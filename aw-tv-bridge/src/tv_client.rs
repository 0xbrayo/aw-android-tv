use std::path::Path;

use anyhow::{Context, Result};
use eventsource_client::{self as es, Client, SSE};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::Value;
use tracing::{debug, info, warn};

use crate::aw_client::{AwClient, TvBucket, TvEvent};
use crate::state::SyncState;

#[derive(Debug, Deserialize)]
struct SseData {
    seq: Option<i64>,
    bucket: String,
    event: SseEventPayload,
}

#[derive(Debug, Deserialize)]
struct SseEventPayload {
    id: Option<i64>,
    timestamp: i64,
    duration: i64,
    #[serde(rename = "bucketId")]
    bucket_id: String,
    data: Value,
}

/// Runs the SSE stream until the server closes it or a non-retryable error occurs.
/// Caller is responsible for reconnection with backoff.
pub async fn run_stream(
    tv_host: &str,
    tv_port: u16,
    since: u64,
    aw: &AwClient,
    state: &mut SyncState,
    state_path: &Path,
) -> Result<()> {
    let base = format!("http://{tv_host}:{tv_port}/api/v0");
    let url = format!("{base}/sync/stream?since={since}");

    let mut builder = es::ClientBuilder::for_url(&url)?
        .header("Accept", "text/event-stream")?;

    // On reconnect, supply Last-Event-ID so the TV replays missed events
    if state.last_seq > 0 {
        builder = builder.last_event_id(state.last_seq.to_string());
        info!(last_seq = state.last_seq, "Reconnecting with Last-Event-ID");
    }

    let client = builder.build();
    let mut stream = client.stream();

    info!(url = %url, "SSE stream open");

    while let Some(item) = stream.next().await {
        match item {
            Ok(SSE::Event(ev)) if ev.event_type == "event" => {
                let data: SseData = match serde_json::from_str(&ev.data) {
                    Ok(d) => d,
                    Err(e) => {
                        warn!("Failed to parse SSE event: {e}  data={}", ev.data);
                        continue;
                    }
                };

                let tv_event = TvEvent {
                    id: data.event.id,
                    timestamp: data.event.timestamp,
                    duration: data.event.duration,
                    bucket_id: data.event.bucket_id.clone(),
                    data: data.event.data,
                };

                // Ensure the bucket exists (handles on-the-fly new buckets)
                if let Err(e) = ensure_bucket_for_event(tv_host, tv_port, &data.bucket, aw).await {
                    warn!("Could not ensure bucket '{}': {e:#}", data.bucket);
                }

                match aw.post_event(&data.bucket, &tv_event).await {
                    Ok(()) => {
                        if let Some(seq) = data.seq {
                            ack(tv_host, tv_port, seq, state, state_path).await;
                        }
                    }
                    Err(e) => {
                        warn!("Failed to forward event to AW: {e:#}  (will retry on reconnect)");
                        // Don't ACK — let the TV buffer it for the next reconnect
                    }
                }
            }
            Ok(SSE::Event(_)) => {
                // unknown event type, ignore
            }
            Ok(SSE::Comment(_)) => {
                // keepalive ": keepalive"
                debug!("SSE keepalive");
            }
            Ok(SSE::Connected(_)) => {
                info!("SSE connection established");
            }
            Err(e) => {
                return Err(anyhow::anyhow!("SSE stream error: {e}"));
            }
        }
    }

    Ok(())
}

async fn ack(tv_host: &str, tv_port: u16, seq: i64, state: &mut SyncState, state_path: &Path) {
    let url = format!("http://{tv_host}:{tv_port}/api/v0/sync/ack");
    let body = serde_json::json!({"seq": seq});
    match reqwest::Client::new().post(&url).json(&body).send().await {
        Ok(resp) if resp.status().is_success() => {
            state.last_seq = seq;
            state.save(state_path);
            debug!(seq, "ACKed");
        }
        Ok(resp) => {
            warn!(seq, status = %resp.status(), "ACK rejected by TV");
        }
        Err(e) => {
            warn!(seq, "ACK request failed: {e}");
        }
    }
}

async fn ensure_bucket_for_event(
    tv_host: &str,
    tv_port: u16,
    bucket_id: &str,
    aw: &AwClient,
) -> Result<()> {
    let url = format!("http://{tv_host}:{tv_port}/api/v0/buckets");
    let buckets: Vec<TvBucket> = reqwest::get(&url)
        .await
        .context("GET /buckets")?
        .json()
        .await
        .context("parse /buckets")?;

    if let Some(b) = buckets.iter().find(|b| b.id == bucket_id) {
        aw.ensure_bucket(b).await?;
    }
    Ok(())
}

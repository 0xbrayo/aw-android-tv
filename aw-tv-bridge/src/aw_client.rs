use anyhow::{bail, Result};
use chrono::{DateTime, TimeZone, Utc};
use reqwest::StatusCode;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tracing::{debug, warn};

// ── TV-side types (deserialized from TV API) ─────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub struct TvBucket {
    pub id: String,
    #[serde(rename = "type")]
    pub bucket_type: String,
    pub client: String,
    pub hostname: String,
    #[allow(dead_code)]
    pub created: Option<i64>,
    #[allow(dead_code)]
    pub name: Option<String>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct TvEvent {
    #[allow(dead_code)]
    pub id: Option<i64>,
    pub timestamp: i64,   // ms since epoch
    pub duration: i64,    // ms
    #[allow(dead_code)]
    #[serde(rename = "bucketId")]
    pub bucket_id: String,
    pub data: Value,      // already parsed object (SyncServer puts parsed JSON here)
}

// ── AW-server-side types (serialized for POST) ───────────────────────────────

#[derive(Debug, Serialize)]
struct AwBucketCreate<'a> {
    client: &'a str,
    #[serde(rename = "type")]
    bucket_type: &'a str,
    hostname: &'a str,
}

#[derive(Debug, Serialize)]
struct AwEvent {
    timestamp: String,  // ISO-8601 UTC  e.g. "2024-05-16T12:34:56.000000Z"
    duration: f64,      // seconds
    data: Value,
}

// ── Client ────────────────────────────────────────────────────────────────────

pub struct AwClient {
    http: reqwest::Client,
    base: String,
}

impl AwClient {
    pub fn new(host: &str, port: u16) -> Self {
        Self {
            http: reqwest::Client::new(),
            base: format!("http://{host}:{port}/api/0"),
        }
    }

    /// Creates the bucket on the AW server if it doesn't already exist.
    pub async fn ensure_bucket(&self, b: &TvBucket) -> Result<()> {
        let url = format!("{}/buckets/{}", self.base, urlencoding(&b.id));
        let body = AwBucketCreate {
            client: &b.client,
            bucket_type: &b.bucket_type,
            hostname: &b.hostname,
        };
        let resp = self.http.post(&url).json(&body).send().await?;
        match resp.status() {
            StatusCode::OK | StatusCode::CREATED => {
                debug!(bucket = %b.id, "Bucket ensured");
            }
            // 304 Not Modified — bucket already exists, fine
            StatusCode::NOT_MODIFIED => {}
            // Some AW server versions return 409 Conflict for existing buckets
            StatusCode::CONFLICT => {}
            other => {
                let text = resp.text().await.unwrap_or_default();
                warn!(bucket = %b.id, status = %other, body = %text, "Unexpected response creating bucket");
            }
        }
        Ok(())
    }

    /// Posts a single event to the AW server.
    /// `bucket_id` is the raw (un-URL-encoded) bucket ID.
    pub async fn post_event(&self, bucket_id: &str, tv_event: &TvEvent) -> Result<()> {
        let aw_event = transform(tv_event)?;
        let url = format!("{}/buckets/{}/events", self.base, urlencoding(bucket_id));
        let resp = self
            .http
            .post(&url)
            .json(&[&aw_event])
            .send()
            .await?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            bail!("AW server rejected event: {status}  {body}");
        }
        debug!(bucket = %bucket_id, ts = %aw_event.timestamp, "Event forwarded");
        Ok(())
    }
}

// ── Transform ─────────────────────────────────────────────────────────────────

fn transform(e: &TvEvent) -> Result<AwEvent> {
    let dt: DateTime<Utc> = Utc
        .timestamp_millis_opt(e.timestamp)
        .single()
        .ok_or_else(|| anyhow::anyhow!("invalid timestamp: {}", e.timestamp))?;

    // data arrives already parsed from SyncServer; if it's a string (shouldn't
    // happen but be safe), try to parse it as JSON
    let data = match &e.data {
        Value::String(s) => serde_json::from_str(s).unwrap_or_else(|_| e.data.clone()),
        other => other.clone(),
    };

    Ok(AwEvent {
        timestamp: dt.format("%Y-%m-%dT%H:%M:%S%.6fZ").to_string(),
        duration: e.duration as f64 / 1000.0,
        data,
    })
}

fn urlencoding(s: &str) -> String {
    s.chars()
        .flat_map(|c| {
            if c.is_alphanumeric() || matches!(c, '-' | '_' | '.' | '~') {
                vec![c]
            } else {
                // percent-encode
                c.to_string()
                    .as_bytes()
                    .iter()
                    .flat_map(|b| format!("%{b:02X}").chars().collect::<Vec<_>>())
                    .collect()
            }
        })
        .collect()
}

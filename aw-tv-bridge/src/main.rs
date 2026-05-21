mod aw_client;
mod discovery;
mod state;
mod tv_client;

use anyhow::{Context, Result};
use clap::Parser;
use std::path::PathBuf;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

#[derive(Parser, Debug)]
#[command(name = "aw-tv-bridge", about = "Bridge aw-android-tv to an ActivityWatch server")]
pub struct Args {
    /// Skip mDNS discovery and connect directly to this IP
    #[arg(long, env = "AW_TV_HOST")]
    pub tv_host: Option<String>,

    /// TV sync server port
    #[arg(long, default_value = "5606", env = "AW_TV_PORT")]
    pub tv_port: u16,

    /// ActivityWatch server host
    #[arg(long, default_value = "localhost", env = "AW_HOST")]
    pub aw_host: String,

    /// ActivityWatch server port
    #[arg(long, default_value = "5600", env = "AW_PORT")]
    pub aw_port: u16,

    /// Only sync events after this timestamp (ms since epoch)
    #[arg(long, default_value = "0")]
    pub since: u64,

    /// Path to persist sync state (last ACKed seq)
    #[arg(long)]
    pub state_file: Option<PathBuf>,
}

impl Args {
    pub fn state_path(&self) -> PathBuf {
        self.state_file.clone().unwrap_or_else(|| {
            dirs::home_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join(".aw-tv-bridge")
                .join("state.json")
        })
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("aw_tv_bridge=info".parse()?))
        .init();

    let args = Args::parse();

    // Resolve the TV address
    let tv_addr = match &args.tv_host {
        Some(host) => {
            info!(host, port = args.tv_port, "Using provided TV address");
            (host.clone(), args.tv_port)
        }
        None => {
            info!("Starting mDNS discovery for _activitywatch-tv._tcp ...");
            discovery::discover_tv()
                .await
                .context("mDNS discovery failed — is the TV on the network? Or pass --tv-host")?
        }
    };

    info!(host = %tv_addr.0, port = tv_addr.1, "Connected to TV");

    let aw = aw_client::AwClient::new(&args.aw_host, args.aw_port);
    let state_path = args.state_path();
    let mut sync_state = state::SyncState::load(&state_path);

    info!(
        aw_url = %format!("http://{}:{}", args.aw_host, args.aw_port),
        last_seq = sync_state.last_seq,
        "Starting bridge"
    );

    // Bootstrap: sync buckets from TV to AW server
    let tv_base = format!("http://{}:{}/api/v0", tv_addr.0, tv_addr.1);
    if let Err(e) = bootstrap_buckets(&tv_base, &aw).await {
        warn!("Failed to bootstrap buckets: {e:#}  (will retry on first event)");
    }

    // Run the sync loop with reconnection
    let mut backoff_secs: u64 = 1;
    loop {
        match tv_client::run_stream(
            &tv_addr.0,
            tv_addr.1,
            args.since,
            &aw,
            &mut sync_state,
            &state_path,
        )
        .await
        {
            Ok(()) => {
                info!("Stream closed cleanly");
                break;
            }
            Err(e) => {
                error!("Stream error: {e:#}  — reconnecting in {backoff_secs}s");
                tokio::time::sleep(std::time::Duration::from_secs(backoff_secs)).await;
                backoff_secs = (backoff_secs * 2).min(60);
            }
        }
    }

    Ok(())
}

async fn bootstrap_buckets(tv_base: &str, aw: &aw_client::AwClient) -> Result<()> {
    let buckets: Vec<aw_client::TvBucket> = reqwest::get(format!("{tv_base}/buckets"))
        .await?
        .json()
        .await?;

    for b in &buckets {
        aw.ensure_bucket(b).await?;
    }
    info!("Bootstrapped {} bucket(s)", buckets.len());
    Ok(())
}

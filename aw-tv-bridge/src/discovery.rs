use anyhow::{bail, Result};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::time::Duration;
use tracing::{debug, info};

const MDNS_TYPE: &str = "_activitywatch-tv._tcp.local.";
const DISCOVER_TIMEOUT: Duration = Duration::from_secs(15);

pub async fn discover_tv() -> Result<(String, u16)> {
    let daemon = ServiceDaemon::new()?;
    let receiver = daemon.browse(MDNS_TYPE)?;

    let result = tokio::time::timeout(DISCOVER_TIMEOUT, async move {
        loop {
            match receiver.recv_async().await {
                Ok(ServiceEvent::ServiceResolved(info)) => {
                    let port = info.get_port();
                    // prefer IPv4
                    let ip = info
                        .get_addresses_v4()
                        .into_iter()
                        .next()
                        .map(|a| a.to_string())
                        .or_else(|| {
                            info.get_addresses()
                                .iter()
                                .next()
                                .map(|a| a.to_string())
                        });

                    if let Some(ip) = ip {
                        info!(service = %info.get_fullname(), %ip, port, "Discovered TV");
                        return Ok((ip, port));
                    }
                    debug!(service = %info.get_fullname(), "Resolved but no IP yet, continuing");
                }
                Ok(other) => {
                    debug!(?other, "mDNS event");
                }
                Err(e) => {
                    return Err(anyhow::anyhow!("mDNS channel error: {e}"));
                }
            }
        }
    })
    .await;

    daemon.shutdown()?;

    match result {
        Ok(r) => r,
        Err(_) => bail!("mDNS discovery timed out after {DISCOVER_TIMEOUT:?}"),
    }
}

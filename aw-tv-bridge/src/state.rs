use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tracing::{debug, warn};

#[derive(Debug, Serialize, Deserialize, Default, Clone)]
pub struct SyncState {
    /// Last SSE sequence number that was successfully ACKed to the TV.
    pub last_seq: i64,
}

impl SyncState {
    pub fn load(path: &Path) -> Self {
        match std::fs::read_to_string(path) {
            Ok(text) => serde_json::from_str(&text).unwrap_or_else(|e| {
                warn!("Could not parse state file: {e}  — starting fresh");
                Self::default()
            }),
            Err(_) => {
                debug!("No state file at {}  — starting fresh", path.display());
                Self::default()
            }
        }
    }

    pub fn save(&self, path: &Path) {
        let tmp = path.with_extension("json.tmp");
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match serde_json::to_string_pretty(self) {
            Ok(text) => {
                if let Err(e) = std::fs::write(&tmp, &text) {
                    warn!("Failed to write state tmp file: {e}");
                    return;
                }
                if let Err(e) = std::fs::rename(&tmp, path) {
                    warn!("Failed to rename state file: {e}");
                }
            }
            Err(e) => warn!("Failed to serialize state: {e}"),
        }
    }
}

#[allow(dead_code)]
pub fn default_state_path() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".aw-tv-bridge")
        .join("state.json")
}

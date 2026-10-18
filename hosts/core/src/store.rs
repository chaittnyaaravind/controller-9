//! Persistent host state: static identity key, paired controllers, configuration.
//! Files live in the per-user config directory; the key file is created with mode 0600 on Unix
//! and inherits the per-user profile ACL on Windows. Keys are never logged.
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use nine_proto::noise::KeyPair;
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct HostConfig {
    pub name: String,
    pub tcp_port: u16,
    pub udp_port: u16,
    pub max_slots: u8,
    pub timeout_ms: u32,
    pub heartbeat_ms: u32,
    pub idle_heartbeat_ms: u32,
    pub grace_s: u32,
    /// Advertise via DNS-SD on the local network.
    pub advertise: bool,
    /// Bind address; default all interfaces. Use 127.0.0.1 for tests.
    pub bind: String,
}

impl Default for HostConfig {
    fn default() -> Self {
        HostConfig {
            name: default_name(), tcp_port: nine_proto::DEFAULT_TCP_PORT, udp_port: nine_proto::DEFAULT_UDP_PORT,
            max_slots: 4, timeout_ms: 250, heartbeat_ms: 50, idle_heartbeat_ms: 200, grace_s: 30,
            advertise: true, bind: "0.0.0.0".into(),
        }
    }
}

fn default_name() -> String {
    std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).ok()
        .or_else(|| std::process::Command::new("hostname").output().ok().and_then(|o| String::from_utf8(o.stdout).ok()))
        .map(|s| s.trim().trim_end_matches(".local").to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "9-controller host".into())
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    /// Controller static public key, base64.
    pub key: String,
    pub name: String,
    pub platform: String,
    pub paired_at: String,
    pub last_seen: Option<String>,
}

pub struct Store {
    dir: Option<PathBuf>,
    pub identity: KeyPair,
    pub paired: Vec<PairedDevice>,
    pub config: HostConfig,
}

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error("{0}: {1}")]
    Io(String, std::io::Error),
    #[error("corrupt {0}: {1}")]
    Corrupt(String, String),
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<(), StoreError> {
    let tmp = path.with_extension("tmp");
    let io = |e| StoreError::Io(tmp.display().to_string(), e);
    {
        let mut o = std::fs::OpenOptions::new();
        o.write(true).create(true).truncate(true);
        #[cfg(unix)]
        { use std::os::unix::fs::OpenOptionsExt; o.mode(0o600); }
        let mut f = o.open(&tmp).map_err(io)?;
        f.write_all(bytes).map_err(io)?;
        f.sync_all().map_err(io)?;
    }
    std::fs::rename(&tmp, path).map_err(|e| StoreError::Io(path.display().to_string(), e))
}

impl Store {
    pub fn default_dir() -> Option<PathBuf> {
        directories::ProjectDirs::from("dev", "9-controller", "9-controller").map(|d| d.config_dir().to_path_buf())
    }

    /// Ephemeral store (tests, `--ephemeral`): nothing touches disk.
    pub fn in_memory(config: HostConfig) -> Store {
        Store { dir: None, identity: KeyPair::generate(), paired: Vec::new(), config }
    }

    pub fn open(dir: &Path) -> Result<Store, StoreError> {
        std::fs::create_dir_all(dir).map_err(|e| StoreError::Io(dir.display().to_string(), e))?;
        #[cfg(unix)]
        { use std::os::unix::fs::PermissionsExt; let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700)); }
        let key_path = dir.join("host_key");
        let identity = match std::fs::read(&key_path) {
            Ok(b) => KeyPair::from_secret(b.try_into().map_err(|_| StoreError::Corrupt("host_key".into(), "expected 32 bytes".into()))?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                let kp = KeyPair::generate();
                write_private(&key_path, &kp.secret_bytes())?;
                kp
            }
            Err(e) => return Err(StoreError::Io(key_path.display().to_string(), e)),
        };
        let read_json = |name: &str| -> Result<Option<String>, StoreError> {
            match std::fs::read_to_string(dir.join(name)) {
                Ok(s) => Ok(Some(s)),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
                Err(e) => Err(StoreError::Io(name.into(), e)),
            }
        };
        let paired = match read_json("paired.json")? {
            Some(s) => serde_json::from_str(&s).map_err(|e| StoreError::Corrupt("paired.json".into(), e.to_string()))?,
            None => Vec::new(),
        };
        let config = match read_json("config.json")? {
            Some(s) => serde_json::from_str(&s).map_err(|e| StoreError::Corrupt("config.json".into(), e.to_string()))?,
            None => HostConfig::default(),
        };
        Ok(Store { dir: Some(dir.to_path_buf()), identity, paired, config })
    }

    pub fn save(&self) -> Result<(), StoreError> {
        let Some(dir) = &self.dir else { return Ok(()) };
        write_private(&dir.join("paired.json"), serde_json::to_string_pretty(&self.paired).unwrap().as_bytes())?;
        write_private(&dir.join("config.json"), serde_json::to_string_pretty(&self.config).unwrap().as_bytes())
    }

    pub fn is_paired(&self, key: &[u8; 32]) -> bool {
        let k = B64.encode(key);
        self.paired.iter().any(|d| d.key == k)
    }

    pub fn add_paired(&mut self, key: &[u8; 32], name: &str, platform: &str) {
        let k = B64.encode(key);
        self.paired.retain(|d| d.key != k);
        self.paired.push(PairedDevice { key: k, name: name.into(), platform: platform.into(), paired_at: crate::time::rfc3339_now(), last_seen: None });
    }

    pub fn touch(&mut self, key: &[u8; 32]) {
        let k = B64.encode(key);
        if let Some(d) = self.paired.iter_mut().find(|d| d.key == k) { d.last_seen = Some(crate::time::rfc3339_now()); }
    }

    pub fn remove(&mut self, key_b64: &str) -> bool {
        let n = self.paired.len();
        self.paired.retain(|d| d.key != key_b64);
        n != self.paired.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn persists_identity_and_pairings() {
        let dir = std::env::temp_dir().join(format!("9ctl-store-{}", std::process::id()));
        let s = Store::open(&dir).unwrap();
        let pubkey = s.identity.public;
        let mut s = s;
        s.add_paired(&[1; 32], "Phone", "android");
        s.save().unwrap();
        let s2 = Store::open(&dir).unwrap();
        assert_eq!(s2.identity.public, pubkey);
        assert!(s2.is_paired(&[1; 32]) && !s2.is_paired(&[2; 32]));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(std::fs::metadata(dir.join("host_key")).unwrap().permissions().mode() & 0o777, 0o600);
        }
        for f in ["host_key", "paired.json", "config.json"] { std::fs::remove_file(dir.join(f)).unwrap(); }
        std::fs::remove_dir(&dir).unwrap();
    }
}

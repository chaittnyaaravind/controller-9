//! Virtual-controller backend interface. Each OS backend creates one OS-visible gamepad per slot.
use nine_proto::frame::Feedback;
use nine_proto::input::InputState;
use parking_lot::Mutex;
use serde::Serialize;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BackendKind {
    /// Creates real OS-visible controllers.
    Native,
    /// The mechanism exists but an external prerequisite (driver, entitlement, permission) is missing.
    Blocked,
    /// Test double. Never presented as controller support.
    Mock,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackendStatus {
    pub name: String,
    pub kind: BackendKind,
    pub available: bool,
    pub max_pads: usize,
    /// Human-readable state, e.g. "uinput ready" or "entitlement missing".
    pub detail: String,
    /// What the user must do to fix a blocked backend.
    pub remedy: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum BackendError {
    #[error("virtual controller backend unavailable: {0}")]
    Unavailable(String),
    #[error("virtual controller I/O error: {0}")]
    Io(String),
}

pub trait VirtualPad: Send {
    fn submit(&mut self, state: &InputState) -> Result<(), BackendError>;
    /// Rumble requested by the game, if the backend supports force feedback.
    fn poll_feedback(&mut self) -> Option<Feedback> { None }
}

pub trait Backend: Send + Sync {
    fn status(&self) -> BackendStatus;
    fn create(&self, slot: u8, name: &str) -> Result<Box<dyn VirtualPad>, BackendError>;
}

/// Records submitted states; used by tests and the explicitly labelled "preview" mode.
#[derive(Default, Clone)]
pub struct MockBackend { pub log: Arc<Mutex<Vec<(u8, InputState)>>>, pub feedback: Arc<Mutex<Vec<(u8, Feedback)>>> }

struct MockPad { slot: u8, log: Arc<Mutex<Vec<(u8, InputState)>>>, feedback: Arc<Mutex<Vec<(u8, Feedback)>>> }

impl VirtualPad for MockPad {
    fn submit(&mut self, s: &InputState) -> Result<(), BackendError> { self.log.lock().push((self.slot, *s)); Ok(()) }
    fn poll_feedback(&mut self) -> Option<Feedback> {
        let mut f = self.feedback.lock();
        let i = f.iter().position(|(s, _)| *s == self.slot)?;
        Some(f.remove(i).1)
    }
}

impl Backend for MockBackend {
    fn status(&self) -> BackendStatus {
        BackendStatus { name: "mock".into(), kind: BackendKind::Mock, available: true, max_pads: 4,
            detail: "Preview mode — no OS-visible controller is created".into(), remedy: None }
    }
    fn create(&self, slot: u8, _: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        Ok(Box::new(MockPad { slot, log: self.log.clone(), feedback: self.feedback.clone() }))
    }
}

/// Backend that reports a documented external blocker and never pretends to inject input.
pub struct BlockedBackend { pub status: BackendStatus }
impl Backend for BlockedBackend {
    fn status(&self) -> BackendStatus { self.status.clone() }
    fn create(&self, _: u8, _: &str) -> Result<Box<dyn VirtualPad>, BackendError> {
        Err(BackendError::Unavailable(self.status.detail.clone()))
    }
}

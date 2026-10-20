//! Controller-side client (reference for the phone apps; used by `ninectl sim` and tests).
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use nine_proto::control::{self, Control, ControllerHello, HostHello, InitHello};
use nine_proto::frame::{self, Counter, Feedback, Header, InputFrame, PacketType, Ping, Pong, ReplayWindow, Seen};
use nine_proto::input::InputState;
use nine_proto::noise::{Handshake, KeyPair, Role, Transport};
use nine_proto::pairing::{commitment, qr_proof, sas_code, PairUri};
use nine_proto::stats::Window;
use std::collections::HashMap;
use std::net::SocketAddr;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::mpsc;

type Rd = Box<dyn AsyncRead + Unpin + Send>;
type Wr = Box<dyn AsyncWrite + Unpin + Send>;

#[derive(Debug, thiserror::Error)]
pub enum ClientError {
    #[error("network: {0}")] Io(#[from] std::io::Error),
    #[error("protocol: {0}")] Protocol(String),
    #[error("host refused: {0}")] Refused(String),
    #[error("host identity does not match the QR code")] WrongHost,
}

fn proto(e: impl std::fmt::Display) -> ClientError { ClientError::Protocol(e.to_string()) }

pub struct Connection {
    rd: Rd,
    wr: Wr,
    t: Transport,
    pub host: HostHello,
    pub host_static: [u8; 32],
    pub handshake_hash: [u8; 32],
}

#[derive(Debug, Clone)]
pub struct SessionInfo {
    pub slot: u8,
    pub session_id: u32,
    pub udp_port: u16,
    pub k_in: [u8; 32],
    pub k_out: [u8; 32],
    pub heartbeat_ms: u32,
    pub idle_heartbeat_ms: u32,
    pub timeout_ms: u32,
    pub backend_warning: Option<String>,
}

impl Connection {
    /// IP binding: TCP control channel.
    pub async fn connect(addr: SocketAddr, identity: &KeyPair, hello: &ControllerHello) -> Result<Connection, ClientError> {
        let stream = TcpStream::connect(addr).await?;
        stream.set_nodelay(true)?;
        let (rd, wr) = stream.into_split();
        Self::connect_over(Box::new(rd), Box::new(wr), identity, hello).await
    }

    /// Any binding: run the handshake over an already-framed control stream (`len:u16be || frame`).
    pub async fn connect_over(mut rd: Rd, mut wr: Wr, identity: &KeyPair, hello: &ControllerHello) -> Result<Connection, ClientError> {
        let mut hs = Handshake::new(Role::Initiator, KeyPair::from_secret(identity.secret_bytes()));
        let m1 = hs.write_message(&serde_json::to_vec(&InitHello { proto: vec![1] }).unwrap()).map_err(proto)?;
        wr.write_all(&control::frame(&m1)).await?;
        let m2 = read_frame(&mut rd).await?;
        let host: HostHello = serde_json::from_slice(&hs.read_message(&m2).map_err(proto)?).map_err(proto)?;
        let m3 = hs.write_message(&serde_json::to_vec(hello).unwrap()).map_err(proto)?;
        wr.write_all(&control::frame(&m3)).await?;
        let t = hs.into_transport().map_err(proto)?;
        Ok(Connection { rd, wr, host_static: t.remote_static, handshake_hash: t.handshake_hash, t, host })
    }

    pub async fn send(&mut self, m: &Control) -> Result<(), ClientError> {
        let ct = self.t.encrypt(&m.to_json()).map_err(proto)?;
        self.wr.write_all(&control::frame(&ct)).await?;
        Ok(())
    }

    pub async fn recv(&mut self) -> Result<Control, ClientError> {
        let ct = read_frame(&mut self.rd).await?;
        Control::from_json(&self.t.decrypt(&ct).map_err(proto)?).map_err(proto)
    }

    /// QR pairing. Aborts before proving anything if the host key differs from the QR code.
    pub async fn pair_qr(&mut self, uri: &PairUri) -> Result<(), ClientError> {
        if self.host_static != uri.host_static { return Err(ClientError::WrongHost); }
        let proof = B64.encode(qr_proof(&uri.token, &self.handshake_hash));
        self.send(&Control::PairQr { proof }).await?;
        match self.recv().await? {
            Control::Paired => Ok(()),
            Control::PairFailed { reason } => Err(ClientError::Refused(reason)),
            Control::Error { code, .. } => Err(ClientError::Refused(code)),
            o => Err(proto(format!("unexpected {o:?}"))),
        }
    }

    /// Numeric comparison. `confirm` shows the code to the user and returns their verdict.
    pub async fn pair_code(&mut self, confirm: impl FnOnce(u32) -> bool) -> Result<u32, ClientError> {
        self.send(&Control::PairCode).await?;
        let c = match self.recv().await? {
            Control::Commit { c } => B64.decode(c).map_err(proto)?,
            Control::Error { code, .. } => return Err(ClientError::Refused(code)),
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        let na = nine_proto::random_bytes::<16>();
        self.send(&Control::Nonce { n: B64.encode(na) }).await?;
        let nb: [u8; 16] = match self.recv().await? {
            Control::Reveal { n } => B64.decode(n).map_err(proto)?.try_into().map_err(|_| proto("nonce length"))?,
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        if commitment(&self.handshake_hash, &nb).as_slice() != c.as_slice() {
            return Err(ClientError::Protocol("host commitment mismatch — possible interception, pairing aborted".into()));
        }
        let code = sas_code(&self.handshake_hash, &na, &nb);
        let ok = confirm(code);
        self.send(if ok { &Control::CodeOk } else { &Control::CodeReject }).await?;
        if !ok { return Err(ClientError::Refused("rejected on controller".into())); }
        match self.recv().await? {
            Control::Paired => Ok(code),
            Control::PairFailed { reason } => Err(ClientError::Refused(reason)),
            o => Err(proto(format!("unexpected {o:?}"))),
        }
    }

    pub async fn open(&mut self, resume: Option<u8>, profile: Option<&str>) -> Result<SessionInfo, ClientError> {
        self.open_with(Control::Open { resume_slot: resume, profile: profile.map(String::from), join: None }).await
    }

    /// Add this link to the live session `session_id` (SPEC §8.5). The host answers with the same
    /// slot, session id and keys; datagrams sealed once can then be sent over every joined link.
    pub async fn join(&mut self, session_id: u32) -> Result<SessionInfo, ClientError> {
        if !self.host.multipath { return Err(ClientError::Refused("host does not support multipath".into())); }
        self.open_with(Control::Open { resume_slot: None, profile: None, join: Some(session_id) }).await
    }

    async fn open_with(&mut self, open: Control) -> Result<SessionInfo, ClientError> {
        self.send(&open).await?;
        let key = |s: &str| -> Result<[u8; 32], ClientError> { B64.decode(s).map_err(proto)?.try_into().map_err(|_| proto("key length")) };
        let mut info = match self.recv().await? {
            Control::Session { slot, session_id, udp_port, k_in, k_out, heartbeat_ms, idle_heartbeat_ms, timeout_ms } => SessionInfo {
                slot, session_id, udp_port, k_in: key(&k_in.0)?, k_out: key(&k_out.0)?, heartbeat_ms, idle_heartbeat_ms, timeout_ms, backend_warning: None },
            Control::PairRequired { .. } => return Err(ClientError::Refused("not_paired".into())),
            Control::Error { code, .. } => return Err(ClientError::Refused(code)),
            o => return Err(proto(format!("unexpected {o:?}"))),
        };
        // An optional backend warning follows immediately when the host could not create a pad.
        if let Ok(Ok(Control::Error { code, message })) = tokio::time::timeout(std::time::Duration::from_millis(50), self.recv()).await
            && code == "backend_unavailable" { info.backend_warning = message.or(Some(code)); }
        Ok(info)
    }
}

async fn read_frame<R: AsyncRead + Unpin + ?Sized>(s: &mut R) -> Result<Vec<u8>, ClientError> {
    let n = s.read_u16().await? as usize;
    if n == 0 { return Err(proto("empty frame")); }
    let mut b = vec![0u8; n];
    s.read_exact(&mut b).await?;
    Ok(b)
}

/// How input datagrams travel: UDP (IP binding) or a link channel (stream / BLE bindings).
pub enum DatagramPath {
    Udp(UdpSocket),
    Link { tx: mpsc::UnboundedSender<Vec<u8>>, rx: mpsc::UnboundedReceiver<Vec<u8>> },
}

impl DatagramPath {
    async fn send(&self, d: &[u8]) -> Result<(), ClientError> {
        match self {
            DatagramPath::Udp(s) => { s.send(d).await?; }
            DatagramPath::Link { tx, .. } => tx.send(d.to_vec()).map_err(|_| ClientError::Io(std::io::ErrorKind::BrokenPipe.into()))?,
        }
        Ok(())
    }
    fn try_recv(&mut self) -> Option<Vec<u8>> {
        let mut buf = [0u8; 512];
        match self {
            DatagramPath::Udp(s) => s.try_recv(&mut buf).ok().map(|n| buf[..n].to_vec()),
            DatagramPath::Link { rx, .. } => rx.try_recv().ok(),
        }
    }
    async fn recv(&mut self) -> Option<Vec<u8>> {
        let mut buf = [0u8; 512];
        match self {
            DatagramPath::Udp(s) => s.recv(&mut buf).await.ok().map(|n| buf[..n].to_vec()),
            DatagramPath::Link { rx, .. } => rx.recv().await,
        }
    }
}

/// Input sender with ping/pong RTT measurement, over any datagram path.
pub struct InputSender {
    pub path: DatagramPath,
    pub info: SessionInfo,
    counter: Counter,
    /// Replay window, not strict ordering: with several links, pongs and feedback copies interleave.
    fresh_in: ReplayWindow,
    pings: HashMap<u32, u64>,
    next_ping: u32,
    pub rtt_ms: Window,
    pub feedback: Vec<Feedback>,
    epoch: std::time::Instant,
}

impl InputSender {
    pub async fn new(host: SocketAddr, info: SessionInfo) -> Result<Self, ClientError> {
        let bind: SocketAddr = if host.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
        let sock = UdpSocket::bind(bind).await?;
        sock.connect(SocketAddr::new(host.ip(), info.udp_port)).await?;
        Ok(Self::over(DatagramPath::Udp(sock), info))
    }

    pub fn over(path: DatagramPath, info: SessionInfo) -> Self {
        Self { path, info, counter: Counter::default(), fresh_in: ReplayWindow::default(), pings: HashMap::new(), next_ping: 1,
            rtt_ms: Window::new(4096), feedback: Vec::new(), epoch: std::time::Instant::now() }
    }

    pub fn now_us(&self) -> u64 { self.epoch.elapsed().as_micros() as u64 }

    pub fn seal(&mut self, ptype: PacketType, payload: &[u8]) -> Vec<u8> {
        frame::seal(&self.info.k_in, Header { ptype, session_id: self.info.session_id, counter: self.counter.bump() }, payload)
    }

    pub async fn send_input(&mut self, state: InputState, flags: u16) -> Result<(), ClientError> {
        let f = InputFrame { t_us: self.now_us(), state, flags };
        let d = self.seal(PacketType::Input, &f.encode());
        self.path.send(&d).await?;
        Ok(())
    }

    /// Send pre-built bytes (tests: replays, tampering).
    pub async fn send_raw(&self, d: &[u8]) -> Result<(), ClientError> { self.path.send(d).await }

    pub async fn ping(&mut self) -> Result<(), ClientError> {
        let id = self.next_ping;
        self.next_ping += 1;
        let t = self.now_us();
        self.pings.insert(id, t);
        let d = self.seal(PacketType::Ping, &Ping { t_us: t, id }.encode());
        self.path.send(&d).await?;
        Ok(())
    }

    /// Ping over another link of the same session (sealed with this sender's counter, so the session
    /// counter stays unique); collect its pong with [`Self::pump_from`].
    pub async fn ping_over(&mut self, path: &DatagramPath) -> Result<(), ClientError> {
        let id = self.next_ping;
        self.next_ping += 1;
        let t = self.now_us();
        self.pings.insert(id, t);
        let d = self.seal(PacketType::Ping, &Ping { t_us: t, id }.encode());
        path.send(&d).await
    }

    /// Like [`Self::pump`], for host datagrams arriving on another link of the same session.
    pub async fn pump_from(&mut self, path: &mut DatagramPath, dur: std::time::Duration) -> usize {
        let end = tokio::time::Instant::now() + dur;
        let mut n = 0;
        while let Ok(Some(d)) = tokio::time::timeout_at(end, path.recv()).await { self.handle(&d); n += 1; }
        n
    }

    pub async fn bye(&mut self) -> Result<(), ClientError> {
        let d = self.seal(PacketType::Bye, &[]);
        self.path.send(&d).await?;
        Ok(())
    }

    /// Process every datagram already queued (non-blocking).
    pub fn drain(&mut self) {
        while let Some(d) = self.path.try_recv() { self.handle(&d); }
    }

    /// Wait up to `dur` for host datagrams.
    pub async fn pump(&mut self, dur: std::time::Duration) {
        let end = tokio::time::Instant::now() + dur;
        while let Ok(Some(d)) = tokio::time::timeout_at(end, self.path.recv()).await { self.handle(&d); }
    }

    fn handle(&mut self, d: &[u8]) {
        let Ok((h, pt)) = frame::open(&self.info.k_out, d) else { return };
        if self.fresh_in.check(h.counter) != Seen::New { return; }
        match h.ptype {
            PacketType::Pong => if let Ok(p) = Pong::decode(&pt)
                && self.pings.remove(&p.id).is_some() { self.rtt_ms.push((self.now_us() - p.t_us) as f64 / 1000.0); },
            PacketType::Feedback => if let Ok(f) = Feedback::decode(&pt) { self.feedback.push(f); },
            _ => {}
        }
    }
}

struct AbortOnDrop(tokio::task::AbortHandle);
impl Drop for AbortOnDrop { fn drop(&mut self) { self.0.abort(); } }

/// Client side of the stream binding (SPEC §8.2) over any reliable byte stream: returns a framed
/// control stream for [`Connection::connect_over`] and a datagram path for [`InputSender::over`].
pub fn stream_link<S>(stream: S) -> (Rd, Wr, DatagramPath)
where S: AsyncRead + AsyncWrite + Send + Unpin + 'static {
    use nine_proto::link::{Record, RecordReader};
    let (mut rd, mut wr) = tokio::io::split(stream);
    let (mut ctl_in_w, ctl_in_r) = tokio::io::duplex(1 << 17);
    let (ctl_out_w, mut ctl_out_r) = tokio::io::duplex(1 << 17);
    let (up_tx, mut up_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let (down_tx, down_rx) = mpsc::unbounded_channel::<Vec<u8>>();
    let reader = tokio::spawn(async move {
        let mut rr = RecordReader::default();
        let mut buf = [0u8; 4096];
        while let Ok(n) = rd.read(&mut buf).await {
            if n == 0 { break; }
            rr.push(&buf[..n]);
            while let Ok(Some(rec)) = rr.next_record() {
                match rec {
                    Record::Control(b) => { if ctl_in_w.write_all(&control::frame(&b)).await.is_err() { return; } }
                    Record::Datagram(d) => { let _ = down_tx.send(d); }
                }
            }
        }
    });
    // When the caller drops the link (control stream and datagram path), the writer ends; stop the
    // reader too so the socket really closes and the host sees this link go away.
    let reader = reader.abort_handle();
    tokio::spawn(async move {
        let _close = AbortOnDrop(reader);
        loop {
            tokio::select! {
                f = read_frame(&mut ctl_out_r) => match f { Ok(b) => if wr.write_all(&Record::Control(b).encode()).await.is_err() { return }, Err(_) => return },
                Some(first) = up_rx.recv() => {
                    // Latest-state coalescing (SPEC §8.2): of the queued *input* datagrams only the newest
                    // is written; pings, byes and other types are never dropped.
                    let is_input = |d: &[u8]| d.get(3) == Some(&(nine_proto::frame::PacketType::Input as u8));
                    // Order is preserved: all datagrams share one counter, so reordering would make
                    // the receiver reject later-written frames as stale.
                    let mut queue = vec![first];
                    while let Ok(more) = up_rx.try_recv() { queue.push(more); }
                    let last_input = queue.iter().rposition(|d| is_input(d));
                    for (i, d) in queue.into_iter().enumerate() {
                        if is_input(&d) && Some(i) != last_input { continue; }
                        if wr.write_all(&Record::Datagram(d).encode()).await.is_err() { return }
                    }
                }
            }
        }
    });
    (Box::new(ctl_in_r), Box::new(ctl_out_w), DatagramPath::Link { tx: up_tx, rx: down_rx })
}

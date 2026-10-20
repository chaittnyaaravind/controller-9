// Controller-9 companion UI. Talks to the Rust host through Tauri commands; when opened in a
// plain browser (design review) it runs against a clearly labelled in-page preview instead.
"use strict";
const T = window.__TAURI__;
const preview = !T;
// Protocol v1 button bits (SPEC §6.3). Face buttons are positional: A = bottom, B = right, X = left, Y = top.
const BTN = ["A", "B", "X", "Y", "LB", "RB", "View", "Menu", "LS", "RS", "↑", "↓", "←", "→", "Guide"];
const $ = (s, root = document) => root.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (v, d = 1, unit = "") => (v == null || Number.isNaN(v) ? "—" : `${Number(v).toFixed(d)}${unit}`);

const api = preview ? previewApi() : {
  invoke: (cmd, args) => T.core.invoke(cmd, args),
  listen: (ev, cb) => T.event.listen(ev, (e) => cb(e.payload)),
};

let view = "overview";
let snap = null;
let pairing = null;
let pending = null;
let installing = false; // ViGEmBus install in progress (Windows)

function show(v) {
  view = v;
  document.querySelectorAll(".view").forEach((el) => (el.hidden = el.id !== `view-${v}`));
  document.querySelectorAll(".nav").forEach((b) => {
    const on = b.dataset.view === v;
    b.classList.toggle("on", on);
    on ? b.setAttribute("aria-current", "page") : b.removeAttribute("aria-current");
  });
  $("#eyebrow").textContent = { overview: "Players", pair: "Pair a phone", devices: "Devices", diagnostics: "Diagnostics", settings: "Settings", help: "Setup & help" }[v];
  if (v === "pair") startPairing();
  if (v === "settings") loadSettings();
  render();
}
document.querySelectorAll("[data-view]").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg; t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => (t.hidden = true), 2600);
}

// ---------------------------------------------------------------- slot health (mirrors host::slot_health)
const HEALTH = {
  live: { tone: "ok", text: (s) => `Live · ${s.linkLabel}` },
  stale: { tone: "warn", text: (s) => `Stale · no input ${fmt((s.silentMs || 0) / 1000, 1)} s` },
  holding: { tone: "warn", text: (s) => `Holding slot · ${s.graceSLeft ?? 0} s` },
  waiting: { tone: "idle", text: (s) => `Waiting for input · ${s.linkLabel}` },
};

// ---------------------------------------------------------------- player bays
// Each bay is built once per (slot, device) and then patched in place, so a refresh of one
// phone never re-renders another phone's card (or steals focus from its buttons).
const bays = new Map(); // slot -> { key, el }

function emptyBay(i) {
  const el = document.createElement("div");
  el.className = "bay empty";
  el.innerHTML = `<div><div class="pindex">P${i + 1}</div><div>Waiting for a controller</div>
    ${i === 0 ? `<button class="btn small" data-act="pair">Pair a phone</button>` : ""}</div>`;
  return el;
}

const STICK = (side) => `<svg width="76" height="92" viewBox="0 0 76 92" role="img" aria-label="${side} stick">
  <circle cx="38" cy="38" r="34" class="ring"/><line x1="38" y1="8" x2="38" y2="68" class="axis"/><line x1="8" y1="38" x2="68" y2="38" class="axis"/>
  <circle cx="38" cy="38" r="9" class="knob" data-k="${side}"/>
  <text x="38" y="88" text-anchor="middle" class="cap">${side.toUpperCase()}</text></svg>`;

function buildBay(s) {
  const el = document.createElement("article");
  el.className = "bay";
  el.setAttribute("aria-label", `Player ${s.slot + 1}`);
  el.innerHTML = `
    <div class="bay-head">
      <div class="who">
        <div class="pindex">P${s.slot + 1} · <span data-f="platform"></span></div>
        <div class="bay-title" data-f="name"></div>
        <div class="bay-sub"><span class="devid" title="Stable device ID (from the phone's public key)" data-f="id"></span> <span data-f="profile"></span></div>
      </div>
      <span class="chip" data-f="health" role="status"></span>
    </div>
    <div class="pad-line" data-f="pad"></div>
    <div class="viz">${STICK("left")}
      <div class="mid">
        <div class="trig"><span class="micro dim">LT</span><div class="track"><i data-f="lt"></i></div></div>
        <div class="trig"><span class="micro dim">RT</span><div class="track"><i data-f="rt"></i></div></div>
        <div class="btns">${BTN.slice(0, 14).map((b, i) => `<span class="b" data-b="${i}">${b}</span>`).join("")}</div>
      </div>
      ${STICK("right")}</div>
    <div class="stats">
      <div class="stat"><b data-f="rate"></b><span>Updates/s</span></div>
      <div class="stat"><b data-f="rtt"></b><span>Latency ms</span></div>
      <div class="stat"><b data-f="p95"></b><span>p95 ms</span></div>
      <div class="stat"><b data-f="loss"></b><span>Loss %</span></div>
    </div>
    <div class="bay-actions"><button class="btn small" data-act="rumble" data-slot="${s.slot}">Test rumble</button>
      <button class="btn small ghost danger" data-act="kick" data-slot="${s.slot}">Disconnect</button></div>`;
  return el;
}

function setText(el, f, v) { const n = $(`[data-f="${f}"]`, el); if (n && n.textContent !== v) n.textContent = v; }

function updateBay(el, s) {
  const h = HEALTH[s.health] || HEALTH.waiting;
  setText(el, "platform", s.platform);
  setText(el, "name", s.name);
  setText(el, "id", `ID ${String(s.deviceId || "").slice(0, 8)}`);
  setText(el, "profile", s.profile ? `· ${s.profile}${s.motion ? " · tilt" : ""}` : "");
  const chip = $('[data-f="health"]', el);
  const cls = `chip ${h.tone}`;
  if (chip.className !== cls) chip.className = cls;
  setText(el, "health", h.text(s));
  el.classList.toggle("grace", s.health === "holding");
  el.classList.toggle("stale", s.health === "stale");
  // Honest about the OS side: input arriving here is not the same as a game seeing a controller.
  setText(el, "pad", s.padOk ? "Virtual controller active — games can see this player" : "Input received here · games can’t see it (no virtual controller)");
  const padLine = $('[data-f="pad"]', el);
  padLine.classList.toggle("warn", !s.padOk);
  padLine.title = s.padError || "";
  const st = s.state;
  for (const [side, x, y] of [["left", st.lx, st.ly], ["right", st.rx, st.ry]]) {
    const k = $(`[data-k="${side}"]`, el);
    k.setAttribute("cx", (38 + (x / 32767) * 26).toFixed(1));
    k.setAttribute("cy", (38 - (y / 32767) * 26).toFixed(1));
    k.classList.toggle("on", Math.abs(x) > 1500 || Math.abs(y) > 1500);
  }
  $('[data-f="lt"]', el).style.width = `${Math.round((st.lt / 65535) * 100)}%`;
  $('[data-f="rt"]', el).style.width = `${Math.round((st.rt / 65535) * 100)}%`;
  el.querySelectorAll(".b").forEach((b) => b.classList.toggle("on", (st.buttons & (1 << Number(b.dataset.b))) !== 0));
  const loss = s.frames + s.lost > 0 ? (100 * s.lost) / (s.frames + s.lost) : null;
  setText(el, "rate", fmt(s.rateHz, 0));
  setText(el, "rtt", fmt(s.phone.rttP50Ms, 0));
  setText(el, "p95", fmt(s.phone.rttP95Ms, 0));
  setText(el, "loss", fmt(loss, 1));
}

function renderBays() {
  const root = $("#bays");
  const bySlot = Object.fromEntries(snap.slots.map((s) => [s.slot, s]));
  for (let i = 0; i < snap.maxSlots; i++) {
    const s = bySlot[i];
    const key = s ? `dev:${s.deviceId}` : "empty";
    let rec = bays.get(i);
    if (!rec || rec.key !== key) {
      const el = s ? buildBay(s) : emptyBay(i);
      if (!preview && rec) el.classList.add("enter");
      rec ? rec.el.replaceWith(el) : root.appendChild(el);
      rec = { key, el };
      bays.set(i, rec);
    }
    if (s) updateBay(rec.el, s);
  }
  for (const [i, rec] of bays) if (i >= snap.maxSlots) { rec.el.remove(); bays.delete(i); }
}

// ---------------------------------------------------------------- rendering
function render() {
  if (!snap) return;
  $("#hostName").textContent = snap.name;
  $("#hostAddr").textContent = snap.address ? `${snap.address}:${snap.tcpPort}` : `port ${snap.tcpPort}`;
  $("#hostVersion").textContent = `v${snap.version || "0.1.0"} · ${snap.os}`;
  $("#manualAddr").textContent = snap.address ? `${snap.address}:${snap.tcpPort}` : "—";
  $("#addrList").innerHTML = (snap.addresses || []).map((a) => `<li><span class="mono">${esc(a.ip)}</span> <span class="dim">${esc(a.label)} · ${esc(a.iface)}</span></li>`).join("")
    || `<li class="dim">No network interface is up. Phones can still connect over Bluetooth.</li>`;
  const ble = snap.ble || {};
  $("#bleStatus").textContent = !ble.supported ? "Bluetooth: not available in this build" : ble.active ? `Bluetooth: ${ble.detail}${ble.connected && ble.connected.length ? " · " + ble.connected.join(", ") : ""}` : `Bluetooth: ${ble.detail || "off"}`;
  $("#linkList").textContent = `Links: ${(snap.links || ["ip"]).join(", ")}${snap.streamPort ? ` · TCP-only port ${snap.streamPort}` : ""}`;
  $("#pairHostName").textContent = snap.name;
  const b = snap.backend;
  const chip = $("#backendChip");
  chip.className = `chip ${b.kind === "native" && b.available ? "ok" : "warn"}`;
  chip.textContent = preview ? "Design preview — no host" : snap.preview ? "Preview mode" : b.kind === "native" && b.available ? (b.name === "macos-keyboard" ? "Keyboard & mouse mode" : "Virtual controller ready") : "Virtual controller unavailable";
  const banner = $("#banner");
  if (!preview && (snap.preview || !b.available)) {
    banner.hidden = false;
    banner.innerHTML = snap.preview
      ? `<b>Preview mode.</b><span class="fix">Input is received and shown here, but no controller is created for games.</span>`
      : b.name === "windows-vigem"
        ? `<b>Games on this computer can’t see Controller-9 yet.</b><span class="fix">${installing ? "Installing the free ViGEmBus driver — approve the Windows prompt…" : "One free driver (ViGEmBus) is needed once. Windows will ask for permission."}</span>
           <button class="btn primary small" data-act="install-driver" ${installing ? "disabled" : ""}>${installing ? "Installing…" : "Install driver"}</button>`
        : b.name === "macos-keyboard"
        ? `<b>Allow Controller-9 to press keys for you.</b><span class="fix">On Mac the phone plays as keyboard and mouse. macOS asks once: turn on Controller-9 under Privacy &amp; Security → Accessibility.</span>
           <button class="btn primary small" data-act="request-access">Allow keyboard control</button>`
        : `<b>Games on this computer can’t see Controller-9 yet.</b><span class="fix">${esc(b.detail)}${b.remedy ? ` — ${esc(b.remedy)}` : ""}</span>`;
  } else banner.hidden = true;
  $("#helpBackend").innerHTML = `<p><b>${esc(b.name)}</b> — ${esc(b.detail)}</p>${b.remedy ? `<p>${esc(b.remedy)}</p>` : ""}
    <p>Up to ${snap.maxSlots} players. Each phone becomes its own controller.</p>`;

  if (view === "overview") renderBays();
  if (view === "devices") {
    $("#devices").innerHTML = snap.paired.map((d) => `<tr><td>${esc(d.name)}</td><td class="muted">${esc(d.platform)}</td>
      <td class="mono">${esc(d.pairedAt.replace("T", " ").replace("Z", ""))}</td><td class="mono">${esc((d.lastSeen || "—").replace("T", " ").replace("Z", ""))}</td>
      <td><button class="btn small ghost danger" data-act="unpair" data-key="${esc(d.key)}">Forget</button></td></tr>`).join("");
    $("#devicesEmpty").hidden = snap.paired.length > 0;
  }
  if (view === "diagnostics") {
    $("#diag").innerHTML = snap.slots.length ? snap.slots.map((s) => `<tr><td>P${s.slot + 1} ${esc(s.name)}<br><span class="dim">${esc(s.linkLabel)} · ID ${esc(String(s.deviceId || "").slice(0, 8))} · ${esc(s.health)}</span></td>
      <td>${fmt(s.rateHz, 0, " Hz")}</td><td>${fmt(s.interarrivalP50Ms, 1)} / ${fmt(s.interarrivalP95Ms, 1)} ms</td><td>${fmt(s.jitterMs, 2)} ms</td>
      <td>${s.lost}</td><td>${s.stale}</td><td>${s.invalid}</td><td>${fmt(s.phone.rttP50Ms, 1)} / ${fmt(s.phone.rttP95Ms, 1)} ms</td>
      <td>${fmt(s.phone.sensorHz, 0, " Hz")}</td><td>${fmt(s.hostProcessingP95Us, 0, " µs")}</td><td>${s.neutralResets}</td></tr>`).join("")
      : `<tr><td colspan="11" class="muted">No controllers connected.</td></tr>`;
    $("#diagGlobal").textContent = `Malformed datagrams ${snap.malformedDatagrams} · unknown session ${snap.unknownSessionDatagrams} · uptime ${Math.round(snap.uptimeS / 60)} min`;
  }
}

document.addEventListener("click", async (e) => {
  const el = e.target.closest("[data-act]");
  if (!el) return;
  const act = el.dataset.act;
  if (act === "pair") show("pair");
  if (act === "rumble") { await api.invoke("test_rumble", { slot: Number(el.dataset.slot) }); toast("Rumble sent"); }
  if (act === "kick") await api.invoke("disconnect", { slot: Number(el.dataset.slot) });
  if (act === "install-driver" && !installing) {
    installing = true; render();
    try {
      const r = await api.invoke("install_driver");
      toast(r && r.restartNeeded ? "Driver installed — restart Windows to finish" : "Driver installed — games now see an Xbox controller");
    } catch (err) { toast(String(err)); }
    installing = false; snap = await api.invoke("snapshot"); render();
  }
  if (act === "request-access") {
    const ok = await api.invoke("request_input_access");
    toast(ok ? "Keyboard control allowed" : "Turn on Controller-9 in System Settings → Privacy & Security → Accessibility");
    snap = await api.invoke("snapshot"); render();
  }
  if (act === "unpair" && confirm("Forget this phone? It will need to pair again.")) { await api.invoke("unpair", { key: el.dataset.key }); toast("Device forgotten"); }
});

// ---------------------------------------------------------------- pairing
async function startPairing() {
  pairing = await api.invoke("open_pairing");
  $("#qr").innerHTML = pairing.qrSvg; // SVG generated by the host from its own URI (no remote input)
  tickPair();
}
function tickPair() {
  if (!pairing) return;
  const left = snap ? snap.pairingSecondsLeft : pairing.secondsLeft;
  $("#pairTimer").textContent = left > 0 ? `Valid for ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}` : "Expired — make a new code";
}
$("#renewQr").addEventListener("click", startPairing);
$("#closePair").addEventListener("click", async () => { await api.invoke("close_pairing"); pairing = null; $("#qr").innerHTML = ""; show("overview"); });

function openRequest(r) {
  pending = r;
  $("#pmName").textContent = r.name;
  $("#pmCode").textContent = r.code;
  $("#pairModal").hidden = false;
  $("#pmAllow").focus();
}
async function decide(allow) {
  if (!pending) return;
  await api.invoke("answer", { id: pending.id, allow });
  pending = null;
  $("#pairModal").hidden = true;
}
$("#pmAllow").addEventListener("click", () => decide(true));
$("#pmDeny").addEventListener("click", () => decide(false));
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && pending) decide(false); });

api.listen("host-event", (e) => {
  if (e.type === "pairing_request") openRequest(e);
  if (e.type === "pairing_resolved" && pending && pending.id === e.id) { pending = null; $("#pairModal").hidden = true; }
  if (e.type === "paired") toast(`${e.name} paired`);
  if (e.type === "slot_connected") toast(`${e.name} connected as player ${e.slot + 1}`);
  if (e.type === "slot_disconnected") toast(`${e.name} disconnected — holding player ${e.slot + 1} for ${e.graceS}s`);
  if (e.type === "notice") toast(e.message);
});

// ---------------------------------------------------------------- settings
async function loadSettings() {
  const c = await api.invoke("get_config");
  const f = $("#settings");
  for (const [k, v] of Object.entries(c)) {
    const el = f.elements[k];
    if (!el) continue;
    el.type === "checkbox" ? (el.checked = v) : (el.value = v);
  }
  f.dataset.base = JSON.stringify(c);
}
$("#settings").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = e.target;
  const c = JSON.parse(f.dataset.base || "{}");
  c.name = f.elements.name.value;
  for (const k of ["maxSlots", "timeoutMs", "graceS", "tcpPort", "udpPort"]) c[k] = Number(f.elements[k].value);
  c.advertise = f.elements.advertise.checked;
  try { await api.invoke("save_config", { config: c }); $("#settingsMsg").textContent = "Saved. Restart to apply network changes."; }
  catch (err) { $("#settingsMsg").textContent = String(err); }
});

$("#copyDiag").addEventListener("click", async () => {
  const report = { generatedAt: new Date().toISOString(), host: { name: snap.name, os: snap.os, backend: snap.backend }, slots: snap.slots.map(({ state, ...s }) => s), malformed: snap.malformedDatagrams };
  await navigator.clipboard.writeText(JSON.stringify(report, null, 2));
  toast("Diagnostics copied");
});

// ---------------------------------------------------------------- loop
async function poll() {
  if (document.visibilityState === "visible") {
    try { snap = await api.invoke("snapshot"); render(); tickPair(); } catch (err) { console.error(err); }
  }
  setTimeout(poll, view === "overview" ? 100 : 400);
}
poll();

// ---------------------------------------------------------------- browser preview (design review only)
function previewApi() {
  const t0 = performance.now();
  const qr = `<svg viewBox="0 0 29 29" xmlns="http://www.w3.org/2000/svg"><rect width="29" height="29" fill="#F4F5F2"/>${
    Array.from({ length: 29 * 29 }, (_, k) => ((k * 2654435761) >>> 0) % 7 < 3 ? `<rect x="${k % 29}" y="${(k / 29) | 0}" width="1" height="1" fill="#0B0C0D"/>` : "").join("")}</svg>`;
  const cfg = { name: "Studio Mac", tcpPort: 47901, udpPort: 47900, maxSlots: 4, timeoutMs: 250, heartbeatMs: 50, idleHeartbeatMs: 200, graceS: 30, advertise: true, bind: "0.0.0.0" };
  const slot = (i, name, platform, profile, motion, link, health, deviceId) => {
    const t = (performance.now() - t0) / 1000 + i;
    const live = health === "live";
    return { slot: i, link, linkLabel: link === "ble" ? "Bluetooth LE" : "Wi-Fi / LAN", connected: health !== "holding", graceSLeft: health === "holding" ? 24 : null,
      name, platform, profile, connectedS: 300, silentMs: live ? 8 : health === "stale" ? 2300 : null, frames: 36000, rateHz: live ? 118 - i * 6 : null, lost: 12 + i * 9, stale: 2, invalid: 0,
      jitterMs: 0.8 + i * 0.4, interarrivalP50Ms: 8.3, interarrivalP95Ms: 11.9, hostProcessingP95Us: 41, neutralResets: health === "stale" ? 1 : 0, motion,
      phone: { rttP50Ms: link === "ble" ? 62 : 11, rttP95Ms: link === "ble" ? 92 : 24, sensorHz: 200, sendHz: 120, batteryPct: 81 },
      state: live ? { buttons: (Math.sin(t * 3) > 0.6 ? 1 : 0) | (Math.sin(t * 2) > 0.8 ? 1 << 5 : 0), lx: Math.round(Math.sin(t) * 26000), ly: Math.round(Math.cos(t * 0.7) * 9000),
        rx: Math.round(Math.sin(t * 0.4) * 12000), ry: 0, lt: Math.max(0, Math.round(Math.sin(t * 1.3) * 65535)), rt: Math.round((Math.sin(t * 0.9) * 0.5 + 0.5) * 65535) }
        : { buttons: 0, lx: 0, ly: 0, rx: 0, ry: 0, lt: 0, rt: 0 },
      padOk: false, padError: "missing com.apple.developer.hid.virtual.device", deviceId, health };
  };
  return {
    listen: () => {},
    invoke: async (cmd) => ({
      snapshot: { name: cfg.name, id: "0000000000000000", os: "preview", address: "192.168.1.20", tcpPort: 47901, udpPort: 47900, advertising: true,
        backend: { name: "macOS IOHID", kind: "native", available: false, maxPads: 4, detail: "Design preview — virtual controller unavailable (missing entitlement)", remedy: null },
        maxSlots: 4, slots: [slot(0, "realme 12 5G", "android", "Xbox-style", false, "lan", "live", "3f9a1c2b7d004e11"), slot(1, "Pixel 9", "android", "Racing", true, "ble", "stale", "a07c55e2910bd3f4"), slot(2, "iPhone 16", "ios", "DualSense-style", false, "ble", "holding", "5b1e0c9d8a7f6e21")],
        addresses: [{ ip: "192.168.1.20", iface: "en0", label: "Wi-Fi / LAN" }, { ip: "192.168.42.10", iface: "en7", label: "USB network" }],
        links: ["ip", "stream", "ble"], streamPort: 47902, ble: { supported: true, active: true, detail: "Scanning for phones offering Bluetooth", connected: ["phone 6f7a81"] },
        pairingOpen: true, pairingSecondsLeft: 104, uptimeS: 1260, malformedDatagrams: 0, unknownSessionDatagrams: 0, preview: true, version: "0.1.0",
        paired: [{ key: "a", name: "realme 12 5G", platform: "android", pairedAt: "2026-10-17T12:00:00Z", lastSeen: "2026-10-17T13:02:11Z" },
                 { key: "b", name: "Pixel 9", platform: "android", pairedAt: "2026-10-17T12:05:00Z", lastSeen: "2026-10-17T13:02:11Z" }] },
      open_pairing: { uri: "ninectl://pair?v=1&…", qrSvg: qr, secondsLeft: 120 },
      get_config: cfg,
    })[cmd] ?? null,
  };
}

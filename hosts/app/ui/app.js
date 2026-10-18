// Controller-9 companion UI. Talks to the Rust host through Tauri commands; when opened in a
// plain browser (design review) it runs against a clearly labelled in-page preview instead.
"use strict";
const T = window.__TAURI__;
const preview = !T;
const BTN = ["A", "B", "X", "Y", "LB", "RB", "View", "Menu", "LS", "RS", "↑", "↓", "←", "→", "Guide"];
const $ = (s) => document.querySelector(s);
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

function show(v) {
  view = v;
  document.querySelectorAll(".view").forEach((el) => (el.hidden = el.id !== `view-${v}`));
  document.querySelectorAll(".nav").forEach((b) => {
    const on = b.dataset.view === v;
    b.classList.toggle("on", on);
    on ? b.setAttribute("aria-current", "page") : b.removeAttribute("aria-current");
  });
  $("#eyebrow").textContent = { overview: "Overview", pair: "Pair a phone", devices: "Devices", diagnostics: "Diagnostics", settings: "Settings", help: "Setup & help" }[v];
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

// ---------------------------------------------------------------- rendering
function stick(x, y, label) {
  const cx = 38 + (x / 32767) * 26, cy = 38 - (y / 32767) * 26;
  const active = Math.abs(x) > 1500 || Math.abs(y) > 1500;
  return `<svg width="76" height="92" viewBox="0 0 76 92" role="img" aria-label="${label} stick">
    <circle cx="38" cy="38" r="34" fill="none" stroke="rgb(238 240 241 / .14)"/>
    <line x1="38" y1="8" x2="38" y2="68" stroke="rgb(238 240 241 / .06)"/><line x1="8" y1="38" x2="68" y2="38" stroke="rgb(238 240 241 / .06)"/>
    <circle cx="${cx.toFixed(1)}" cy="${cy.toFixed(1)}" r="9" fill="${active ? "#EEF0F1" : "rgb(238 240 241 / .38)"}"/>
    <text x="38" y="88" text-anchor="middle" fill="rgb(238 240 241 / .42)" font-size="10" letter-spacing=".06em">${label}</text></svg>`;
}
function bar(v, label) {
  const w = Math.round((v / 65535) * 100);
  return `<div class="trig"><span class="micro dim">${label}</span><div class="track"><i style="width:${w}%"></i></div></div>`;
}
function buttons(mask) {
  return `<div class="btns">${BTN.slice(0, 14).map((b, i) => `<span class="b ${mask & (1 << i) ? "on" : ""}">${b}</span>`).join("")}</div>`;
}
function bay(s, i, max) {
  if (!s) {
    return `<div class="bay empty"><div><div class="pindex">P${i + 1}</div><div>Waiting for a controller</div>
      ${i === 0 ? `<button class="btn small" data-act="pair" style="margin-top:12px">Pair a phone</button>` : ""}</div></div>`;
  }
  const loss = s.frames + s.lost > 0 ? (100 * s.lost) / (s.frames + s.lost) : null;
  const st = s.state;
  const status = !s.connected ? `Reconnecting · ${s.graceSLeft ?? 0}s` : s.padOk ? `Live · ${s.linkLabel}` : `${s.linkLabel} · no virtual controller`;
  return `<div class="bay ${s.connected ? "" : "grace"}">
    <div class="bay-head">
      <div><div class="pindex">P${s.slot + 1} · ${esc(s.platform)}</div><div class="bay-title">${esc(s.name)}</div>
        <div class="bay-sub">${esc(s.profile || "Default layout")}${s.motion ? " · motion steering" : ""}</div></div>
      <span class="chip ${s.connected && s.padOk ? "ok" : "warn"}">${status}</span>
    </div>
    <div class="viz">${stick(st.lx, st.ly, "LEFT")}
      <div class="mid">${bar(st.lt, "LT")}${bar(st.rt, "RT")}${buttons(st.buttons)}</div>
      ${stick(st.rx, st.ry, "RIGHT")}</div>
    <div class="stats">
      <div class="stat"><b>${fmt(s.rateHz, 0)}</b><span>Hz</span></div>
      <div class="stat"><b>${fmt(s.phone.rttP50Ms, 1)}</b><span>RTT ms</span></div>
      <div class="stat"><b>${fmt(s.jitterMs, 2)}</b><span>Jitter ms</span></div>
      <div class="stat"><b>${fmt(loss, 1)}</b><span>Loss %</span></div>
    </div>
    <div class="bay-actions"><button class="btn small" data-act="rumble" data-slot="${s.slot}">Test rumble</button>
      <button class="btn small ghost danger" data-act="kick" data-slot="${s.slot}">Disconnect</button></div>
  </div>`;
}

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
  chip.textContent = preview ? "Design preview — no host" : snap.preview ? "Preview mode" : b.kind === "native" && b.available ? "Virtual controller ready" : "Virtual controller unavailable";
  const banner = $("#banner");
  if (!preview && (snap.preview || !b.available)) {
    banner.hidden = false;
    banner.innerHTML = snap.preview
      ? `<b>Preview mode.</b><span class="fix">Input is received and shown here, but no controller is created for games.</span>`
      : `<b>${esc(b.detail)}</b>${b.remedy ? `<span class="fix">${esc(b.remedy)}</span>` : ""}`;
  } else banner.hidden = true;
  $("#helpBackend").innerHTML = `<p><b>${esc(b.name)}</b> — ${esc(b.detail)}</p>${b.remedy ? `<p>${esc(b.remedy)}</p>` : ""}
    <p>Up to ${snap.maxSlots} players. Each phone becomes its own controller.</p>`;

  if (view === "overview") {
    const bySlot = Object.fromEntries(snap.slots.map((s) => [s.slot, s]));
    // Entry animation only when the set of occupants changes, not on every live refresh.
    const key = snap.slots.map((s) => `${s.slot}:${s.name}:${s.connected}`).join("|") + `/${snap.maxSlots}`;
    const bays = $("#bays");
    bays.classList.toggle("settled", bays.dataset.key === key);
    bays.dataset.key = key;
    bays.innerHTML = Array.from({ length: snap.maxSlots }, (_, i) => bay(bySlot[i], i, snap.maxSlots)).join("");
  }
  if (view === "devices") {
    $("#devices").innerHTML = snap.paired.map((d) => `<tr><td>${esc(d.name)}</td><td class="muted">${esc(d.platform)}</td>
      <td class="mono">${esc(d.pairedAt.replace("T", " ").replace("Z", ""))}</td><td class="mono">${esc((d.lastSeen || "—").replace("T", " ").replace("Z", ""))}</td>
      <td><button class="btn small ghost danger" data-act="unpair" data-key="${esc(d.key)}">Forget</button></td></tr>`).join("");
    $("#devicesEmpty").hidden = snap.paired.length > 0;
  }
  if (view === "diagnostics") {
    $("#diag").innerHTML = snap.slots.length ? snap.slots.map((s) => `<tr><td>P${s.slot + 1} ${esc(s.name)}<br><span class="dim">${esc(s.linkLabel)}</span></td>
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
  const qr = `<svg viewBox="0 0 29 29" xmlns="http://www.w3.org/2000/svg"><rect width="29" height="29" fill="#F2EDE4"/>${
    Array.from({ length: 29 * 29 }, (_, k) => ((k * 2654435761) >>> 0) % 7 < 3 ? `<rect x="${k % 29}" y="${(k / 29) | 0}" width="1" height="1" fill="#0C0B09"/>` : "").join("")}</svg>`;
  const cfg = { name: "Studio PC", tcpPort: 47901, udpPort: 47900, maxSlots: 4, timeoutMs: 250, heartbeatMs: 50, idleHeartbeatMs: 200, graceS: 30, advertise: true, bind: "0.0.0.0" };
  const slot = (i, name, platform, profile, motion) => {
    const t = (performance.now() - t0) / 1000 + i;
    return { slot: i, link: i ? "ble" : "lan", linkLabel: i ? "Bluetooth LE" : "Wi-Fi / LAN", connected: true, graceSLeft: null, name, platform, profile, connectedS: 300, frames: 36000, rateHz: 120 - i * 6, lost: 12 + i * 9, stale: 2, invalid: 0,
      jitterMs: 0.8 + i * 0.4, interarrivalP50Ms: 8.3, interarrivalP95Ms: 11.9, hostProcessingP95Us: 41, neutralResets: 0, motion,
      phone: { rttP50Ms: 6.2 + i * 1.7, rttP95Ms: 13.8 + i * 2, sensorHz: 200, sendHz: 120, batteryPct: 81 },
      state: { buttons: (Math.sin(t * 3) > 0.6 ? 1 : 0) | (Math.sin(t * 2) > 0.8 ? 1 << 5 : 0), lx: Math.round(Math.sin(t) * 26000), ly: Math.round(Math.cos(t * 0.7) * 9000),
        rx: Math.round(Math.sin(t * 0.4) * 12000), ry: 0, lt: Math.max(0, Math.round(Math.sin(t * 1.3) * 65535)), rt: Math.round((Math.sin(t * 0.9) * 0.5 + 0.5) * 65535) },
      padOk: true, padError: null };
  };
  return {
    listen: () => {},
    invoke: async (cmd) => ({
      snapshot: { name: cfg.name, id: "0000000000000000", os: "preview", address: "192.168.1.20", tcpPort: 47901, udpPort: 47900, advertising: true,
        backend: { name: "preview", kind: "native", available: true, maxPads: 4, detail: "Design preview — not connected to a host", remedy: null },
        maxSlots: 4, slots: [slot(0, "Pixel 9", "android", "Racing", true), slot(1, "iPhone 16", "ios", "Classic", false)],
        addresses: [{ ip: "192.168.1.20", iface: "en0", label: "Wi-Fi / LAN" }, { ip: "192.168.42.10", iface: "en7", label: "USB network" }],
        links: ["ip", "stream", "ble"], streamPort: 47902, ble: { supported: true, active: true, detail: "Scanning for phones offering Bluetooth", connected: ["iPhone 16"] },
        pairingOpen: true, pairingSecondsLeft: 104, uptimeS: 1260, malformedDatagrams: 0, unknownSessionDatagrams: 0, preview: true, version: "0.1.0",
        paired: [{ key: "a", name: "Pixel 9", platform: "android", pairedAt: "2026-10-17T12:00:00Z", lastSeen: "2026-10-17T13:02:11Z" },
                 { key: "b", name: "iPhone 16", platform: "ios", pairedAt: "2026-10-17T12:05:00Z", lastSeen: "2026-10-17T13:02:11Z" }] },
      open_pairing: { uri: "ninectl://pair?v=1&…", qrSvg: qr, secondsLeft: 120 },
      get_config: cfg,
    })[cmd] ?? null,
  };
}

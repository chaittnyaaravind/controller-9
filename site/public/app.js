// Controller-9 site — one WebGL scene, directed by scroll. Every effect is a function of scroll
// progress, so scrolling back plays it in reverse.
// Act 0 (load): the Pocket Pad mark assembles from glowing edges into prismatic glass.
// Act 1 Fusion: a game controller and a phone fly in and collide — flash, shockwave, sparks, shake —
//   the controller is absorbed and the phone turns sideways with the controller on its screen.
// Act 2 Launch: the phone tips upright, ignites (exhaust plume, speed lines), blasts off, drops back.
// Act 3 Inside (exploded view + callouts). Act 4 Tilt to steer. Act 5 Five layouts.
import * as THREE from "three";
import { RoundedBoxGeometry } from "three/addons/geometries/RoundedBoxGeometry.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { UnrealBloomPass } from "three/addons/postprocessing/UnrealBloomPass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";

const gsap = window.gsap, ScrollTrigger = window.ScrollTrigger;
const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
const canvas = document.getElementById("gl");
const LAYOUTS = ["xbox", "dualsense", "dualshock4", "switchpro", "8bitdo-pro2", "gamecube", "arcade"];
const NAMES = ["Xbox-style", "DualSense-style", "DualShock 4-style", "Switch Pro-style", "8BitDo Pro 2-style", "GameCube-style", "Arcade stick"];

// ------------------------------------------------------------------ chrome (works without WebGL)
const nav = document.getElementById("nav");
const onScroll = () => nav.classList.toggle("scrolled", scrollY > 8);
addEventListener("scroll", onScroll, { passive: true }); onScroll();
const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { rootMargin: "0px 0px -12% 0px" });
document.querySelectorAll(".reveal").forEach((el, i) => { el.style.transitionDelay = (i % 4) * 60 + "ms"; io.observe(el); });
const ua = navigator.userAgent;
const plat = /Android/i.test(ua) ? "android" : /iPhone|iPad/i.test(ua) ? "iphone" : /Mac/i.test(ua) ? "mac" : /Win/i.test(ua) ? "windows" : /Linux/i.test(ua) ? "linux" : "";
const mine = plat && document.querySelector(`[data-platform="${plat}"]`); if (mine) mine.classList.add("yours");

function webglOK() { try { const c = document.createElement("canvas"); return !!c.getContext("webgl2"); } catch { return false; } }
if (reduced || !webglOK() || !gsap) { document.documentElement.classList.add("static", "ready"); } else { start(); }

function start() {
  document.documentElement.classList.add("gl");
  gsap.registerPlugin(ScrollTrigger);
  if (window.Lenis) {
    const lenis = new window.Lenis({ lerp: 0.09 });
    lenis.on("scroll", ScrollTrigger.update);
    gsap.ticker.add(t => lenis.raf(t * 1000)); gsap.ticker.lagSmoothing(0);
    document.querySelectorAll('a[href^="#"]').forEach(a => a.addEventListener("click", e => { const t = document.querySelector(a.getAttribute("href")); if (t) { e.preventDefault(); lenis.scrollTo(t, { offset: -64 }); } }));
  }
  const mobile = () => innerWidth < 760;
  const lite = mobile() || (navigator.hardwareConcurrency || 8) <= 4;

  // ---------------------------------------------------------------- renderer, scene, post
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(devicePixelRatio, lite ? 1.5 : 2));
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.0;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  // Through the composer (half-float target → OutputPass) the clear colour is encoded twice; this value was
  // measured to come out as the page background #0B0C0D (12,13,14 vs 11,12,13).
  renderer.setClearColor(new THREE.Color().setRGB(3.25 / 255, 3.45 / 255, 3.65 / 255, THREE.SRGBColorSpace), 1);
  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.75;
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 80); camera.position.set(0, 0, 9);
  const key = new THREE.DirectionalLight(0xffffff, 1.6); key.position.set(3, 4, 6); scene.add(key);
  const accentLight = new THREE.PointLight(0xd9b979, 2, 7, 2); scene.add(accentLight);

  const composer = new EffectComposer(renderer);
  composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(512, 512), 0.22, 0.18, 0.985);
  composer.addPass(bloom);
  composer.addPass(new OutputPass());

  const ACCENT = new THREE.Color("#D9B979");
  const glass = lite
    ? new THREE.MeshPhysicalMaterial({ color: "#C9CDD1", metalness: 0.85, roughness: 0.24, clearcoat: 1, transparent: true })
    : new THREE.MeshPhysicalMaterial({ color: "#ffffff", metalness: 0, roughness: 0.06, transmission: 1, thickness: 2.2, ior: 1.5,
        dispersion: 4, iridescence: 1, iridescenceIOR: 1.6, iridescenceThicknessRange: [180, 620], clearcoat: 1,
        attenuationColor: new THREE.Color("#DDEFC0"), attenuationDistance: 6, transparent: true });
  const lime = new THREE.MeshPhysicalMaterial({ color: ACCENT, emissive: ACCENT, emissiveIntensity: 0.32, roughness: 0.35, clearcoat: 1, toneMapped: false, transparent: true });
  const graphite = new THREE.MeshPhysicalMaterial({ color: "#15181B", metalness: 0.6, roughness: 0.36, clearcoat: 1, clearcoatRoughness: 0.1, transparent: true });

  function radial(inner, outer, size = 256) {
    const c = document.createElement("canvas"); c.width = c.height = size; const g = c.getContext("2d");
    const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2); gr.addColorStop(0, inner); gr.addColorStop(1, outer);
    g.fillStyle = gr; g.fillRect(0, 0, size, size); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
  }
  const softDot = radial("rgba(255,255,255,1)", "rgba(255,255,255,0)", 64);
  // Deterministic pseudo-random (effects must replay identically when scrolling back).
  let seed = 9; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;

  // ---------------------------------------------------------------- dust: depth particles
  const DUST = lite ? 260 : 700;
  const dpos = new Float32Array(DUST * 3);
  for (let i = 0; i < DUST; i++) { dpos[i * 3] = (rnd() - 0.5) * 18; dpos[i * 3 + 1] = (rnd() - 0.5) * 11; dpos[i * 3 + 2] = -rnd() * 10 + 2; }
  const dgeo = new THREE.BufferGeometry(); dgeo.setAttribute("position", new THREE.BufferAttribute(dpos, 3));
  const dust = new THREE.Points(dgeo, new THREE.PointsMaterial({ size: 0.035, map: softDot, transparent: true, opacity: 0.5, depthWrite: false }));
  scene.add(dust);

  // ---------------------------------------------------------------- the mark (brand/build.py geometry)
  const mark = new THREE.Group();
  function roundedRect(w, h, r) {
    const s = new THREE.Shape(); const x = -w / 2, y = -h / 2;
    s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r); s.lineTo(x + w, y + h - r);
    s.quadraticCurveTo(x + w, y + h, x + w - r, y + h); s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
    s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y); return s;
  }
  const outer = roundedRect(42.2, 25.2, 8.6); outer.holes.push(roundedRect(35.8, 18.8, 5.4));
  const ringGeo = new THREE.ExtrudeGeometry(outer, { depth: 3.4, bevelEnabled: true, bevelThickness: 1, bevelSize: 0.8, bevelSegments: 8, curveSegments: 40 });
  ringGeo.translate(0, 0, -1.7);
  const ring = new THREE.Mesh(ringGeo, glass); mark.add(ring);
  const barV = new RoundedBoxGeometry(3.2, 9.6, 3.8, 5, 1.45), barH = new RoundedBoxGeometry(9.6, 3.2, 3.8, 5, 1.45);
  const dV = new THREE.Mesh(barV, glass), dH = new THREE.Mesh(barH, glass); dV.position.x = dH.position.x = -9; mark.add(dV, dH);
  const btn = new THREE.Mesh(new THREE.CylinderGeometry(4.2, 4.2, 4, 64), lime); btn.rotation.x = Math.PI / 2; btn.position.x = 8.5; mark.add(btn);
  const edgeMat = new THREE.LineBasicMaterial({ color: 0xeef0f1, transparent: true, opacity: 0, toneMapped: false });
  const edges = new THREE.Group();
  [[ringGeo, 18, 0], [barV, 1, -9], [barH, 1, -9]].forEach(([g, ang, x]) => { const l = new THREE.LineSegments(new THREE.EdgesGeometry(g, ang), edgeMat); l.position.x = x; edges.add(l); });
  mark.add(edges);
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(30, 30), new THREE.MeshBasicMaterial({ map: radial("rgba(198,244,50,.16)", "rgba(198,244,50,0)"), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false }));
  glow.position.set(8.5, 0, -4); mark.add(glow);
  scene.add(mark);
  const markSolids = [ring, dV, dH, btn];

  // ---------------------------------------------------------------- the game controller (original shape)
  const pad = new THREE.Group();
  const padShape = new THREE.Shape();
  padShape.moveTo(0, 4.4);
  padShape.bezierCurveTo(3, 4.6, 5.8, 4.6, 6.8, 3.6);
  padShape.bezierCurveTo(8.2, 2.2, 8.6, -3.6, 7.6, -4.9);
  padShape.bezierCurveTo(6.8, -5.9, 5.0, -5.6, 4.2, -4.4);
  padShape.bezierCurveTo(3.4, -3.2, 2.6, -2.2, 0, -2.2);
  padShape.bezierCurveTo(-2.6, -2.2, -3.4, -3.2, -4.2, -4.4);
  padShape.bezierCurveTo(-5.0, -5.6, -6.8, -5.9, -7.6, -4.9);
  padShape.bezierCurveTo(-8.6, -3.6, -8.2, 2.2, -6.8, 3.6);
  padShape.bezierCurveTo(-5.8, 4.6, -3, 4.6, 0, 4.4);
  const padBodyGeo = new THREE.ExtrudeGeometry(padShape, { depth: 2.2, bevelEnabled: true, bevelThickness: 0.7, bevelSize: 0.6, bevelSegments: 8, curveSegments: 48 });
  padBodyGeo.translate(0, 0, -1.1);
  const padMat = new THREE.MeshPhysicalMaterial({ color: "#16191C", metalness: 0.1, roughness: 0.62, clearcoat: 0.35, clearcoatRoughness: 0.45, transparent: true });
  const padLime = new THREE.MeshPhysicalMaterial({ color: ACCENT, emissive: ACCENT, emissiveIntensity: 0.5, roughness: 0.35, toneMapped: false, transparent: true });
  const rubber = new THREE.MeshPhysicalMaterial({ color: "#2B3035", metalness: 0.1, roughness: 0.75, transparent: true });
  const keyMat = new THREE.MeshPhysicalMaterial({ color: "#3A4046", metalness: 0.2, roughness: 0.4, clearcoat: 1, transparent: true });
  pad.add(new THREE.Mesh(padBodyGeo, padMat));
  const top = 1.8;
  const stick = (x, y) => { const g = new THREE.Group();
    const base = new THREE.Mesh(new THREE.CylinderGeometry(1.25, 1.25, 0.25, 40), rubber); base.rotation.x = Math.PI / 2; g.add(base);
    const cap = new THREE.Mesh(new THREE.CylinderGeometry(0.95, 1.05, 0.7, 40), rubber); cap.rotation.x = Math.PI / 2; cap.position.z = 0.45; g.add(cap);
    g.position.set(x, y, top); pad.add(g); };
  stick(-4.4, 1.3); stick(2.0, -0.7);
  const dpadV = new THREE.Mesh(new RoundedBoxGeometry(0.75, 2.2, 0.5, 3, 0.15), keyMat), dpadH = new THREE.Mesh(new RoundedBoxGeometry(2.2, 0.75, 0.5, 3, 0.15), keyMat);
  dpadV.position.set(-2.0, -0.7, top); dpadH.position.set(-2.0, -0.7, top); pad.add(dpadV, dpadH);
  [[0, -1, padLime], [1, 0, keyMat], [-1, 0, keyMat], [0, 1, keyMat]].forEach(([dx, dy, mat]) => {
    const b = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.42, 0.45, 32), mat); b.rotation.x = Math.PI / 2; b.position.set(4.6 + dx * 0.95, 1.3 + dy * 0.95, top); pad.add(b); });
  [-4.9, 4.9].forEach(x => { const bp = new THREE.Mesh(new RoundedBoxGeometry(3.2, 0.7, 1.6, 3, 0.3), keyMat); bp.position.set(x, 4.05, 0); pad.add(bp); });
  const padMats = [padMat, rubber, keyMat, padLime];
  pad.visible = false; scene.add(pad);

  // ---------------------------------------------------------------- the phone, in layers
  const PW = 3.9, PH = 1.86, PD = 0.16;
  const phone = new THREE.Group(); scene.add(phone);
  const body = new THREE.Mesh(new RoundedBoxGeometry(PW, PH, PD, 8, 0.2), graphite); phone.add(body);
  const boardCanvas = document.createElement("canvas"); boardCanvas.width = 1024; boardCanvas.height = 488;
  (() => {
    const g = boardCanvas.getContext("2d"); g.fillStyle = "#0E1311"; g.fillRect(0, 0, 1024, 488);
    g.strokeStyle = "rgba(198,244,50,.35)"; g.lineWidth = 2;
    for (let i = 0; i < 46; i++) {
      g.beginPath(); let x = 40 + rnd() * 940, y = 30 + rnd() * 420; g.moveTo(x, y);
      for (let k = 0; k < 3; k++) { if (k % 2) y += (rnd() - 0.5) * 160; else x += (rnd() - 0.5) * 260; g.lineTo(Math.max(20, Math.min(1004, x)), Math.max(20, Math.min(468, y))); }
      g.stroke();
    }
    const chip = (x, y, w, h, label) => { g.fillStyle = "#1A1F1C"; g.fillRect(x, y, w, h); g.strokeStyle = "rgba(238,240,241,.25)"; g.strokeRect(x, y, w, h);
      g.fillStyle = "rgba(238,240,241,.6)"; g.font = "500 20px Geist Mono, monospace"; g.fillText(label, x + 14, y + 32); };
    chip(110, 150, 220, 150, "IMU · 100 Hz"); chip(420, 110, 200, 120, "Wi-Fi"); chip(420, 270, 200, 110, "Bluetooth"); chip(720, 170, 190, 160, "SoC");
  })();
  const boardTex = new THREE.CanvasTexture(boardCanvas); boardTex.colorSpace = THREE.SRGBColorSpace;
  const board = new THREE.Mesh(new THREE.PlaneGeometry(PW - 0.3, PH - 0.3), new THREE.MeshBasicMaterial({ map: boardTex, transparent: true, toneMapped: false, opacity: 0 }));
  board.position.z = PD / 2 + 0.001; phone.add(board);
  const screenCanvas = document.createElement("canvas"); screenCanvas.width = 2048; screenCanvas.height = 976;
  const sctx = screenCanvas.getContext("2d");
  const screenTex = new THREE.CanvasTexture(screenCanvas); screenTex.colorSpace = THREE.SRGBColorSpace; screenTex.anisotropy = 8;
  const screenMat = new THREE.MeshBasicMaterial({ map: screenTex, toneMapped: false, transparent: true });
  const screen = new THREE.Mesh(new THREE.PlaneGeometry(PW - 0.16, PH - 0.16), screenMat); screen.position.z = PD / 2 + 0.002; phone.add(screen);
  const coverMat = new THREE.MeshPhysicalMaterial({ color: "#BFD3DA", roughness: 0.03, metalness: 0, transparent: true, opacity: 0.07, clearcoat: 1, depthWrite: false });
  const cover = new THREE.Mesh(new RoundedBoxGeometry(PW - 0.06, PH - 0.06, 0.02, 4, 0.01), coverMat); cover.position.z = PD / 2 + 0.012; phone.add(cover);
  const island = new THREE.Mesh(new RoundedBoxGeometry(0.09, 0.42, 0.01, 2, 0.04), new THREE.MeshBasicMaterial({ color: "#000", transparent: true }));
  island.position.set(-PW / 2 + 0.2, 0, PD / 2 + 0.006); phone.add(island);
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(6, 1.6), new THREE.MeshBasicMaterial({ map: radial("rgba(0,0,0,.6)", "rgba(0,0,0,0)"), transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; scene.add(shadow);

  const pads = {};
  const loadPad = id => new Promise(res => { const img = new Image(); img.onload = () => { pads[id] = img; res(); }; img.onerror = res; img.src = `/assets/pad-${id}.svg`; });
  let shown = -1;
  function paintScreen(i) {
    sctx.fillStyle = "#0B0C0D"; sctx.fillRect(0, 0, 2048, 976);
    const img = pads[LAYOUTS[i]]; if (img) sctx.drawImage(img, 120, 70, 1808, 904);
    sctx.fillStyle = "rgba(17,19,21,.92)"; sctx.beginPath(); sctx.roundRect(784, 26, 480, 52, 26); sctx.fill();
    sctx.fillStyle = "#D9B979"; sctx.beginPath(); sctx.arc(820, 52, 8, 0, Math.PI * 2); sctx.fill();
    sctx.fillStyle = "rgba(238,240,241,.85)"; sctx.font = "500 22px Geist, system-ui"; sctx.fillText("P1 · YOUR COMPUTER · WI-FI", 842, 60);
    screenTex.needsUpdate = true;
  }
  Promise.all(LAYOUTS.map(loadPad)).then(() => { paintScreen(0); shown = 0; });

  // ---------------------------------------------------------------- impact effects: shockwave, sparks
  const shock = new THREE.Mesh(new THREE.RingGeometry(0.92, 1, 128), new THREE.MeshBasicMaterial({ color: ACCENT, transparent: true, opacity: 0, side: THREE.DoubleSide, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  const shock2 = new THREE.Mesh(new THREE.RingGeometry(0.97, 1, 128), new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0, side: THREE.DoubleSide, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(shock, shock2);
  const flash = new THREE.Mesh(new THREE.PlaneGeometry(14, 14), new THREE.MeshBasicMaterial({ map: radial("rgba(220,255,140,1)", "rgba(198,244,50,0)"), transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(flash);
  const SPARKS = lite ? 160 : 360;
  const sDir = new Float32Array(SPARKS * 3), sSpeed = new Float32Array(SPARKS);
  for (let i = 0; i < SPARKS; i++) {
    const th = rnd() * Math.PI * 2, ph = Math.acos(2 * rnd() - 1);
    sDir[i * 3] = Math.sin(ph) * Math.cos(th); sDir[i * 3 + 1] = Math.sin(ph) * Math.sin(th); sDir[i * 3 + 2] = Math.cos(ph) * 0.6;
    sSpeed[i] = 1.2 + rnd() * 3.2;
  }
  const sPos = new Float32Array(SPARKS * 3), sCol = new Float32Array(SPARKS * 3);
  for (let i = 0; i < SPARKS; i++) { const w = rnd() < 0.35; sCol[i * 3] = w ? 1 : 0.78; sCol[i * 3 + 1] = w ? 1 : 0.96; sCol[i * 3 + 2] = w ? 1 : 0.2; }
  const sGeo = new THREE.BufferGeometry(); sGeo.setAttribute("position", new THREE.BufferAttribute(sPos, 3)); sGeo.setAttribute("color", new THREE.BufferAttribute(sCol, 3));
  const sparks = new THREE.Points(sGeo, new THREE.PointsMaterial({ size: 0.09, map: softDot, vertexColors: true, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(sparks);

  // ---------------------------------------------------------------- launch effects: exhaust plume, speed lines
  const PLUME = lite ? 220 : 520;
  const pPhase = new Float32Array(PLUME), pSpread = new Float32Array(PLUME * 2);
  for (let i = 0; i < PLUME; i++) { pPhase[i] = rnd(); pSpread[i * 2] = (rnd() - 0.5); pSpread[i * 2 + 1] = (rnd() - 0.5); }
  const plPos = new Float32Array(PLUME * 3), plCol = new Float32Array(PLUME * 3);
  const plGeo = new THREE.BufferGeometry(); plGeo.setAttribute("position", new THREE.BufferAttribute(plPos, 3)); plGeo.setAttribute("color", new THREE.BufferAttribute(plCol, 3));
  const plume = new THREE.Points(plGeo, new THREE.PointsMaterial({ size: 0.26, map: softDot, vertexColors: true, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(plume);
  // Flame core: a hot white-lime glow at the nozzle, flickering.
  const flame = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: radial("rgba(255,255,235,1)", "rgba(198,244,50,0)"), transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(flame);
  const LINES = lite ? 40 : 90;
  const lnBase = new Float32Array(LINES * 3), lnPos = new Float32Array(LINES * 6);
  for (let i = 0; i < LINES; i++) { lnBase[i * 3] = (rnd() - 0.5) * 16; lnBase[i * 3 + 1] = rnd() * 14; lnBase[i * 3 + 2] = -1 - rnd() * 6; }
  const lnGeo = new THREE.BufferGeometry(); lnGeo.setAttribute("position", new THREE.BufferAttribute(lnPos, 3));
  const speedLines = new THREE.LineSegments(lnGeo, new THREE.LineBasicMaterial({ color: 0xeef0f1, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false, toneMapped: false }));
  scene.add(speedLines);

  // ---------------------------------------------------------------- scroll → state
  const state = { p: 0, hero: 0 };
  ScrollTrigger.create({ trigger: "#story", start: "top bottom", end: "bottom bottom", scrub: true, onUpdate: s => { state.p = s.progress; } });
  ScrollTrigger.create({ trigger: ".hero", start: "top top", end: "bottom top", scrub: true, onUpdate: s => { state.hero = s.progress; } });
  const panels = [...document.querySelectorAll(".panel")];
  const callouts = [...document.querySelectorAll(".callout")];
  const layoutName = document.getElementById("layout-name");
  const giant = document.getElementById("giant");
  const fade = (p, a, b, c, d) => Math.max(0, Math.min(1, (p - a) / (b - a), (d - p) / (d - c)));
  const smooth = t => t * t * (3 - 2 * t);
  const clamp01 = t => Math.min(1, Math.max(0, t));
  const lerp = (a, b, t) => a + (b - a) * t;
  const pointer = { x: 0, y: 0, tx: 0, ty: 0 };
  addEventListener("pointermove", e => { if (e.pointerType === "mouse") { pointer.tx = e.clientX / innerWidth - 0.5; pointer.ty = e.clientY / innerHeight - 0.5; } }, { passive: true });

  function resize() {
    const w = innerWidth, h = innerHeight;
    renderer.setSize(w, h, false); composer.setSize(w, h); bloom.setSize(w / 2, h / 2);
    camera.aspect = w / h; camera.fov = w < 760 ? 46 : 30; camera.updateProjectionMatrix();
  }
  addEventListener("resize", resize); resize();

  const cur = { mx: 0, my: 0, ms: 1, mo: 1, px: 5, py: 0, pr: Math.PI / 2, pt: 0, ps: 1, po: 0, ex: 0, gx: -5, gs: 1 };
  let visible = true;
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; }).observe(document.getElementById("gl-zone"));
  const born = performance.now();
  const v3 = new THREE.Vector3(), nozzle = new THREE.Vector3(), up = new THREE.Vector3();
  const LAYERS = [cover, screen, board, body];
  const ANCHORS = [[0.3, 0.42], [0.42, 0.12], [0.36, -0.2], [0.44, -0.44]];
  const leaders = document.getElementById("leaders");
  const leaderPaths = leaders ? [...leaders.querySelectorAll("path")] : [], leaderDots = leaders ? [...leaders.querySelectorAll("circle")] : [];

  const X0Light = () => (mobile() ? 0 : 1.25);
  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible || document.hidden) return;
    const t = (now - born) / 1000, p = state.p, mob = mobile();
    pointer.x += (pointer.tx - pointer.x) * 0.06; pointer.y += (pointer.ty - pointer.y) * 0.06;

    // ---- timeline (story progress p in 0..1)
    const build = smooth(clamp01((t - 0.2) / 1.4)), solid = smooth(clamp01((t - 1.2) / 1.0));
    const m = smooth(clamp01(p / 0.05));                      // mark leaves
    const approach = smooth(clamp01((p - 0.03) / 0.075));      // controller + phone fly in
    const impact = clamp01((p - 0.11) / 0.07);                // effects window after the hit
    const fuse = smooth(clamp01((p - 0.105) / 0.025));          // controller absorbed, phone turns, screen on
    const upright = smooth(clamp01((p - 0.19) / 0.03));       // tips up like a rocket
    const ignite = clamp01((p - 0.2) / 0.02);                 // engine lights
    const la = clamp01((p - 0.22) / 0.09);                    // launch climb
    const desc = smooth(clamp01((p - 0.32) / 0.05));          // drops back into frame
    const x = fade(p, 0.38, 0.44, 0.54, 0.6), s = clamp01((p - 0.61) / 0.12), l = clamp01((p - 0.76) / 0.2), o = smooth(clamp01((p - 0.97) / 0.03));
    const X0 = mob ? 0 : 1.25;                                // where the action happens (text lives on the left)

    // Phone path through the acts.
    let py = mob ? 0.75 : 0, pr;
    if (p < 0.19) pr = (1 - fuse) * Math.PI / 2;
    else if (p < 0.32) { pr = upright * Math.PI / 2; py += la * la * 8.5 + Math.sin(t * 40) * 0.01 * ignite * (1 - la); }
    else { pr = (1 - desc) * Math.PI / 2; py += (1 - desc) * 7; }
    const tgt = {
      mx: mob ? 0 : 1.55, my: (mob ? 1.0 : 0.05) + state.hero * 0.3 + m * 1.8, ms: 1 - m * 0.25, mo: 1 - m,
      px: lerp(X0 + 4.5, X0, approach), py: py - o * 0.6, pr, pt: Math.sin(s * Math.PI * 2) * 0.22,
      ps: (mob ? 0.6 : 0.84) * (1 - o * 0.15), po: approach * (1 - o), ex: x,
      gx: lerp(X0 - 5.5, X0, approach), gs: 1 - fuse,
    };
    const k = p > 0.1 && p < 0.38 ? 0.22 : 0.085;             // action acts track scroll tightly
    for (const kk in tgt) cur[kk] += (tgt[kk] - cur[kk]) * k;

    // ---- mark
    mark.position.set(cur.mx + Math.sin(t * 0.6) * 0.04, cur.my + Math.sin(t * 0.8) * 0.06, 0);
    mark.rotation.set(-0.18 - pointer.y * 0.35 + Math.sin(t * 0.5) * 0.05, -0.32 + pointer.x * 0.6 + Math.sin(t * 0.35) * 0.18, 0.02);
    mark.scale.setScalar(1 / 22 * (mob ? 1.35 : 1.75) * cur.ms);
    mark.visible = cur.mo > 0.01;
    const sop = solid * cur.mo;
    edgeMat.opacity = build * (1 - solid * 0.85) * cur.mo;
    markSolids.forEach(o2 => { o2.material.opacity = sop; });
    glow.material.opacity = sop * (0.75 + Math.sin(t * 2.2) * 0.25);
    btn.position.z = 0.4 + Math.sin(t * 1.4) * 0.25;
    mark.updateMatrixWorld();
    v3.set(8.5, 0, 6).applyMatrix4(mark.matrixWorld); accentLight.position.copy(v3);
    if (m > 0.99) accentLight.position.set(X0Light(), -3, 3);
    if (giant) giant.style.opacity = (0.035 * (1 - m)).toFixed(3);

    // ---- the controller: flies in spinning, shrinks into the phone on impact
    pad.visible = approach > 0.01 && cur.gs > 0.02;
    const gScale = (mob ? 0.1 : 0.14) * cur.gs;
    pad.position.set(cur.gx, (mob ? 0.75 : 0) + Math.sin(t * 1.3) * 0.05 * (1 - approach), 0.3);
    pad.rotation.set(-0.35 + (1 - approach) * 0.6, 0.5 * (1 - approach) - 0.25, (1 - approach) * -0.8);
    pad.scale.setScalar(gScale);
    padMats.forEach(mm => { mm.opacity = clamp01(approach * 3) * cur.gs; });

    // ---- the phone
    phone.visible = cur.po > 0.01;
    const ex = cur.ex, pull = mob ? 0.55 : 0.75;
    phone.position.set(cur.px - ex * 0.9, cur.py + ex * 0.05, 0);
    const spinIn = (1 - approach) * 0.9;
    phone.rotation.set(-0.3 + pointer.y * 0.1 - ex * 0.25, -0.36 * (mob ? 0 : 1) + pointer.x * 0.15 + ex * 0.55 + spinIn, cur.pr + cur.pt);
    phone.scale.setScalar(cur.ps * (1 - ex * 0.16));
    board.position.z = PD / 2 + 0.001 + ex * pull * 0.5;
    screen.position.z = PD / 2 + 0.002 + ex * pull * 1.0;
    cover.position.z = PD / 2 + 0.012 + ex * pull * 1.5;
    graphite.opacity = cur.po; screenMat.opacity = cur.po * fuse; board.material.opacity = cur.po * clamp01(ex * 3); island.material.opacity = cur.po * (1 - ex);
    coverMat.opacity = 0.07 * cur.po;
    shadow.position.set(cur.px, (mob ? 0.75 : 0) - 1.2 * cur.ps, -0.4); shadow.scale.set(cur.ps, cur.ps, 1);
    shadow.material.opacity = cur.po * 0.75 * (1 - clamp01(la * 2)) * (p < 0.32 ? 1 : desc);
    accentLight.intensity = 1.6 * sop + 0.8 * cur.po + 6 * ignite * (1 - clamp01((p - 0.31) / 0.02));
    phone.updateMatrixWorld();

    // ---- impact: flash, shockwave rings, sparks, camera shake (all from scroll progress)
    const hit = impact > 0 && impact < 1;
    const cx = X0, cy = mob ? 0.75 : 0;
    flash.position.set(cx, cy, 0.5); flash.material.opacity = hit ? Math.pow(1 - impact, 6) * 0.5 : 0;
    flash.scale.setScalar(0.25 + impact * 0.5);
    [shock, shock2].forEach((r, i) => { r.position.set(cx, cy, 0.2); const e = clamp01(impact * (1.6 - i * 0.4));
      r.scale.setScalar(0.2 + e * (i ? 5.5 : 4.2)); r.material.opacity = hit && e < 0.98 ? Math.pow(1 - e, 2) * (i ? 0.7 : 0.9) : 0; r.lookAt(camera.position); });
    sparks.material.opacity = hit ? Math.pow(1 - impact, 2) : 0;
    if (hit) {
      const tt = impact * 1.4;
      for (let i = 0; i < SPARKS; i++) {
        const d = sSpeed[i] * tt;
        sPos[i * 3] = cx + sDir[i * 3] * d; sPos[i * 3 + 1] = cy + sDir[i * 3 + 1] * d - 1.6 * tt * tt; sPos[i * 3 + 2] = 0.4 + sDir[i * 3 + 2] * d;
      }
      sGeo.attributes.position.needsUpdate = true;
    }
    bloom.strength = 0.22 + (hit ? Math.pow(1 - impact, 4) * 0.45 : 0) + ignite * 0.25 * (1 - desc);
    const shake = hit ? Math.pow(1 - impact, 2) * 0.09 : 0;
    camera.position.set(Math.sin(t * 61) * shake, Math.cos(t * 47) * shake, 9);

    // ---- launch: exhaust plume from the phone's tail, speed lines rushing past
    const burning = ignite * (1 - clamp01((p - 0.31) / 0.015));
    plume.material.opacity = burning;
    if (burning > 0) {
      nozzle.set(-PW / 2 - 0.05, 0, 0).applyMatrix4(phone.matrixWorld);
      up.set(1, 0, 0).transformDirection(phone.matrixWorld);       // the phone's tail-to-nose axis
      const thrust = 1.4 + la * 3.5;
      for (let i = 0; i < PLUME; i++) {
        const age = (pPhase[i] + t * (2.2 + la * 2)) % 1;
        const spread = 0.12 + age * 0.55;
        plPos[i * 3] = nozzle.x - up.x * age * thrust + pSpread[i * 2] * spread;
        plPos[i * 3 + 1] = nozzle.y - up.y * age * thrust + pSpread[i * 2] * spread * 0.3;
        plPos[i * 3 + 2] = nozzle.z + pSpread[i * 2 + 1] * spread;
        const hot = 1 - age;                                          // white core → lime → fade
        plCol[i * 3] = (0.78 + hot * 0.22) * hot; plCol[i * 3 + 1] = (0.96 + hot * 0.04) * hot; plCol[i * 3 + 2] = (0.2 + hot * 0.8) * hot * hot;
      }
      plGeo.attributes.position.needsUpdate = true; plGeo.attributes.color.needsUpdate = true;
    }
    flame.material.opacity = burning * 0.95;
    if (burning > 0) { flame.position.copy(nozzle).addScaledVector(up, -0.18); flame.scale.setScalar((0.9 + la * 1.4) * (0.85 + Math.sin(t * 37) * 0.08 + Math.sin(t * 23) * 0.07)); flame.lookAt(camera.position); }
    const rush = Math.sin(Math.PI * clamp01((p - 0.22) / 0.11));
    speedLines.material.opacity = rush * 0.3;
    if (rush > 0) {
      const v = 6 + la * 30;
      for (let i = 0; i < LINES; i++) {
        const yy = ((lnBase[i * 3 + 1] - t * v) % 14 + 14) % 14 - 7, len = 0.3 + la * 1.6;
        lnPos[i * 6] = lnBase[i * 3]; lnPos[i * 6 + 1] = yy; lnPos[i * 6 + 2] = lnBase[i * 3 + 2];
        lnPos[i * 6 + 3] = lnBase[i * 3]; lnPos[i * 6 + 4] = yy + len; lnPos[i * 6 + 5] = lnBase[i * 3 + 2];
      }
      lnGeo.attributes.position.needsUpdate = true;
    }

    // ---- layouts on the screen
    const want = l > 0 ? Math.min(LAYOUTS.length - 1, Math.floor(l * LAYOUTS.length)) : 0;
    if (want !== shown && pads[LAYOUTS[want]]) { shown = want; paintScreen(want); if (layoutName) layoutName.textContent = NAMES[want]; }

    // ---- dust
    dust.rotation.y = pointer.x * 0.08 + t * 0.006; dust.rotation.x = pointer.y * 0.05;
    dust.position.y = state.hero * 0.6 + p * 2 - rush * la * 2;

    // ---- DOM: story panels; exploded-view callouts with SVG leaders
    panels.forEach(el => { const [x0, x1, x2, x3] = el.dataset.at.split(",").map(Number); const f = fade(p, x0, x1, x2, x3);
      el.style.opacity = f.toFixed(3); el.style.transform = `translateY(${((1 - f) * 18).toFixed(1)}px)`; el.style.visibility = f > 0.01 ? "visible" : "hidden"; });
    const vis = ex * (mob ? 0 : 1);
    if (leaders) {
      leaders.style.opacity = vis.toFixed(3);
      const anchors = ANCHORS.map(([ax, ay], i) => { v3.set(PW * ax, PH * ay, 0).applyMatrix4(LAYERS[i].matrixWorld).project(camera);
        return [(v3.x * 0.5 + 0.5) * innerWidth, (-v3.y * 0.5 + 0.5) * innerHeight]; });
      const colX = Math.min(innerWidth - 330, Math.max(...anchors.map(a => a[0])) + 150);
      const topY = Math.max(110, (innerHeight - 3 * 84) / 2 - 40);
      callouts.forEach((el, i) => {
        const [x0, y0] = anchors[i], lx = colX, ly = topY + i * 84, op = clamp01(vis * 1.6 - i * 0.15).toFixed(3);
        el.style.opacity = op; el.style.visibility = vis > 0.02 ? "visible" : "hidden";
        el.style.transform = `translate(${lx.toFixed(1)}px, ${(ly - 10).toFixed(1)}px)`;
        leaderPaths[i].setAttribute("d", `M${x0.toFixed(1)} ${y0.toFixed(1)} L${(lx - 24).toFixed(1)} ${ly.toFixed(1)} L${(lx - 6).toFixed(1)} ${ly.toFixed(1)}`);
        leaderDots[i].setAttribute("cx", x0.toFixed(1)); leaderDots[i].setAttribute("cy", y0.toFixed(1));
        leaderPaths[i].style.opacity = leaderDots[i].style.opacity = op;
      });
    }

    canvas.style.opacity = (1 - o).toFixed(3);
    composer.render();
  }
  requestAnimationFrame(frame);
  setTimeout(() => document.documentElement.classList.add("ready"), 350);
}

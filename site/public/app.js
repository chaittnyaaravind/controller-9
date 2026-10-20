// Controller-9 site: one WebGL scene (three.js) driven by scroll (GSAP ScrollTrigger + Lenis).
// Act 1: the Pocket Pad mark in 3D. Act 2: a phone turns sideways and becomes the controller.
// Act 3: tilt to steer. Act 4: the five layouts on its screen. Reduced motion / no WebGL: static poster.
import * as THREE from "three";
import { RoundedBoxGeometry } from "three/addons/geometries/RoundedBoxGeometry.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";

const gsap = window.gsap, ScrollTrigger = window.ScrollTrigger;
const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;
const canvas = document.getElementById("gl");
const LAYOUTS = ["xbox", "dualsense", "dualshock4", "switchpro", "8bitdo-pro2"];
const NAMES = ["Xbox-style", "DualSense-style", "DualShock 4-style", "Switch Pro-style", "8BitDo Pro 2-style"];

// ------------------------------------------------------------------ chrome (works without WebGL)
const nav = document.getElementById("nav");
const onScroll = () => nav.classList.toggle("scrolled", scrollY > 8);
addEventListener("scroll", onScroll, { passive: true }); onScroll();
const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { rootMargin: "0px 0px -12% 0px" });
document.querySelectorAll(".reveal").forEach((el, i) => { el.style.transitionDelay = (i % 4) * 60 + "ms"; io.observe(el); });
const ua = navigator.userAgent;
const plat = /Android/i.test(ua) ? "android" : /iPhone|iPad/i.test(ua) ? "iphone" : /Mac/i.test(ua) ? "mac" : /Win/i.test(ua) ? "windows" : /Linux/i.test(ua) ? "linux" : "";
const mine = plat && document.querySelector(`[data-platform="${plat}"]`); if (mine) mine.classList.add("yours");

function webglOK() { try { const c = document.createElement("canvas"); return !!(c.getContext("webgl2") || c.getContext("webgl")); } catch { return false; } }
if (reduced || !webglOK() || !gsap) { document.documentElement.classList.add("static"); } else { start(); }

function start() {
  document.documentElement.classList.add("gl");
  gsap.registerPlugin(ScrollTrigger);
  if (window.Lenis) {
    const lenis = new window.Lenis({ lerp: 0.1 });
    lenis.on("scroll", ScrollTrigger.update);
    gsap.ticker.add(t => lenis.raf(t * 1000)); gsap.ticker.lagSmoothing(0);
    document.querySelectorAll('a[href^="#"]').forEach(a => a.addEventListener("click", e => { const t = document.querySelector(a.getAttribute("href")); if (t) { e.preventDefault(); lenis.scrollTo(t, { offset: -64 }); } }));
  }

  // ---------------------------------------------------------------- renderer, scene, light
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(devicePixelRatio, innerWidth < 760 ? 1.5 : 2));
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.05;
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 100); camera.position.set(0, 0, 9);
  const key = new THREE.DirectionalLight(0xffffff, 1.4); key.position.set(3, 4, 6); scene.add(key);
  const rim = new THREE.DirectionalLight(0xc6f432, 0.25); rim.position.set(-4, -2, -3); scene.add(rim);

  const LIME = new THREE.Color("#C6F432");
  const metal = new THREE.MeshPhysicalMaterial({ color: "#C9CDD1", metalness: 0.85, roughness: 0.26, clearcoat: 1, clearcoatRoughness: 0.18 });
  const graphite = new THREE.MeshPhysicalMaterial({ color: "#16191C", metalness: 0.55, roughness: 0.38, clearcoat: 1, clearcoatRoughness: 0.12 });
  const lime = new THREE.MeshPhysicalMaterial({ color: LIME, emissive: LIME, emissiveIntensity: 0.25, roughness: 0.35, clearcoat: 1, toneMapped: false }); // exact brand lime

  // Soft glow sprite + contact shadow (radial textures drawn once).
  function radial(inner, outer) {
    const c = document.createElement("canvas"); c.width = c.height = 256; const g = c.getContext("2d");
    const gr = g.createRadialGradient(128, 128, 0, 128, 128, 128); gr.addColorStop(0, inner); gr.addColorStop(1, outer);
    g.fillStyle = gr; g.fillRect(0, 0, 256, 256); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
  }

  // ---------------------------------------------------------------- Act 1: the mark in 3D
  // Geometry of brand/build.py "regular" cut, 48-unit grid: body 39x22 r7 stroke 3.2, D-pad, button r4.2.
  const mark = new THREE.Group();
  const U = 1 / 22;
  function roundedRect(w, h, r) {
    const s = new THREE.Shape(); const x = -w / 2, y = -h / 2;
    s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r); s.lineTo(x + w, y + h - r);
    s.quadraticCurveTo(x + w, y + h, x + w - r, y + h); s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
    s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y); return s;
  }
  const outer = roundedRect(39 + 3.2, 22 + 3.2, 7 + 1.6), inner = roundedRect(39 - 3.2, 22 - 3.2, 7 - 1.6);
  outer.holes.push(inner);
  const ext = { depth: 3.2, bevelEnabled: true, bevelThickness: 0.9, bevelSize: 0.7, bevelSegments: 6, curveSegments: 32 };
  const ring = new THREE.Mesh(new THREE.ExtrudeGeometry(outer, ext), metal); ring.position.z = -1.6; mark.add(ring);
  const bar = (w, h) => new THREE.Mesh(new RoundedBoxGeometry(w, h, 3.6, 4, 1.4), metal);
  const dx = 15 - 24, dpadV = bar(3.2, 9.6), dpadH = bar(9.6, 3.2); dpadV.position.x = dpadH.position.x = dx; mark.add(dpadV, dpadH);
  const btn = new THREE.Mesh(new THREE.CylinderGeometry(4.2, 4.2, 3.8, 48), lime); btn.rotation.x = Math.PI / 2; btn.position.x = 32.5 - 24; mark.add(btn);
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(26, 26), new THREE.MeshBasicMaterial({ map: radial("rgba(198,244,50,.55)", "rgba(198,244,50,0)"), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending }));
  glow.position.set(32.5 - 24, 0, -3); mark.add(glow);
  mark.scale.setScalar(U * 1.5);
  scene.add(mark);
  const markMats = [metal, lime];

  // ---------------------------------------------------------------- Act 2-4: the phone
  const phone = new THREE.Group();
  const PW = 3.9, PH = 1.86, PD = 0.16;
  const body = new THREE.Mesh(new RoundedBoxGeometry(PW, PH, PD, 8, 0.2), graphite); phone.add(body);
  const screenCanvas = document.createElement("canvas"); screenCanvas.width = 2048; screenCanvas.height = 976;
  const sctx = screenCanvas.getContext("2d");
  const screenTex = new THREE.CanvasTexture(screenCanvas); screenTex.colorSpace = THREE.SRGBColorSpace; screenTex.anisotropy = 8;
  const screenMat = new THREE.MeshBasicMaterial({ map: screenTex, toneMapped: false, transparent: true });
  const screen = new THREE.Mesh(new THREE.PlaneGeometry(PW - 0.16, PH - 0.16), screenMat); screen.position.z = PD / 2 + 0.002; phone.add(screen);
  const island = new THREE.Mesh(new RoundedBoxGeometry(0.09, 0.42, 0.01, 2, 0.04), new THREE.MeshBasicMaterial({ color: "#000", transparent: true }));
  island.position.set(-PW / 2 + 0.2, 0, PD / 2 + 0.006); phone.add(island);
  scene.add(phone);
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(6, 1.6), new THREE.MeshBasicMaterial({ map: radial("rgba(0,0,0,.55)", "rgba(0,0,0,0)"), transparent: true, depthWrite: false }));
  shadow.rotation.x = -Math.PI / 2; scene.add(shadow);

  // Screen: the real layout art (site/public/assets/pad-*.svg) rasterised sharp, with a status pill.
  const pads = {};
  const loadPad = id => new Promise(res => { const img = new Image(); img.onload = () => { pads[id] = img; res(); }; img.onerror = res; img.src = `/assets/pad-${id}.svg`; });
  let shown = -1;
  function paintScreen(i) {
    sctx.fillStyle = "#0B0C0D"; sctx.fillRect(0, 0, 2048, 976);
    const img = pads[LAYOUTS[i]];
    if (img) sctx.drawImage(img, 120, 70, 1808, 904);
    sctx.fillStyle = "rgba(17,19,21,.92)"; sctx.beginPath(); sctx.roundRect(784, 26, 480, 52, 26); sctx.fill();
    sctx.fillStyle = "#C6F432"; sctx.beginPath(); sctx.arc(820, 52, 8, 0, Math.PI * 2); sctx.fill();
    sctx.fillStyle = "rgba(238,240,241,.85)"; sctx.font = "500 22px Geist, system-ui"; sctx.fillText("P1 · YOUR COMPUTER · WI-FI", 842, 60);
    screenTex.needsUpdate = true;
  }
  Promise.all(LAYOUTS.map(loadPad)).then(() => { paintScreen(0); shown = 0; });

  // ---------------------------------------------------------------- scroll story → state
  const state = { p: 0, hero: 0 };
  ScrollTrigger.create({ trigger: "#story", start: "top bottom", end: "bottom bottom", scrub: true, onUpdate: s => { state.p = s.progress; } });
  ScrollTrigger.create({ trigger: ".hero", start: "top top", end: "bottom top", scrub: true, onUpdate: s => { state.hero = s.progress; } });
  const panels = [...document.querySelectorAll(".panel")];
  const layoutName = document.getElementById("layout-name");
  const fade = (p, a, b, c, d) => Math.max(0, Math.min(1, (p - a) / (b - a), (d - p) / (d - c)));
  const smooth = t => t * t * (3 - 2 * t);
  const clamp01 = t => Math.min(1, Math.max(0, t));

  // Cursor parallax (desktop only).
  const pointer = { x: 0, y: 0 };
  addEventListener("pointermove", e => { if (e.pointerType === "mouse") { pointer.x = e.clientX / innerWidth - 0.5; pointer.y = e.clientY / innerHeight - 0.5; } }, { passive: true });

  function resize() {
    const w = innerWidth, h = innerHeight;
    renderer.setSize(w, h, false); camera.aspect = w / h;
    camera.fov = w < 760 ? 44 : 30; camera.updateProjectionMatrix();
  }
  addEventListener("resize", resize); resize();

  const cur = { mx: 0, my: 0, mr: 0, ms: 1, mo: 1, px: 0, py: -4, pr: Math.PI / 2, pt: 0, ps: 1, po: 0 };
  let visible = true;
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; }).observe(document.getElementById("gl-zone"));

  const clock = new THREE.Clock();
  function frame() {
    requestAnimationFrame(frame);
    if (!visible || document.hidden) return;
    const t = clock.getElapsedTime(), p = state.p, mobile = innerWidth < 760;
    const side = mobile ? 0 : 1.35, markSide = mobile ? 0 : 1.75;

    // a: handoff (mark out, phone in), s: steering, l: layouts, o: exit.
    const a = smooth(clamp01((p - 0.06) / 0.2)), m = smooth(clamp01((p - 0.03) / 0.09)), s = clamp01((p - 0.34) / 0.22), l = clamp01((p - 0.6) / 0.32), o = smooth(clamp01((p - 0.95) / 0.05));
    const tgt = {
      mx: markSide + m * 0.4, my: (mobile ? 1.0 : 0.05) + state.hero * 0.25 + m * 1.8, mr: -0.38 + Math.sin(t * 0.45) * 0.32 + pointer.x * 0.5, ms: 1 - m * 0.3, mo: 1 - m,
      px: side * 0.95, py: (mobile ? 0.8 : 0) - (1 - a) * 4 - o * 0.6, pr: (1 - a) * Math.PI / 2,
      pt: Math.sin(s * Math.PI * 2) * 0.22, ps: (mobile ? 0.62 : 0.86) * (1 - o * 0.15), po: a * (1 - o),
    };
    for (const key in tgt) cur[key] += (tgt[key] - cur[key]) * 0.085;

    mark.position.set(cur.mx, cur.my, 0); mark.rotation.set(-pointer.y * 0.3 + 0.12, cur.mr, 0); mark.scale.setScalar(U * (mobile ? 1.15 : 1.5) * cur.ms);
    mark.visible = cur.mo > 0.01;
    markMats.forEach(m => { m.transparent = cur.mo < 0.999; m.opacity = cur.mo; });
    glow.material.opacity = cur.mo * (0.75 + Math.sin(t * 2.2) * 0.25);

    phone.visible = cur.po > 0.01;
    phone.position.set(cur.px, cur.py, 0);
    phone.rotation.set(-0.32 + pointer.y * 0.1, -0.38 * (mobile ? 0 : 1) + pointer.x * 0.15, cur.pr + cur.pt);
    phone.scale.setScalar(cur.ps);
    graphite.transparent = cur.po < 0.999; graphite.opacity = cur.po; screenMat.opacity = cur.po; island.material.opacity = cur.po;
    shadow.position.set(cur.px, cur.py - 1.25 * cur.ps, -0.4); shadow.scale.set(cur.ps, cur.ps, 1); shadow.material.opacity = cur.po * 0.8;

    // Act 4: the five layouts on the screen, and their name in the panel.
    const want = l > 0 ? Math.min(LAYOUTS.length - 1, Math.floor(l * LAYOUTS.length)) : 0;
    if (want !== shown && pads[LAYOUTS[want]]) { shown = want; paintScreen(want); if (layoutName) layoutName.textContent = NAMES[want]; }

    panels.forEach(el => { const [x0, x1, x2, x3] = el.dataset.at.split(",").map(Number); const f = fade(p, x0, x1, x2, x3);
      el.style.opacity = f.toFixed(3); el.style.transform = `translateY(${((1 - f) * 18).toFixed(1)}px)`; el.style.visibility = f > 0.01 ? "visible" : "hidden"; });
    canvas.style.opacity = (1 - o).toFixed(3);
    renderer.render(scene, camera);
  }
  frame();
}

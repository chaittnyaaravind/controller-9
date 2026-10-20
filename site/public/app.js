// Controller-9 site — one WebGL scene, directed by scroll.
// Act 0 (load): the Pocket Pad mark assembles from glowing edges, then solidifies into prismatic glass.
// Act 1: the mark hands off to a phone that turns sideways and becomes the controller.
// Act 2: exploded view — glass, controller screen, sensors, radios — with hairline callouts.
// Act 3: tilt to steer.  Act 4: the five layouts.  Reduced motion / no WebGL: static poster.
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
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: !lite, powerPreference: "high-performance" });
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
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 60); camera.position.set(0, 0, 9);
  const key = new THREE.DirectionalLight(0xffffff, 1.6); key.position.set(3, 4, 6); scene.add(key);
  const limeLight = new THREE.PointLight(0xc6f432, 6, 6, 2); scene.add(limeLight);

  const composer = new EffectComposer(renderer);
  composer.addPass(new RenderPass(scene, camera));
  const bloom = new UnrealBloomPass(new THREE.Vector2(512, 512), 0.22, 0.18, 0.985);
  composer.addPass(bloom);
  composer.addPass(new OutputPass());

  const LIME = new THREE.Color("#C6F432");
  // Prismatic glass for the mark (desktop); polished metal on light devices.
  const glass = lite
    ? new THREE.MeshPhysicalMaterial({ color: "#C9CDD1", metalness: 0.85, roughness: 0.24, clearcoat: 1, transparent: true })
    : new THREE.MeshPhysicalMaterial({ color: "#ffffff", metalness: 0, roughness: 0.06, transmission: 1, thickness: 2.2, ior: 1.5,
        dispersion: 4, iridescence: 1, iridescenceIOR: 1.6, iridescenceThicknessRange: [180, 620], clearcoat: 1,
        attenuationColor: new THREE.Color("#DDEFC0"), attenuationDistance: 6, transparent: true });
  const lime = new THREE.MeshPhysicalMaterial({ color: LIME, emissive: LIME, emissiveIntensity: 0.32, roughness: 0.35, clearcoat: 1, toneMapped: false, transparent: true });
  const graphite = new THREE.MeshPhysicalMaterial({ color: "#15181B", metalness: 0.6, roughness: 0.36, clearcoat: 1, clearcoatRoughness: 0.1, transparent: true });

  function radial(inner, outer, size = 256) {
    const c = document.createElement("canvas"); c.width = c.height = size; const g = c.getContext("2d");
    const gr = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2); gr.addColorStop(0, inner); gr.addColorStop(1, outer);
    g.fillStyle = gr; g.fillRect(0, 0, size, size); const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t;
  }

  // ---------------------------------------------------------------- dust: depth particles
  const DUST = lite ? 260 : 700;
  const dpos = new Float32Array(DUST * 3);
  for (let i = 0; i < DUST; i++) { dpos[i * 3] = (Math.random() - 0.5) * 18; dpos[i * 3 + 1] = (Math.random() - 0.5) * 11; dpos[i * 3 + 2] = -Math.random() * 10 + 2; }
  const dgeo = new THREE.BufferGeometry(); dgeo.setAttribute("position", new THREE.BufferAttribute(dpos, 3));
  const dust = new THREE.Points(dgeo, new THREE.PointsMaterial({ size: 0.035, map: radial("rgba(238,240,241,1)", "rgba(238,240,241,0)", 64), transparent: true, opacity: 0.5, depthWrite: false }));
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
  // Assembly: glowing edges that draw in before the solid appears.
  const edgeMat = new THREE.LineBasicMaterial({ color: 0xeef0f1, transparent: true, opacity: 0, toneMapped: false });
  const edges = new THREE.Group();
  [[ringGeo, 18, 0], [barV, 1, -9], [barH, 1, -9]].forEach(([g, ang, x]) => { const l = new THREE.LineSegments(new THREE.EdgesGeometry(g, ang), edgeMat); l.position.x = x; edges.add(l); });
  mark.add(edges);
  const glow = new THREE.Mesh(new THREE.PlaneGeometry(30, 30), new THREE.MeshBasicMaterial({ map: radial("rgba(198,244,50,.16)", "rgba(198,244,50,0)"), transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, toneMapped: false }));
  glow.position.set(8.5, 0, -4); mark.add(glow);
  scene.add(mark);
  const markSolids = [ring, dV, dH, btn];

  // ---------------------------------------------------------------- the phone, in layers
  const PW = 3.9, PH = 1.86, PD = 0.16;
  const phone = new THREE.Group(); scene.add(phone);
  const body = new THREE.Mesh(new RoundedBoxGeometry(PW, PH, PD, 8, 0.2), graphite); phone.add(body);
  // Layer: logic board — hairline traces, sensors, radios.
  const boardCanvas = document.createElement("canvas"); boardCanvas.width = 1024; boardCanvas.height = 488;
  (() => {
    const g = boardCanvas.getContext("2d"); g.fillStyle = "#0E1311"; g.fillRect(0, 0, 1024, 488);
    g.strokeStyle = "rgba(198,244,50,.35)"; g.lineWidth = 2;
    let seed = 7; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
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
  // Layer: the controller screen.
  const screenCanvas = document.createElement("canvas"); screenCanvas.width = 2048; screenCanvas.height = 976;
  const sctx = screenCanvas.getContext("2d");
  const screenTex = new THREE.CanvasTexture(screenCanvas); screenTex.colorSpace = THREE.SRGBColorSpace; screenTex.anisotropy = 8;
  const screenMat = new THREE.MeshBasicMaterial({ map: screenTex, toneMapped: false, transparent: true });
  const screen = new THREE.Mesh(new THREE.PlaneGeometry(PW - 0.16, PH - 0.16), screenMat); screen.position.z = PD / 2 + 0.002; phone.add(screen);
  // Layer: cover glass.
  const coverMat = new THREE.MeshPhysicalMaterial({ color: "#BFD3DA", roughness: 0.03, metalness: 0, transparent: true, opacity: 0.07, clearcoat: 1, depthWrite: false }); // clear, reflective
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
    sctx.fillStyle = "#C6F432"; sctx.beginPath(); sctx.arc(820, 52, 8, 0, Math.PI * 2); sctx.fill();
    sctx.fillStyle = "rgba(238,240,241,.85)"; sctx.font = "500 22px Geist, system-ui"; sctx.fillText("P1 · YOUR COMPUTER · WI-FI", 842, 60);
    screenTex.needsUpdate = true;
  }
  Promise.all(LAYOUTS.map(loadPad)).then(() => { paintScreen(0); shown = 0; });

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
  const pointer = { x: 0, y: 0, tx: 0, ty: 0 };
  addEventListener("pointermove", e => { if (e.pointerType === "mouse") { pointer.tx = e.clientX / innerWidth - 0.5; pointer.ty = e.clientY / innerHeight - 0.5; } }, { passive: true });

  function resize() {
    const w = innerWidth, h = innerHeight;
    renderer.setSize(w, h, false); composer.setSize(w, h); bloom.setSize(w / 2, h / 2);
    camera.aspect = w / h; camera.fov = w < 760 ? 46 : 30; camera.updateProjectionMatrix();
  }
  addEventListener("resize", resize); resize();

  const cur = { mx: 0, my: 0, ms: 1, mo: 1, px: 0, py: -4, pr: Math.PI / 2, pt: 0, ps: 1, po: 0, ex: 0 };
  let visible = true;
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; }).observe(document.getElementById("gl-zone"));
  const born = performance.now();
  const v3 = new THREE.Vector3();
  const LAYERS = [cover, screen, board, body];
  // Anchor points (fractions of the phone size) spread across the stack so leaders never overlap.
  const ANCHORS = [[0.3, 0.42], [0.42, 0.12], [0.36, -0.2], [0.44, -0.44]];
  const leaders = document.getElementById("leaders");
  const leaderPaths = leaders ? [...leaders.querySelectorAll("path")] : [], leaderDots = leaders ? [...leaders.querySelectorAll("circle")] : [];

  function frame(now) {
    requestAnimationFrame(frame);
    if (!visible || document.hidden) return;
    const t = (now - born) / 1000, p = state.p, mob = mobile();
    pointer.x += (pointer.tx - pointer.x) * 0.06; pointer.y += (pointer.ty - pointer.y) * 0.06;

    // Act 0 — assembly on load: edges draw in, then the glass solidifies.
    const build = smooth(clamp01((t - 0.2) / 1.4)), solid = smooth(clamp01((t - 1.2) / 1.0));
    edges.scale.setScalar(0.94 + build * 0.06);

    // Scroll acts. m: mark exits; a: phone arrives; x: exploded view; s: steering; l: layouts; o: exit.
    const m = smooth(clamp01((p - 0.02) / 0.08)), a = smooth(clamp01((p - 0.05) / 0.15));
    const x = fade(p, 0.24, 0.32, 0.44, 0.5), s = clamp01((p - 0.52) / 0.16), l = clamp01((p - 0.7) / 0.24), o = smooth(clamp01((p - 0.96) / 0.04));
    const right = mob ? 0 : 1.25;
    const tgt = {
      mx: mob ? 0 : 1.55, my: (mob ? 1.0 : 0.05) + state.hero * 0.3 + m * 1.8, ms: 1 - m * 0.25, mo: 1 - m,
      px: right, py: (mob ? 0.75 : 0) - (1 - a) * 4 - o * 0.6, pr: (1 - a) * Math.PI / 2,
      pt: Math.sin(s * Math.PI * 2) * 0.22, ps: (mob ? 0.6 : 0.84) * (1 - o * 0.15), po: a * (1 - o), ex: x,
    };
    for (const k in tgt) cur[k] += (tgt[k] - cur[k]) * 0.085;

    // Mark: floats, sways to the cursor.
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
    v3.set(8.5, 0, 6).applyMatrix4(mark.matrixWorld); limeLight.position.copy(v3); limeLight.intensity = 1.6 * sop + 0.8 * cur.po;
    if (giant) giant.style.opacity = (0.035 * (1 - m)).toFixed(3);

    // Phone: arrives, turns sideways, explodes into layers, tilts, cycles layouts.
    phone.visible = cur.po > 0.01;
    const ex = cur.ex, pull = mob ? 0.55 : 0.75;
    phone.position.set(cur.px - ex * 0.9, cur.py + ex * 0.05, 0);
    phone.rotation.set(-0.3 + pointer.y * 0.1 - ex * 0.25, -0.36 * (mob ? 0 : 1) + pointer.x * 0.15 + ex * 0.55, cur.pr + cur.pt);
    phone.scale.setScalar(cur.ps * (1 - ex * 0.16));
    board.position.z = PD / 2 + 0.001 + ex * pull * 0.5;
    screen.position.z = PD / 2 + 0.002 + ex * pull * 1.0;
    cover.position.z = PD / 2 + 0.012 + ex * pull * 1.5;
    graphite.opacity = cur.po; screenMat.opacity = cur.po; board.material.opacity = cur.po * clamp01(ex * 3); island.material.opacity = cur.po * (1 - ex);
    coverMat.opacity = 0.07 * cur.po;
    shadow.position.set(cur.px, cur.py - 1.2 * cur.ps, -0.4); shadow.scale.set(cur.ps, cur.ps, 1); shadow.material.opacity = cur.po * 0.75;

    const want = l > 0 ? Math.min(LAYOUTS.length - 1, Math.floor(l * LAYOUTS.length)) : 0;
    if (want !== shown && pads[LAYOUTS[want]]) { shown = want; paintScreen(want); if (layoutName) layoutName.textContent = NAMES[want]; }

    // Dust drifts and parallaxes with the cursor.
    dust.rotation.y = pointer.x * 0.08 + t * 0.006; dust.rotation.x = pointer.y * 0.05;
    dust.position.y = state.hero * 0.6 + p * 2;

    // DOM: story panels, and exploded-view callouts pinned to each layer's projected corner.
    panels.forEach(el => { const [x0, x1, x2, x3] = el.dataset.at.split(",").map(Number); const f = fade(p, x0, x1, x2, x3);
      el.style.opacity = f.toFixed(3); el.style.transform = `translateY(${((1 - f) * 18).toFixed(1)}px)`; el.style.visibility = f > 0.01 ? "visible" : "hidden"; });
    phone.updateMatrixWorld();
    // Callouts: a distinct anchor on each layer, labels in an evenly spaced column that stays on screen,
    // joined by hairline leaders (SVG).
    const vis = ex * (mob ? 0 : 1);
    if (leaders) {
      leaders.style.opacity = vis.toFixed(3);
      const anchors = ANCHORS.map(([ax, ay], i) => { v3.set(PW * ax, PH * ay, 0).applyMatrix4(LAYERS[i].matrixWorld).project(camera);
        return [(v3.x * 0.5 + 0.5) * innerWidth, (-v3.y * 0.5 + 0.5) * innerHeight]; });
      const colX = Math.min(innerWidth - 330, Math.max(...anchors.map(a => a[0])) + 150);
      const top = Math.max(110, (innerHeight - 3 * 84) / 2 - 40);
      callouts.forEach((el, i) => {
        const [x0, y0] = anchors[i], lx = colX, ly = top + i * 84;
        el.style.opacity = clamp01(vis * 1.6 - i * 0.15).toFixed(3); el.style.visibility = vis > 0.02 ? "visible" : "hidden";
        el.style.transform = `translate(${lx.toFixed(1)}px, ${(ly - 10).toFixed(1)}px)`;
        leaderPaths[i].setAttribute("d", `M${x0.toFixed(1)} ${y0.toFixed(1)} L${(lx - 24).toFixed(1)} ${ly.toFixed(1)} L${(lx - 6).toFixed(1)} ${ly.toFixed(1)}`);
        leaderDots[i].setAttribute("cx", x0.toFixed(1)); leaderDots[i].setAttribute("cy", y0.toFixed(1));
        leaderPaths[i].style.opacity = leaderDots[i].style.opacity = clamp01(vis * 1.6 - i * 0.15).toFixed(3);
      });
    }

    canvas.style.opacity = (1 - o).toFixed(3);
    composer.render();
  }
  requestAnimationFrame(frame);
  setTimeout(() => document.documentElement.classList.add("ready"), 350);
}

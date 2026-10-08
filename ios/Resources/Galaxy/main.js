import * as THREE from "./vendor/three.module.min.js";
import { article } from "./article.js";

// Debate camps (2–4) ride the Galilean moons in order; Amalthea is scenery only.
const moonInfo = {
  amalthea: { name: "木卫五", latin: "Amalthea", radius: 0.42, orbit: 9.1, period: 26, phase: 4.6, incl: 0.02 },
  io:       { name: "木卫一", latin: "Io",       radius: 0.66, orbit: 10.6, period: 38, phase: 0.6, incl: 0.01 },
  europa:   { name: "木卫二", latin: "Europa",   radius: 0.6,  orbit: 12.0, period: 52, phase: 2.3, incl: 0.03 },
  ganymede: { name: "木卫三", latin: "Ganymede", radius: 0.86, orbit: 13.5, period: 70, phase: 3.5, incl: 0.02 },
  callisto: { name: "木卫四", latin: "Callisto", radius: 0.8,  orbit: 15.0, period: 96, phase: 5.4, incl: 0.04 },
};
const campMoons = ["ganymede", "io", "europa", "callisto"];
const stanceStyle = {
  pro:     { text: "支持", color: "#6fbf8a" },
  con:     { text: "反对", color: "#e0654f" },
  neutral: { text: "中立或折中", color: "#e3b04b" },
};
const styleOf = stance => stanceStyle[stance] || stanceStyle.neutral;
// Article text is model output: never let it become markup.
const esc = v => String(v ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
import * as S from "./shaders.js";

const R = 7;                          // Jupiter radius (scene units)
const FOV = 48;
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
const sunDir = new THREE.Vector3(-0.9, 0.38, 0.62).normalize();
const camps = article.debate.camps.slice(0, 4).map((camp, i) => ({ ...camp, moon: campMoons[i] }));
const bodies = [...camps, { moon: "amalthea", stance: null, decor: true }];
const kindIndex = { amalthea: 0, io: 1, europa: 2, ganymede: 3, callisto: 4 };

// ------------------------------------------------------------------ renderer & scene
const canvas = document.getElementById("scene");
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: "high-performance" });
let pixelRatio = Math.min(devicePixelRatio, 2);
renderer.setPixelRatio(pixelRatio);
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 0;
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 3000);

// The orbital system is tilted slightly so the composition reads diagonally.
const system = new THREE.Group();
system.rotation.z = -0.14;
scene.add(system);

// ------------------------------------------------------------------ sky
const sky = new THREE.Mesh(new THREE.SphereGeometry(900, 48, 32),
  new THREE.ShaderMaterial({ vertexShader: S.skyVertex, fragmentShader: S.skyFragment, side: THREE.BackSide, depthWrite: false }));
scene.add(sky);

const starCount = 2600;
const starGeo = new THREE.BufferGeometry();
{
  const pos = new Float32Array(starCount * 3), size = new Float32Array(starCount), phase = new Float32Array(starCount), color = new Float32Array(starCount * 3);
  const tints = [[1, 1, 1], [0.8, 0.88, 1], [1, 0.92, 0.8], [1, 0.82, 0.7], [0.75, 0.82, 1]];
  for (let i = 0; i < starCount; i++) {
    const u = Math.random() * 2 - 1, th = Math.random() * Math.PI * 2, s = Math.sqrt(1 - u * u);
    pos.set([Math.cos(th) * s * 700, u * 700, Math.sin(th) * s * 700], i * 3);
    const bright = Math.random() < 0.035;
    size[i] = bright ? 3.4 + Math.random() * 3 : 1.1 + Math.random() * 1.8;
    phase[i] = Math.random();
    const t = tints[(Math.random() * tints.length) | 0], k = bright ? 1 : 0.45 + Math.random() * 0.45;
    color.set([t[0] * k, t[1] * k, t[2] * k], i * 3);
  }
  starGeo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  starGeo.setAttribute("aSize", new THREE.BufferAttribute(size, 1));
  starGeo.setAttribute("aPhase", new THREE.BufferAttribute(phase, 1));
  starGeo.setAttribute("aColor", new THREE.BufferAttribute(color, 3));
}
const starMat = new THREE.ShaderMaterial({
  vertexShader: S.starVertex, fragmentShader: S.starFragment, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  uniforms: { uTime: { value: 0 }, uPixelRatio: { value: pixelRatio }, uTwinkle: { value: reduceMotion ? 0 : 1 } },
});
scene.add(new THREE.Points(starGeo, starMat));

function glowTexture(stops) {
  const c = document.createElement("canvas"); c.width = c.height = 256;
  const g = c.getContext("2d"), grad = g.createRadialGradient(128, 128, 0, 128, 128, 128);
  stops.forEach(([o, col]) => grad.addColorStop(o, col));
  g.fillStyle = grad; g.fillRect(0, 0, 256, 256);
  const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace; return tex;
}
const sun = new THREE.Sprite(new THREE.SpriteMaterial({
  map: glowTexture([[0, "rgba(255,250,235,1)"], [0.08, "rgba(255,236,200,0.9)"], [0.25, "rgba(255,200,140,0.25)"], [1, "rgba(255,170,90,0)"]]),
  blending: THREE.AdditiveBlending, depthWrite: false, transparent: true,
}));
sun.position.copy(sunDir).multiplyScalar(600); sun.scale.setScalar(150);
scene.add(sun);

// ------------------------------------------------------------------ Jupiter
const moonUniformArray = Array.from({ length: 5 }, () => new THREE.Vector4(0, 0, 999, 0.0001));
const jupiterMat = new THREE.ShaderMaterial({
  vertexShader: S.jupiterVertex, fragmentShader: S.jupiterFragment,
  uniforms: { uTime: { value: 0 }, uSunDir: { value: sunDir }, uMoons: { value: moonUniformArray }, uSpin: { value: 0 }, uFocus: { value: 0 } },
});
const jupiter = new THREE.Mesh(new THREE.SphereGeometry(R, 200, 140), jupiterMat);
jupiter.scale.y = 0.935;                       // Jupiter is visibly oblate
system.add(jupiter);

const haloR = 1.075;
const halo = new THREE.Mesh(new THREE.SphereGeometry(R * haloR, 96, 64), new THREE.ShaderMaterial({
  vertexShader: S.haloVertex, fragmentShader: S.haloFragment, side: THREE.BackSide, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  uniforms: { uSunDir: { value: sunDir }, uK: { value: Math.sqrt(1 - 1 / (haloR * haloR)) }, uIntensity: { value: 1.25 } },
}));
halo.scale.y = 0.94;
system.add(halo);

const ringInner = R * 1.16, ringOuter = R * 1.4;
const ring = new THREE.Mesh(new THREE.RingGeometry(ringInner, ringOuter, 360, 1), new THREE.ShaderMaterial({
  vertexShader: S.ringVertex, fragmentShader: S.ringFragment, side: THREE.DoubleSide, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  uniforms: { uSunDir: { value: sunDir }, uCenter: { value: new THREE.Vector3() }, uR: { value: R }, uInner: { value: ringInner }, uOuter: { value: ringOuter }, uOpacity: { value: 0.16 } },
}));
ring.rotation.x = -Math.PI / 2;
system.add(ring);

// ------------------------------------------------------------------ moons
function ringTexture() {
  const c = document.createElement("canvas"); c.width = c.height = 256;
  const g = c.getContext("2d");
  const grad = g.createRadialGradient(128, 128, 60, 128, 128, 128);
  grad.addColorStop(0, "rgba(255,255,255,0)"); grad.addColorStop(0.55, "rgba(255,255,255,0.0)");
  grad.addColorStop(0.72, "rgba(255,255,255,0.85)"); grad.addColorStop(0.8, "rgba(255,255,255,0.25)"); grad.addColorStop(1, "rgba(255,255,255,0)");
  g.fillStyle = grad; g.fillRect(0, 0, 256, 256);
  return new THREE.CanvasTexture(c);
}
const haloTex = ringTexture();

const moons = bodies.map((camp, i) => {
  const info = moonInfo[camp.moon];
  const stance = new THREE.Color(camp.decor ? "#8a8f9c" : styleOf(camp.stance).color);
  const lumpy = camp.moon === "amalthea";
  const mat = new THREE.ShaderMaterial({
    vertexShader: S.moonVertex, fragmentShader: S.moonFragment,
    uniforms: {
      uKind: { value: kindIndex[camp.moon] }, uTime: { value: 0 }, uSunDir: { value: sunDir },
      uJupiter: { value: new THREE.Vector3() }, uJupiterR: { value: R }, uStance: { value: new THREE.Vector3(stance.r, stance.g, stance.b) },
      uHighlight: { value: 0 }, uRot: { value: new THREE.Matrix3() }, uLumpy: { value: lumpy ? 0.16 : 0 },
    },
  });
  const mesh = new THREE.Mesh(new THREE.SphereGeometry(info.radius, 128, 96), mat);
  if (lumpy) mesh.scale.set(1.3, 0.82, 0.78);
  const pivot = new THREE.Group();                 // orbital plane (with a small inclination)
  pivot.rotation.x = info.incl;
  pivot.add(mesh);
  system.add(pivot);

  // Generous invisible hit target: moons are small on a phone.
  const hit = new THREE.Mesh(new THREE.SphereGeometry(Math.max(info.radius * 2.6, 1.6), 12, 8), new THREE.MeshBasicMaterial({ visible: false }));
  if (!camp.decor) { hit.userData.index = i; mesh.add(hit); }

  const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: haloTex, color: stance, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 }));
  glow.scale.setScalar(info.radius * 3.6);
  mesh.add(glow);

  const pts = []; for (let k = 0; k <= 256; k++) { const a = (k / 256) * Math.PI * 2; pts.push(new THREE.Vector3(Math.cos(a) * info.orbit, 0, Math.sin(a) * info.orbit)); }
  const orbit = new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts),
    new THREE.LineBasicMaterial({ color: 0xa9bde0, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending }));
  pivot.add(orbit);

  return { camp, info, mesh, mat, pivot, hit, glow, orbit, stance, phase: info.phase, appear: 0, hide: 0, index: i, decor: !!camp.decor };
});
const campCount = camps.length;
const interactive = moons.filter(m => !m.decor);

// Io's volcanic plumes (Pele-like umbrellas), parented to Io so they ride its rotation.
const io = moons.find(m => m.camp.moon === "io");
const plumeCount = 420;
const plumeGeo = new THREE.BufferGeometry();
const plumePos = new Float32Array(plumeCount * 3), plumeLife = new Float32Array(plumeCount);
const plumeSeeds = Array.from({ length: plumeCount }, (_, i) => ({ az: Math.random() * Math.PI * 2, sp: 0.75 + Math.random() * 0.5, vent: i % 3 === 0 ? 1 : 0, life: Math.random() }));
plumeGeo.setAttribute("position", new THREE.BufferAttribute(plumePos, 3));
plumeGeo.setAttribute("aLife", new THREE.BufferAttribute(plumeLife, 1));
const plumeMat = new THREE.ShaderMaterial({
  vertexShader: S.plumeVertex, fragmentShader: S.plumeFragment, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  uniforms: { uPixelRatio: { value: pixelRatio }, uScale: { value: 1.2 }, uOpacity: { value: 0.9 } },
});
const plumes = new THREE.Points(plumeGeo, plumeMat);
io.mesh.add(plumes);
const vents = [new THREE.Vector3(0.35, 0.62, 0.7).normalize(), new THREE.Vector3(-0.55, -0.25, 0.8).normalize()];
function updatePlumes(dt) {
  const r = io.info.radius;
  for (let i = 0; i < plumeCount; i++) {
    const s = plumeSeeds[i];
    s.life = (s.life + dt * 0.22 * s.sp) % 1;
    const n = vents[s.vent];
    const t1 = new THREE.Vector3(0, 1, 0).cross(n).normalize(), t2 = n.clone().cross(t1);
    const L = s.life, scale = s.vent ? 0.6 : 1;
    const h = r * 0.75 * scale * 4 * L * (1 - L) * s.sp;
    const spread = r * 0.85 * scale * L * s.sp;
    const p = n.clone().multiplyScalar(r * 0.98 + h)
      .addScaledVector(t1, Math.cos(s.az) * spread).addScaledVector(t2, Math.sin(s.az) * spread);
    plumePos.set([p.x, p.y, p.z], i * 3);
    plumeLife[i] = L;
  }
  plumeGeo.attributes.position.needsUpdate = true;
  plumeGeo.attributes.aLife.needsUpdate = true;
}

// ------------------------------------------------------------------ camera rig
const rig = {
  mode: "intro", focus: null,             // focus: moon index or "jupiter"
  yaw: 0.32, pitch: 0.27, yawVel: 0, pitchVel: 0,
  fYaw: 0, fPitch: 0,
  timeScale: 1, timeScaleGoal: 1,
  shift: -0.04,
  from: null, t0: 0, duration: 1.7,
  steer: null,
};
let overviewDist = 60;

function fitOverview() {
  const w = innerWidth, h = innerHeight, aspect = w / h, tanH = Math.tan(THREE.MathUtils.degToRad(FOV / 2));
  const fitWidth = 11.2 / (tanH * aspect);      // outer moons swing off-screen near elongation
  const fitHeight = 13.5 / tanH;
  overviewDist = Math.max(32, aspect < 1 ? fitWidth : fitHeight);
}

const tmpV = new THREE.Vector3(), tmpV2 = new THREE.Vector3(), up = new THREE.Vector3(0, 1, 0);
function sph(dist, yaw, pitch) {
  return new THREE.Vector3(Math.sin(yaw) * Math.cos(pitch) * dist, Math.sin(pitch) * dist, Math.cos(yaw) * Math.cos(pitch) * dist);
}
function overviewPose() {
  return { pos: sph(overviewDist, rig.yaw, rig.pitch), target: new THREE.Vector3(0, -0.6, 0), shift: 0.085 };
}
function moonPose(i) {
  const m = moons[i], mp = m.mesh.getWorldPosition(new THREE.Vector3());
  const out = mp.clone().normalize();
  const side = new THREE.Vector3().crossVectors(up, out).normalize();
  const sgn = Math.sign(side.dot(sunDir)) || 1;
  let dir = out.clone().multiplyScalar(0.86).addScaledVector(side, 0.34 * sgn).addScaledVector(up, 0.2).normalize();
  dir.applyAxisAngle(up, rig.fYaw);
  const right = new THREE.Vector3().crossVectors(up, dir).normalize();
  dir.applyAxisAngle(right, -rig.fPitch);
  const dist = m.info.radius * 10.5 / Math.min(1, innerWidth / innerHeight * 2.1);
  return { pos: mp.clone().addScaledVector(dir, dist), target: mp, shift: -0.22 };
}
// Frame the whole disc with the Great Red Spot turned toward the lit, camera-facing side.
function jupiterPose() {
  const lat = THREE.MathUtils.degToRad(-22.5), lon = 0.9 + simTime * 0.0015 - spin;
  const grs = new THREE.Vector3(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon)).applyQuaternion(system.quaternion);
  let dir = grs.multiplyScalar(0.75).addScaledVector(sunDir, 0.45).addScaledVector(up, 0.32).normalize();
  dir.applyAxisAngle(up, rig.fYaw);
  const right = new THREE.Vector3().crossVectors(up, dir).normalize();
  dir.applyAxisAngle(right, -rig.fPitch);
  return { pos: dir.multiplyScalar(R * 4.5), target: new THREE.Vector3(0, -2.4, 0), shift: -0.22 };
}
function goalPose() {
  if (rig.focus === "jupiter") return jupiterPose();
  if (typeof rig.focus === "number") return moonPose(rig.focus);
  return overviewPose();
}

const camState = { pos: new THREE.Vector3(), target: new THREE.Vector3(), shift: -0.05 };
const ease = t => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

function startTransition(duration = 1.7) {
  rig.from = { pos: camState.pos.clone(), target: camState.target.clone(), shift: camState.shift };
  rig.t0 = performance.now(); rig.duration = reduceMotion ? 0.01 : duration;
}

// Glide the chosen moon along its orbit into sunlight, facing the overview camera.
function steerMoonIntoLight(i) {
  const m = moons[i];
  const inv = system.quaternion.clone().invert();
  const sunL = sunDir.clone().applyQuaternion(inv);
  const camL = sph(1, rig.yaw, rig.pitch).applyQuaternion(inv);
  const want = new THREE.Vector3(sunL.x * 0.55 + camL.x * 0.85, 0, sunL.z * 0.55 + camL.z * 0.85).normalize();
  const target = Math.atan2(want.z, want.x);
  let delta = target - m.phase; delta = Math.atan2(Math.sin(delta), Math.cos(delta));
  rig.steer = { i, start: m.phase, delta, t0: performance.now(), duration: reduceMotion ? 0.01 : 1.9 };
}

// ------------------------------------------------------------------ UI
const $ = s => document.querySelector(s);
const hero = $("#hero"), dock = $("#dock"), sheet = $("#sheet"), sheetBody = $("#sheet-body"), labelsEl = $("#labels");
$("#title").textContent = article.title;
$("#lede").textContent = article.lede || "";
$("#kicker").textContent = "观点交锋 · 深度阅读";

const labels = moons.map((m, i) => {
  if (m.decor) return null;
  const st = styleOf(m.camp.stance);
  const b = document.createElement("button");
  b.className = "tag";
  b.innerHTML = `<i style="background:${st.color}"></i><span>${esc(m.camp.label)}</span><em>${m.info.name}</em>`;
  b.addEventListener("click", e => { e.stopPropagation(); focusMoon(i); });
  labelsEl.appendChild(b);
  return b;
});

const chipsEl = $("#chips");
interactive.forEach(m => {
  const st = styleOf(m.camp.stance);
  const b = document.createElement("button");
  b.className = "chip";
  b.style.setProperty("--c", st.color);
  const who = (m.camp.holders || [])[0];
  b.innerHTML = `<span class="orb orb-${m.camp.moon}"></span><span class="chip-text"><b>${esc(m.camp.label)}</b><small>${m.info.name}${who ? " · " + esc(who) : ""}</small></span>`;
  b.addEventListener("click", () => focusMoon(m.index));
  chipsEl.appendChild(b);
});
$("#dispute-btn").addEventListener("click", () => focusJupiter());

const sourceById = Object.fromEntries((article.sources || []).map(s => [s.id, s]));
function citeHTML(ids) {
  const known = (ids || []).filter(id => sourceById[id]);
  if (!known.length) return "";
  return `<div class="cites">${known.map(id => `<span class="cite"><b>${esc(id)}</b>${esc(sourceById[id].site || sourceById[id].title)}</span>`).join("")}</div>`;
}

function renderMoonSheet(i) {
  const m = moons[i], c = m.camp, st = styleOf(c.stance);
  const prev = (i + campCount - 1) % campCount, next = (i + 1) % campCount;
  sheetBody.innerHTML = `
    <div class="sheet-top">
      <span class="moon-cap"><span class="orb orb-${c.moon}"></span>${m.info.name}<em>${m.info.latin}</em></span>
      <span class="count">${i + 1} / ${campCount}</span>
    </div>
    <span class="badge" style="--c:${st.color}">${st.text}</span>
    <h2>${esc(c.label)}</h2>
    ${(c.holders || []).length ? `<p class="holders">${c.holders.map(esc).join("、")}</p>` : ""}
    <p class="arg">${esc(c.argument)}</p>
    ${c.quote ? `<blockquote>“${esc(c.quote)}”${c.quoteBy ? `<cite>—— ${esc(c.quoteBy)}</cite>` : ""}</blockquote>` : ""}
    ${citeHTML(c.sources)}
    <div class="nav">
      <button data-go="${prev}">‹ ${esc(moons[prev].camp.label)}</button>
      <button data-go="${next}">${esc(moons[next].camp.label)} ›</button>
    </div>`;
  sheetBody.scrollTop = 0;
  sheetBody.querySelectorAll("[data-go]").forEach(b => b.addEventListener("click", () => focusMoon(+b.dataset.go)));
  sheet.style.setProperty("--accent", st.color);
}
function renderJupiterSheet() {
  const d = article.debate;
  sheetBody.innerHTML = `
    <div class="sheet-top"><span class="moon-cap"><span class="orb orb-jupiter"></span>木星<em>Jupiter</em></span></div>
    <span class="badge" style="--c:#e8c79a">核心争议</span>
    <p class="dispute">${esc(d.dispute)}</p>
    ${d.takeaway ? `<div class="takeaway"><b>你可以怎么看</b><p>${esc(d.takeaway)}</p></div>` : ""}
    <div class="camp-list">${interactive.map(m => `<button data-go="${m.index}" style="--c:${styleOf(m.camp.stance).color}"><i></i>${esc(m.camp.label)}<small>${m.info.name}</small></button>`).join("")}</div>`;
  sheetBody.scrollTop = 0;
  sheetBody.querySelectorAll("[data-go]").forEach(b => b.addEventListener("click", () => focusMoon(+b.dataset.go)));
  sheet.style.setProperty("--accent", "#e8c79a");
}

function setMode(mode) {
  rig.mode = mode;
  document.body.dataset.mode = mode;
}
function focusMoon(i) {
  if (rig.focus === i) return;
  const wasFocus = rig.focus !== null;
  rig.focus = i; rig.fYaw = 0; rig.fPitch = 0;
  rig.timeScaleGoal = 0.06;
  steerMoonIntoLight(i);
  startTransition(wasFocus ? 1.5 : 1.8);
  sheetBody.classList.remove("in"); void sheetBody.offsetWidth;
  renderMoonSheet(i);
  sheetBody.classList.add("in");
  setMode("focus");
}
function focusJupiter() {
  rig.focus = "jupiter"; rig.fYaw = 0; rig.fPitch = 0;
  rig.timeScaleGoal = 0.25;
  startTransition(1.8);
  sheetBody.classList.remove("in"); void sheetBody.offsetWidth;
  renderJupiterSheet();
  sheetBody.classList.add("in");
  setMode("focus");
}
function backToOverview() {
  rig.focus = null; rig.timeScaleGoal = 1;
  startTransition(1.6);
  setMode("overview");
}
$("#close").addEventListener("click", backToOverview);

// Swipe the sheet sideways to move between camps.
{
  let sx = 0, sy = 0;
  sheet.addEventListener("touchstart", e => { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }, { passive: true });
  sheet.addEventListener("touchend", e => {
    const dx = e.changedTouches[0].clientX - sx, dy = e.changedTouches[0].clientY - sy;
    if (typeof rig.focus === "number" && Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5)
      focusMoon((rig.focus + (dx < 0 ? 1 : campCount - 1)) % campCount);
  }, { passive: true });
}

// ------------------------------------------------------------------ pointer: drag to orbit, tap to select
const raycaster = new THREE.Raycaster(), ndc = new THREE.Vector2();
let drag = null;
canvas.addEventListener("pointerdown", e => { drag = { x: e.clientX, y: e.clientY, lx: e.clientX, ly: e.clientY, t: performance.now(), moved: 0 }; canvas.setPointerCapture(e.pointerId); });
canvas.addEventListener("pointermove", e => {
  if (!drag || rig.mode === "intro") return;
  const dx = e.clientX - drag.lx, dy = e.clientY - drag.ly;
  drag.lx = e.clientX; drag.ly = e.clientY; drag.moved += Math.abs(dx) + Math.abs(dy);
  if (rig.focus === null) {
    rig.yawVel = -dx * 0.0042; rig.pitchVel = dy * 0.0032;
    rig.yaw += rig.yawVel; rig.pitch = THREE.MathUtils.clamp(rig.pitch + rig.pitchVel, 0.06, 1.05);
  } else {
    rig.fYaw = THREE.MathUtils.clamp(rig.fYaw - dx * 0.004, -1.3, 1.3);
    rig.fPitch = THREE.MathUtils.clamp(rig.fPitch + dy * 0.003, -0.55, 0.65);
  }
});
canvas.addEventListener("pointerup", e => {
  if (!drag) return;
  const tap = drag.moved < 10 && performance.now() - drag.t < 450;
  drag = null;
  if (!tap || rig.mode === "intro") return;
  ndc.set((e.clientX / innerWidth) * 2 - 1, -(e.clientY / innerHeight) * 2 + 1);
  raycaster.setFromCamera(ndc, camera);
  const hits = raycaster.intersectObjects([...interactive.map(m => m.hit), jupiter], false);
  // Prefer a moon in front of Jupiter; fall back to the planet.
  const moonHit = hits.find(h => h.object.userData.index !== undefined);
  const jupHit = hits.find(h => h.object === jupiter);
  if (moonHit && (!jupHit || moonHit.distance < jupHit.distance + 0.5)) focusMoon(moonHit.object.userData.index);
  else if (jupHit) focusJupiter();
});

// ------------------------------------------------------------------ frame loop
function resize() {
  const w = innerWidth, h = innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  fitOverview();
}
addEventListener("resize", resize);
resize();

const clock = new THREE.Clock();
let simTime = 0, spin = -0.95, started = performance.now(), running = true, frameTimes = [];
const introFrom = { pos: sph(overviewDist * 2.6, 1.55, 0.62), target: new THREE.Vector3(0, 0, 0), shift: 0 };
camState.pos.copy(introFrom.pos); camState.target.copy(introFrom.target); camState.shift = 0;
rig.from = introFrom; rig.t0 = performance.now(); rig.duration = reduceMotion ? 0.01 : 2.8;
setMode("intro");
moons.forEach(m => (m.appear = reduceMotion ? 1 : 0));

const occluder = new THREE.Sphere(new THREE.Vector3(), R * 0.97);
const ray = new THREE.Ray();

function frame() {
  if (!running) return;
  requestAnimationFrame(frame);
  const now = performance.now();
  const dt = Math.min(clock.getDelta(), 0.05);
  const since = (now - started) / 1000;

  rig.timeScale += (rig.timeScaleGoal - rig.timeScale) * Math.min(1, dt * 2.2);
  const motion = reduceMotion ? 0.35 : 1;
  simTime += dt * motion;
  spin += dt * 0.01 * rig.timeScale * motion;

  // Exposure fade-in and staggered moon arrival during the intro.
  renderer.toneMappingExposure += (1.12 - renderer.toneMappingExposure) * Math.min(1, dt * 1.4);
  moons.forEach((m, i) => { if (since > 0.8 + i * 0.22) m.appear += (1 - m.appear) * Math.min(1, dt * 2.6); });

  // Orbits.
  moons.forEach(m => {
    m.phase += dt * (Math.PI * 2 / m.info.period) * rig.timeScale * motion;
    if (rig.steer && rig.steer.i === m.index) {
      const k = Math.min(1, (now - rig.steer.t0) / 1000 / rig.steer.duration);
      m.phase = rig.steer.start + rig.steer.delta * ease(k);
      if (k >= 1) rig.steer = null;
    }
    const a = m.info.orbit;
    m.mesh.position.set(Math.cos(m.phase) * a, 0, Math.sin(m.phase) * a);
    m.mesh.rotation.y = -m.phase + Math.PI / 2;          // tidally locked
    m.mesh.scale.setScalar(m.appear < 0.001 ? 0.001 : 1).multiply(m.camp.moon === "amalthea" ? new THREE.Vector3(1.3, 0.82, 0.78) : new THREE.Vector3(1, 1, 1));
    // While a moon is in focus, any other moon drifting between it and the camera shrinks away.
    let hideGoal = 0;
    if (typeof rig.focus === "number" && rig.focus !== m.index) {
      const t = moons[rig.focus], tp = t.mesh.getWorldPosition(new THREE.Vector3()), mp = m.mesh.getWorldPosition(new THREE.Vector3());
      const dT = tp.sub(camera.position), dM = mp.sub(camera.position), lT = dT.length(), lM = dM.length();
      const sep = dT.angleTo(dM);
      if (lM < lT + t.info.radius && sep < Math.atan(m.info.radius * 1.4 / lM) + Math.atan(t.info.radius * 1.8 / lT)) hideGoal = 1;
      if (lM < m.info.radius * 4) hideGoal = 1;
    }
    m.hide += (hideGoal - m.hide) * Math.min(1, dt * 4);
    m.mesh.scale.multiplyScalar(Math.max(0.001, ease(Math.min(1, m.appear)) * (1 - ease(m.hide))));
  });
  scene.updateMatrixWorld();

  moons.forEach((m, i) => {
    const wp = m.mesh.getWorldPosition(tmpV);
    moonUniformArray[i].set(wp.x, wp.y, wp.z, m.info.radius * Math.min(1, m.appear));
    m.mat.uniforms.uTime.value = simTime;
    m.mat.uniforms.uRot.value.setFromMatrix4(m.mesh.matrixWorld);
    // uRot carries scale for Amalthea; the shader normalizes.
    const selected = rig.focus === i;
    const hl = m.mat.uniforms.uHighlight;
    hl.value += ((selected ? 0.15 : rig.focus === null ? 0.55 : 0.0) - hl.value) * Math.min(1, dt * 3);
    const pulse = 0.55 + 0.25 * Math.sin(simTime * 2.2 + i);
    m.glow.material.opacity += (((rig.focus === null ? pulse : 0) * m.appear) - m.glow.material.opacity) * Math.min(1, dt * 3);
    const orbitGoal = rig.focus === null ? 0.16 * m.appear : selected ? 0.0 : 0.05;
    m.orbit.material.opacity += (orbitGoal - m.orbit.material.opacity) * Math.min(1, dt * 3);
  });
  plumeMat.uniforms.uOpacity.value = io.appear;
  ring.material.uniforms.uOpacity.value += ((rig.focus === null ? 0.16 : 0.0) - ring.material.uniforms.uOpacity.value) * Math.min(1, dt * 3);
  updatePlumes(dt * (0.4 + 0.6 * rig.timeScale) * motion);

  jupiterMat.uniforms.uTime.value = simTime;
  jupiterMat.uniforms.uSpin.value = spin;
  starMat.uniforms.uTime.value = simTime;

  // Overview inertia.
  if (!drag && rig.focus === null && rig.mode !== "intro") {
    rig.yawVel *= 0.94; rig.pitchVel *= 0.9;
    rig.yaw += rig.yawVel + dt * 0.012 * motion;          // slow drift keeps the scene alive
    rig.pitch = THREE.MathUtils.clamp(rig.pitch + rig.pitchVel, 0.06, 1.05);
  }

  // Camera: blend from the captured pose to the live goal, with a gentle arc.
  const goal = goalPose();
  const k = Math.min(1, (now - rig.t0) / 1000 / rig.duration), e = ease(k);
  // The title and camps fade in while the camera is still settling, not after it.
  if (rig.mode === "intro" && k > 0.55) setMode("overview");
  if (rig.from && k < 1) {
    camState.pos.lerpVectors(rig.from.pos, goal.pos, e);
    camState.pos.addScaledVector(up, Math.sin(Math.PI * e) * rig.from.pos.distanceTo(goal.pos) * 0.12);
    camState.target.lerpVectors(rig.from.target, goal.target, e);
    camState.shift = rig.from.shift + (goal.shift - rig.from.shift) * e;
  } else {
    if (rig.from) { rig.from = null; if (rig.mode === "intro") setMode("overview"); }
    camState.pos.lerp(goal.pos, Math.min(1, dt * 6));
    camState.target.lerp(goal.target, Math.min(1, dt * 6));
    camState.shift += (goal.shift - camState.shift) * Math.min(1, dt * 6);
  }
  camera.position.copy(camState.pos);
  camera.lookAt(camState.target);
  camera.setViewOffset(innerWidth, innerHeight, 0, -camState.shift * innerHeight, innerWidth, innerHeight);
  camera.updateProjectionMatrix();
  sky.position.copy(camera.position);

  // Labels follow their moons; hidden behind Jupiter, off-screen, or outside the overview.
  moons.forEach((m, i) => {
    const el = labels[i];
    if (!el) return;
    const wp = m.mesh.getWorldPosition(tmpV);
    const toMoon = tmpV2.copy(wp).sub(camera.position);
    const dist = toMoon.length();
    ray.set(camera.position, toMoon.normalize());
    const hitPoint = ray.intersectSphere(occluder, new THREE.Vector3());
    const occluded = hitPoint && camera.position.distanceTo(hitPoint) < dist;
    const p = wp.clone().addScaledVector(up, m.info.radius * 1.5 + 0.35).project(camera);
    const onScreen = p.z < 1 && Math.abs(p.x) < 0.98 && Math.abs(p.y) < 0.95;
    const show = rig.mode === "overview" && rig.focus === null && onScreen && !occluded && m.appear > 0.9;
    el.classList.toggle("show", show);
    if (onScreen) {
      const half = (el.offsetWidth || 120) / 2 + 8;
      const x = THREE.MathUtils.clamp(((p.x + 1) / 2) * innerWidth, half, innerWidth - half);
      el.style.transform = `translate(${x}px, ${((1 - p.y) / 2) * innerHeight}px) translate(-50%, -100%)`;
    }
  });

  renderer.render(scene, camera);

  // Adaptive resolution: step down if frames run long.
  frameTimes.push(dt);
  if (frameTimes.length >= 90) {
    const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length;
    frameTimes = [];
    if (avg > 1 / 50 && pixelRatio > 1.25) {
      pixelRatio = Math.max(1.25, pixelRatio - 0.25);
      renderer.setPixelRatio(pixelRatio); resize();
      starMat.uniforms.uPixelRatio.value = pixelRatio; plumeMat.uniforms.uPixelRatio.value = pixelRatio;
    }
  }
  $("#fps").textContent = `${Math.round(1 / Math.max(dt, 0.001))} fps · ${pixelRatio.toFixed(2)}x`;
}
frame();

const keepAlive = new URLSearchParams(location.search).has("keepalive");
document.addEventListener("visibilitychange", () => {
  running = keepAlive || !document.hidden;
  if (running) { clock.getDelta(); frame(); }
});
if (new URLSearchParams(location.search).has("fps")) document.body.classList.add("show-fps");

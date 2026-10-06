// GLSL for the Jupiter debate scene. Everything is procedural: no textures to download.

export const noise = /* glsl */ `
vec3 mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 mod289(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}
vec4 permute(vec4 x){return mod289(((x*34.0)+10.0)*x);}
vec4 taylorInvSqrt(vec4 r){return 1.79284291400159-0.85373472095314*r;}
float snoise(vec3 v){
  const vec2 C=vec2(1.0/6.0,1.0/3.0); const vec4 D=vec4(0.0,0.5,1.0,2.0);
  vec3 i=floor(v+dot(v,C.yyy)); vec3 x0=v-i+dot(i,C.xxx);
  vec3 g=step(x0.yzx,x0.xyz); vec3 l=1.0-g; vec3 i1=min(g.xyz,l.zxy); vec3 i2=max(g.xyz,l.zxy);
  vec3 x1=x0-i1+C.xxx; vec3 x2=x0-i2+C.yyy; vec3 x3=x0-D.yyy;
  i=mod289(i);
  vec4 p=permute(permute(permute(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));
  float n_=0.142857142857; vec3 ns=n_*D.wyz-D.xzx;
  vec4 j=p-49.0*floor(p*ns.z*ns.z); vec4 x_=floor(j*ns.z); vec4 y_=floor(j-7.0*x_);
  vec4 x=x_*ns.x+ns.yyyy; vec4 y=y_*ns.x+ns.yyyy; vec4 h=1.0-abs(x)-abs(y);
  vec4 b0=vec4(x.xy,y.xy); vec4 b1=vec4(x.zw,y.zw);
  vec4 s0=floor(b0)*2.0+1.0; vec4 s1=floor(b1)*2.0+1.0; vec4 sh=-step(h,vec4(0.0));
  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy; vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;
  vec3 p0=vec3(a0.xy,h.x); vec3 p1=vec3(a0.zw,h.y); vec3 p2=vec3(a1.xy,h.z); vec3 p3=vec3(a1.zw,h.w);
  vec4 norm=taylorInvSqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));
  p0*=norm.x; p1*=norm.y; p2*=norm.z; p3*=norm.w;
  vec4 m=max(0.5-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0); m=m*m;
  return 105.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));
}
float fbm3(vec3 p){ return 0.5*snoise(p)+0.25*snoise(p*2.03)+0.125*snoise(p*4.01); }
float fbm4(vec3 p){ return 0.5*snoise(p)+0.25*snoise(p*2.03)+0.125*snoise(p*4.01)+0.0625*snoise(p*8.07); }
`;

const outChunks = /* glsl */ `
#include <tonemapping_fragment>
#include <colorspace_fragment>
`;

// ---------------------------------------------------------------- Jupiter
export const jupiterVertex = /* glsl */ `
varying vec3 vObjN; varying vec3 vWorldPos; varying vec3 vWorldN;
void main(){
  vObjN = normalize(position);
  vec4 wp = modelMatrix * vec4(position,1.0);
  vWorldPos = wp.xyz;
  vWorldN = normalize(mat3(modelMatrix) * normal);
  gl_Position = projectionMatrix * viewMatrix * wp;
}`;

export const jupiterFragment = /* glsl */ `
uniform float uTime; uniform vec3 uSunDir; uniform vec4 uMoons[5]; uniform float uSpin; uniform float uFocus;
varying vec3 vObjN; varying vec3 vWorldPos; varying vec3 vWorldN;
${noise}
const float PI = 3.14159265;

// Zone/belt colours by planetographic latitude (radians), with wavy edges coming from latW.
vec3 bandColor(float lat){
  float d = degrees(lat);
  float a = abs(d);
  vec3 zoneCream = vec3(0.95,0.90,0.81);
  vec3 ezHaze    = vec3(0.94,0.80,0.60);
  vec3 belt      = vec3(0.67,0.41,0.25);
  vec3 beltDeep  = vec3(0.57,0.31,0.18);
  vec3 tempBelt  = vec3(0.75,0.58,0.43);
  vec3 zoneTan   = vec3(0.89,0.82,0.70);
  vec3 polar     = vec3(0.55,0.54,0.57);
  vec3 c = mix(ezHaze, zoneCream, smoothstep(4.5, 1.0, a) * 0.6);
  c = mix(c, d > 0.0 ? belt : beltDeep, smoothstep(6.5, 8.0, a));       // NEB / SEB
  c = mix(c, mix(belt, zoneCream, 0.45), smoothstep(11.5, 13.0, a) * (1.0 - smoothstep(14.0, 15.0, a)) * 0.5); // belt-internal light lane
  c = mix(c, zoneCream, smoothstep(17.0, 18.5, a));                       // tropical zones
  c = mix(c, beltDeep*1.05, smoothstep(23.0, 24.0, a) * (1.0 - smoothstep(25.5, 26.5, a)) * 0.7); // thin jet belts
  c = mix(c, tempBelt, smoothstep(27.0, 28.5, a));                        // temperate belts
  c = mix(c, zoneTan, smoothstep(33.0, 34.5, a));
  c = mix(c, tempBelt*0.94, smoothstep(39.5, 41.0, a));
  c = mix(c, zoneTan*0.94, smoothstep(44.0, 45.5, a));
  c = mix(c, tempBelt*0.9, smoothstep(49.0, 50.0, a) * (1.0 - smoothstep(52.0, 53.0, a)));
  c = mix(c, polar, smoothstep(54.0, 68.0, a));
  return c;
}

float ellipse(vec2 q, vec2 r){ return length(q / r); }
float wrapAngle(float a){ return mod(a + PI, 2.0*PI) - PI; }

void main(){
  vec3 n = normalize(vObjN);
  float lat = asin(clamp(n.y, -1.0, 1.0));
  float lon = atan(n.z, n.x) + uSpin;
  // Alternating zonal jets drift the cloud bands against each other.
  float jet = sin(lat*13.0)*0.7 + sin(lat*6.0 + 1.3)*0.3;
  float lonF = lon + uTime*0.012*jet;
  vec3 p = vec3(cos(lat)*cos(lonF), sin(lat), cos(lat)*sin(lonF));

  // Great Red Spot frame (southern tropical zone).
  float grsLat = radians(-22.5); float grsLon = 0.9 + uTime*0.0015;
  vec2 gq = vec2(wrapAngle(lon - grsLon)*cos(lat), lat - grsLat);
  float gr = ellipse(gq, vec2(0.215, 0.105));
  float wake = smoothstep(2.6, 0.9, ellipse(gq + vec2(0.32, 0.0), vec2(0.55, 0.12)));

  float w  = fbm4(p*vec3(2.2, 7.5, 2.2) + vec3(0.0, uTime*0.004, 0.0));
  float w2 = fbm3(p*vec3(6.0, 18.0, 6.0) + w*1.4 + uTime*0.006);
  float eddy = fbm3(p*vec3(16.0, 40.0, 16.0) + vec3(w2*2.2, w*1.5, uTime*0.01));
  float turb = (0.35 + 0.65*abs(sin(lat*9.0))) + wake*1.6;
  float latW = lat + (w*0.05 + w2*0.022 + eddy*0.009) * turb;
  vec3 col = bandColor(latW);

  // Fine parallel streaks, filaments and brightness texture.
  float fil = snoise(p*vec3(16.0, 90.0, 16.0) + w*3.0);
  col *= 0.92 + 0.08*fil + 0.07*w2 + 0.05*sin(latW*190.0 + w*7.0);
  col = mix(col, col*vec3(1.06,0.93,0.80), (0.5 + 0.5*snoise(p*2.6 + 4.0)) * 0.5);
  col *= 0.95 + 0.07*snoise(p*vec3(40.0, 160.0, 40.0) + eddy*4.0);
  col = pow(col, vec3(1.12));
  // Dark blue-grey festoons hanging off the north equatorial belt edge.
  float fest = smoothstep(0.35, 0.75, snoise(vec3(lonF*5.0, latW*30.0, uTime*0.01))) * smoothstep(9.0, 6.5, degrees(latW)) * smoothstep(2.0, 5.0, degrees(latW));
  col = mix(col, vec3(0.38,0.43,0.52), fest*0.55);
  // Polar chaos.
  float polarMask = smoothstep(55.0, 72.0, abs(degrees(lat)));
  col *= mix(1.0, 0.85 + 0.3*fbm4(n*9.0 + uTime*0.003), polarMask);

  // Great Red Spot: rotating vortex with a pale collar.
  float ang = atan(gq.y/0.105, gq.x/0.215);
  float swirl = ang + (1.0 - smoothstep(0.0, 1.1, gr)) * (uTime*0.35) + gr*2.6;
  float spiral = fbm3(vec3(cos(swirl)*gr*3.0, sin(swirl)*gr*3.0, uTime*0.02 + 7.0));
  vec3 grsCol = mix(vec3(0.70,0.27,0.15), vec3(0.86,0.50,0.32), smoothstep(0.0, 0.95, gr) + spiral*0.35);
  col = mix(col, vec3(0.95,0.90,0.82), smoothstep(1.35, 1.08, gr) * smoothstep(0.92, 1.08, gr) * 0.8);
  col = mix(col, grsCol, smoothstep(1.02, 0.86, gr));

  // String of white ovals at 40°S.
  for (int i = 0; i < 6; i++) {
    float olon = float(i)*1.05 + 0.4 + uTime*0.004;
    vec2 oq = vec2(wrapAngle(lon - olon)*cos(lat), lat - radians(-40.5));
    float oval = ellipse(oq, vec2(0.05, 0.03));
    col = mix(col, vec3(0.97,0.95,0.92), smoothstep(1.0, 0.55, oval));
  }

  // Lighting.
  vec3 N = normalize(vWorldN);
  vec3 V = normalize(cameraPosition - vWorldPos);
  float ndl = dot(N, uSunDir);
  float diffuse = smoothstep(-0.12, 0.28, ndl) * mix(0.32, 1.0, clamp(ndl, 0.0, 1.0));
  float mu = clamp(dot(N, V), 0.0, 1.0);
  float limb = pow(mu, 0.32);

  // Moon shadows (transits): soft umbra/penumbra from each satellite.
  float shadow = 1.0;
  for (int i = 0; i < 5; i++) {
    vec3 m = uMoons[i].xyz; float r = uMoons[i].w;
    vec3 toM = m - vWorldPos;
    float t = dot(toM, uSunDir);
    if (t > 0.0) {
      float dist = length(toM - uSunDir*t);
      shadow *= mix(0.08, 1.0, smoothstep(r*0.7, r*1.35, dist));
    }
  }

  vec3 lit = col * diffuse * limb * shadow;
  // Forward-scattering haze on the limb of the day side.
  float rim = pow(1.0 - mu, 3.0);
  lit += vec3(0.95,0.86,0.72) * rim * smoothstep(-0.2, 0.6, ndl) * 0.45;
  // Faint polar aurora on the night side.
  float night = smoothstep(0.05, -0.25, ndl);
  float aur = smoothstep(68.0, 80.0, abs(degrees(lat))) * (0.6 + 0.4*sin(uTime*1.7 + lon*7.0)) * (0.5 + 0.5*snoise(vec3(lon*6.0, lat*20.0, uTime*0.4)));
  lit += vec3(0.35,0.55,1.0) * aur * night * 0.55;
  // Earthshine-like ambient so the night side reads as a sphere.
  lit += col * 0.015;

  gl_FragColor = vec4(lit, 1.0);
  ${outChunks}
}`;

// Thin glowing atmosphere just outside the limb.
export const haloVertex = /* glsl */ `
varying vec3 vViewN; varying vec3 vWorldN;
void main(){
  vViewN = normalize(normalMatrix * normal);
  vWorldN = normalize(mat3(modelMatrix) * normal);
  gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0);
}`;
export const haloFragment = /* glsl */ `
uniform vec3 uSunDir; uniform float uK; uniform float uIntensity;
varying vec3 vViewN; varying vec3 vWorldN;
void main(){
  float g = clamp(-vViewN.z / uK, 0.0, 1.0);
  g = pow(g, 2.2);
  float lit = smoothstep(-0.35, 0.55, dot(normalize(vWorldN), uSunDir));
  vec3 c = mix(vec3(0.20,0.30,0.55)*0.25, vec3(0.75,0.82,1.0), lit);
  gl_FragColor = vec4(c * g * uIntensity, 1.0);
  ${outChunks}
}`;

// Jupiter's faint dusty ring, shadowed by the planet.
export const ringVertex = /* glsl */ `
varying vec3 vWorldPos; varying vec3 vLocal;
void main(){
  vLocal = position;
  vec4 wp = modelMatrix * vec4(position,1.0);
  vWorldPos = wp.xyz;
  gl_Position = projectionMatrix * viewMatrix * wp;
}`;
export const ringFragment = /* glsl */ `
uniform vec3 uSunDir; uniform vec3 uCenter; uniform float uR; uniform float uInner; uniform float uOuter; uniform float uOpacity;
varying vec3 vWorldPos; varying vec3 vLocal;
${noise}
void main(){
  float r = length(vLocal.xy);
  float t = (r - uInner) / (uOuter - uInner);
  if (t < 0.0 || t > 1.0) discard;
  float dens = 0.35 + 0.35*sin(t*90.0) + 0.3*sin(t*23.0 + 1.0);
  dens *= smoothstep(0.0, 0.08, t) * smoothstep(1.0, 0.55, t);
  dens += smoothstep(0.12, 0.0, abs(t - 0.62)) * 0.9;       // main ring
  dens *= 0.75 + 0.25*snoise(vec3(r*6.0, atan(vLocal.y, vLocal.x)*3.0, 0.0));
  // Planet shadow on the ring.
  vec3 toC = uCenter - vWorldPos; float tt = dot(toC, uSunDir);
  float sh = 1.0;
  if (tt > 0.0) { float d = length(toC - uSunDir*tt); sh = smoothstep(uR*0.98, uR*1.04, d); }
  vec3 V = normalize(cameraPosition - vWorldPos);
  float forward = pow(max(dot(-V, uSunDir), 0.0), 3.0);
  vec3 c = vec3(0.80,0.68,0.55) * dens * sh * (0.35 + 1.6*forward);
  gl_FragColor = vec4(c * uOpacity, 1.0);
  ${outChunks}
}`;

// ---------------------------------------------------------------- Moons
export const moonVertex = /* glsl */ `
varying vec3 vObjN; varying vec3 vWorldPos; varying vec3 vWorldN;
uniform float uLumpy;
${noise}
void main(){
  vec3 n = normalize(position);
  vec3 pos = position;
  if (uLumpy > 0.0) pos *= 1.0 + uLumpy * fbm3(n*1.6 + 3.0);
  vObjN = n;
  vec4 wp = modelMatrix * vec4(pos,1.0);
  vWorldPos = wp.xyz;
  vWorldN = normalize(mat3(modelMatrix) * normal);
  gl_Position = projectionMatrix * viewMatrix * wp;
}`;

export const moonFragment = /* glsl */ `
uniform int uKind; uniform float uTime; uniform vec3 uSunDir; uniform vec3 uJupiter; uniform float uJupiterR;
uniform vec3 uStance; uniform float uHighlight; uniform mat3 uRot;
varying vec3 vObjN; varying vec3 vWorldPos; varying vec3 vWorldN;
${noise}

// Europa's lineae: long, gently wandering great-circle bands of varying width.
float europaLines(vec3 p){
  float m = 0.0;
  for (int i = 0; i < 9; i++) {
    float fi = float(i);
    vec3 n = normalize(vec3(sin(fi*12.9898)*2.0 - 1.0, sin(fi*78.233)*1.6, cos(fi*37.719)*2.0 - 1.0));
    float d = abs(dot(p, n) + 0.035*snoise(p*2.2 + fi*3.1));
    float w = 0.006 + 0.012*fract(fi*0.618);
    m = max(m, (1.0 - smoothstep(w*0.4, w, d)) * (0.55 + 0.45*fract(fi*0.37 + 0.2)));
  }
  return m;
}

// 0 amalthea, 1 io, 2 europa, 3 ganymede, 4 callisto
float height(vec3 p){
  if (uKind == 1) return 0.25*fbm3(p*3.0);
  if (uKind == 2) return 0.12*europaLines(p) + 0.08*fbm3(p*6.0);
  if (uKind == 3) return 0.4*sin(dot(p, vec3(13.0, 4.0, 9.0)) + 4.0*fbm3(p*2.0)) * smoothstep(0.0, 0.3, fbm3(p*1.4 + 2.0)) + 0.3*fbm3(p*6.0);
  if (uKind == 4) { float c = snoise(p*9.0); float c2 = snoise(p*19.0 + 4.0); return -0.8*smoothstep(0.55, 0.75, c) + 0.5*smoothstep(0.75, 0.82, c) - 0.4*smoothstep(0.6, 0.75, c2) + 0.2*fbm3(p*5.0); }
  return 0.6*fbm3(p*2.5);
}

vec3 albedo(vec3 p){
  if (uKind == 1) { // Io: sulphur plains, diffuse red Pele rings, dark paterae, white SO2 frost
    float f = fbm4(p*2.0);
    vec3 c = mix(vec3(0.70,0.46,0.09), vec3(0.86,0.70,0.20), smoothstep(-0.35, 0.45, f));
    c = mix(c, vec3(0.74,0.40,0.12), smoothstep(0.1, 0.55, fbm3(p*2.6 + 5.0)) * 0.7);
    c = mix(c, vec3(0.62,0.64,0.30), smoothstep(0.3, 0.7, fbm3(p*3.3 + 21.0)) * 0.4);
    c = mix(c, vec3(0.90,0.86,0.70), smoothstep(0.35, 0.7, fbm3(p*2.2 + 40.0)) * smoothstep(0.7, 0.15, abs(p.y)) * 0.35);
    c = mix(c, vec3(0.45,0.32,0.20), smoothstep(0.55, 0.95, abs(p.y)) * 0.45);           // darker reddish poles
    // A few big volcanoes with broad, diffuse red plume deposits (Pele-like).
    float big = snoise(p*1.5 + 11.0) + 0.45*fbm3(p*4.5 + 2.0);
    c = mix(c, vec3(0.62,0.20,0.07), smoothstep(0.35, 0.75, big) * 0.65);
    c = mix(c, vec3(0.10,0.07,0.05), smoothstep(0.95, 1.02, big));
    // Scattered small dark paterae of varying size.
    float v = snoise(p*7.0 + 3.0); float v2 = snoise(p*15.0 + 9.0);
    c = mix(c, vec3(0.12,0.08,0.05), smoothstep(0.80, 0.86, v));
    c = mix(c, vec3(0.30,0.16,0.07), smoothstep(0.84, 0.9, v2) * 0.7);
    return c;
  }
  if (uKind == 2) { // Europa: bright ice cut by reddish-brown lineae and chaos terrain
    vec3 c = mix(vec3(0.84,0.81,0.76), vec3(0.93,0.93,0.95), smoothstep(-0.3, 0.4, fbm4(p*1.8)));
    c = mix(c, vec3(0.62,0.42,0.28), europaLines(p) * 0.8);
    // Fine background fractures and mottled reddish chaos terrain.
    float fine = 1.0 - smoothstep(0.0, 0.02, abs(snoise(p*7.0 + 3.0)));
    c = mix(c, vec3(0.70,0.56,0.45), fine * 0.25);
    c = mix(c, vec3(0.64,0.48,0.36), smoothstep(0.35, 0.7, fbm3(p*3.0 + 8.0)) * 0.45);
    return c;
  }
  if (uKind == 3) { // Ganymede: dark ancient terrain, bright grooved terrain, frosty poles
    float t = smoothstep(0.05, 0.35, fbm3(p*1.4 + 2.0));
    vec3 c = mix(vec3(0.27,0.24,0.21), vec3(0.58,0.54,0.49), t);
    c *= 0.88 + 0.24*fbm3(p*5.0 + 1.0);
    c *= 0.9 + 0.12*sin(dot(p, vec3(13.0, 4.0, 9.0)) + 4.0*fbm3(p*2.0)) * t;
    c = mix(c, vec3(0.80,0.80,0.83), smoothstep(0.7, 0.95, abs(p.y)) * 0.6);
    c = mix(c, vec3(0.88,0.86,0.82), smoothstep(0.84, 0.9, snoise(p*12.0 + 2.0)));
    return c;
  }
  if (uKind == 4) { // Callisto: dark, saturated with bright craters, Valhalla ring basin
    vec3 c = vec3(0.30,0.27,0.24) * (0.85 + 0.3*fbm3(p*3.0));
    float c1 = snoise(p*9.0); float c2 = snoise(p*19.0 + 4.0); float c3 = snoise(p*38.0 + 9.0);
    c = mix(c, vec3(0.75,0.72,0.66), smoothstep(0.76, 0.83, c1));
    c = mix(c, vec3(0.70,0.66,0.60), smoothstep(0.74, 0.82, c2) * 0.8);
    c = mix(c, vec3(0.62,0.58,0.52), smoothstep(0.72, 0.8, c3) * 0.6);
    float vd = acos(clamp(dot(p, normalize(vec3(0.6, 0.35, 0.72))), -1.0, 1.0));
    c = mix(c, vec3(0.60,0.56,0.50), (0.5 + 0.5*sin(vd*60.0)) * smoothstep(0.55, 0.0, vd) * 0.45);
    c = mix(c, vec3(0.82,0.78,0.72), smoothstep(0.1, 0.0, vd));
    return c;
  }
  // Amalthea: deep red, dusty
  return mix(vec3(0.52,0.25,0.15), vec3(0.72,0.42,0.26), smoothstep(-0.3, 0.5, fbm3(p*2.0 + 1.0)));
}

void main(){
  vec3 p = normalize(vObjN);
  vec3 N = normalize(vWorldN);
  // Bump from the height field (tangent-plane finite differences).
  vec3 t1 = normalize(cross(abs(p.y) < 0.99 ? vec3(0.0,1.0,0.0) : vec3(1.0,0.0,0.0), p));
  vec3 t2 = cross(p, t1);
  float e = 0.012;
  float h0 = height(p);
  float hx = height(normalize(p + t1*e)) - h0;
  float hy = height(normalize(p + t2*e)) - h0;
  vec3 bumpObj = normalize(p - (t1*hx + t2*hy) * 2.2);
  vec3 Nw = normalize(uRot * bumpObj);

  vec3 V = normalize(cameraPosition - vWorldPos);
  vec3 col = albedo(p);

  // Eclipse by Jupiter.
  vec3 toJ = uJupiter - vWorldPos; float tj = dot(toJ, uSunDir);
  float ecl = 1.0;
  if (tj > 0.0) { float d = length(toJ - uSunDir*tj); ecl = smoothstep(uJupiterR*0.97, uJupiterR*1.06, d); }

  float ndl = dot(Nw, uSunDir);
  float diffuse = clamp(ndl, 0.0, 1.0);
  diffuse = mix(diffuse, smoothstep(-0.05, 0.4, ndl), 0.25);
  // Warm light reflected off Jupiter's lit face.
  vec3 jDir = normalize(toJ);
  float jLit = 0.5 + 0.5*dot(-jDir, uSunDir);
  float jShine = clamp(dot(Nw, jDir), 0.0, 1.0) * jLit;
  vec3 lit = col * (diffuse * ecl * 0.9 + jShine * vec3(0.95,0.78,0.58) * 0.18 + 0.05);
  // Icy specular on Europa.
  if (uKind == 2) lit += pow(max(dot(reflect(-uSunDir, Nw), V), 0.0), 40.0) * 0.18 * ecl;
  // Opposition surge / sunlit rim.
  lit += col * pow(1.0 - clamp(dot(Nw, V), 0.0, 1.0), 4.0) * smoothstep(0.0, 0.5, ndl) * 0.35 * ecl;
  // Selection tint along the rim.
  lit += uStance * pow(1.0 - clamp(dot(N, V), 0.0, 1.0), 2.5) * uHighlight * 0.8;

  gl_FragColor = vec4(lit, 1.0);
  ${outChunks}
}`;

// ---------------------------------------------------------------- Sky
export const starVertex = /* glsl */ `
attribute float aSize; attribute float aPhase; attribute vec3 aColor;
uniform float uTime; uniform float uPixelRatio; uniform float uTwinkle;
varying vec3 vColor; varying float vAlpha;
void main(){
  vec4 mv = modelViewMatrix * vec4(position,1.0);
  float tw = 1.0 - uTwinkle * (0.5 + 0.5*sin(uTime*(1.2 + aPhase*2.3) + aPhase*40.0)) * 0.55;
  vAlpha = tw; vColor = aColor;
  gl_PointSize = aSize * uPixelRatio;
  gl_Position = projectionMatrix * mv;
}`;
export const starFragment = /* glsl */ `
varying vec3 vColor; varying float vAlpha;
void main(){
  vec2 q = gl_PointCoord - 0.5; float d = length(q);
  float core = smoothstep(0.5, 0.0, d);
  float a = pow(core, 2.6) * vAlpha;
  gl_FragColor = vec4(vColor * a, 1.0);
}`;

export const skyVertex = /* glsl */ `
varying vec3 vDir;
void main(){ vDir = normalize(position); gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`;
export const skyFragment = /* glsl */ `
varying vec3 vDir;
${noise}
void main(){
  vec3 d = normalize(vDir);
  vec3 bandN = normalize(vec3(0.35, 0.82, -0.45));
  float b = dot(d, bandN);
  float band = exp(-b*b*14.0);
  float n = fbm4(d*3.2);
  float dust = smoothstep(0.05, 0.45, fbm4(d*6.0 + 4.0));
  vec3 c = mix(vec3(0.05,0.04,0.10), vec3(0.16,0.13,0.20), 0.5 + 0.5*n);
  c += vec3(0.35,0.28,0.22) * band * (0.4 + 0.6*n) * (1.0 - dust*0.85) * 0.5;
  c += vec3(0.10,0.16,0.35) * pow(max(0.0, fbm3(d*1.5 + 9.0)), 2.0) * 0.6;
  c *= 0.32 + band * 0.25;
  gl_FragColor = vec4(c * 0.38, 1.0);
}`;

// Volcanic plume particles above Io.
export const plumeVertex = /* glsl */ `
attribute float aLife; uniform float uPixelRatio; uniform float uScale;
varying float vLife;
void main(){
  vLife = aLife;
  vec4 mv = modelViewMatrix * vec4(position,1.0);
  gl_PointSize = uScale * uPixelRatio * (1.0 + aLife*2.5) / max(0.5, -mv.z) * 9.0;
  gl_Position = projectionMatrix * mv;
}`;
export const plumeFragment = /* glsl */ `
varying float vLife; uniform float uOpacity;
void main(){
  float d = length(gl_PointCoord - 0.5);
  float a = smoothstep(0.5, 0.0, d) * (1.0 - vLife) * smoothstep(0.0, 0.08, vLife) * uOpacity;
  gl_FragColor = vec4(vec3(0.62,0.74,1.0) * a * 0.55, 1.0);
}`;

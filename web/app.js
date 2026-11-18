// Frontend for the Ambulance Dispatch demo.
// All dispatch logic (heap, KD-Tree, A*, Dijkstra, hash tables) runs in the Java server.
// This file: gets real hospitals for the chosen location (OpenStreetMap), draws the Leaflet map
// (hospital names, simulated traffic, ambulances, routes) and calls the server's /api/... endpoints.

const SEV = { 1: "Critical", 2: "Serious", 3: "Minor" };
const DEMO = { lat: 12.9716, lng: 77.5946, label: "Bengaluru (demo)" };
const OVERPASS = [                       // used straight from the browser if the Java server cannot reach OSM
  "https://overpass-api.de/api/interpreter",
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
];
const MAX_HOSPITALS = 40;
const STEPS = ["Queued", "En route", "On scene", "To hospital", "Handover", "Done"];

let state = null;          // last answer from the server
let live = true;           // is the clock running?
let busy = false;          // true while a new area is being built
let severity = 3;
let trackedId = null;      // call shown in the "Dispatch status" card

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (n, d = 1) => Number(n).toFixed(d);

// ================================================================ map
const map = L.map("map", { zoomControl: true }).setView([DEMO.lat, DEMO.lng], 13);
L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 19,
  attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
}).addTo(map);
L.control.scale({ imperial: false }).addTo(map);

const roadRenderer = L.canvas({ padding: 0.3 });
const trafficLayer = L.layerGroup().addTo(map);
const areaLayer = L.layerGroup().addTo(map);
const routeLayer = L.layerGroup().addTo(map);
const hospLayer = L.layerGroup().addTo(map);
const callLayer = L.layerGroup().addTo(map);
const ambLayer = L.layerGroup().addTo(map);
L.control.layers(null, {
  "Traffic (simulated)": trafficLayer, "Hospitals": hospLayer, "Ambulances": ambLayer, "Routes": routeLayer,
}, { position: "topright" }).addTo(map);

const ambMarkers = {};     // ambulance id -> marker (moved, not re-created, so tooltips stay smooth)
const hospMarkers = {};    // hospital id -> marker

const icon = (cls, html, size) =>
  L.divIcon({ className: "", html: `<div class="mk ${cls}" style="width:${size}px;height:${size}px">${html}</div>`,
              iconSize: [size, size], iconAnchor: [size / 2, size / 2], popupAnchor: [0, -size / 2] });

const pin = L.marker([DEMO.lat, DEMO.lng], { draggable: true, zIndexOffset: 2000,
  icon: L.divIcon({ className: "", html: '<div class="mk pin" style="width:28px;height:28px"><span>!</span></div>', iconSize: [28, 28], iconAnchor: [14, 30] }),
}).bindTooltip("Patient location – drag me").addTo(map);

map.on("click", (e) => { setPicking(false); movePin(e.latlng); });
pin.on("dragend", () => movePin(pin.getLatLng()));
pin.on("click", () => pin.openPopup());
map.on("zoomend", updateLabelVisibility);
pin.bindPopup(() => pinPopupHtml(), { offset: [0, -24], minWidth: 220 });

// Hospital name labels: all of them when zoomed in, only the 12 nearest at city zoom, none when far out
function updateLabelVisibility() {
  const z = map.getZoom();
  map.getContainer().classList.toggle("hide-labels", z < 12);
  map.getContainer().classList.toggle("few-labels", z < 15);
}

const insideArea = (ll) => state && state.ready && L.latLngBounds(state.bounds).contains(ll);

// Put the patient pin at ll. Outside the simulated area: offer to move the whole area there.
function movePin(ll, zoom) {
  ll = L.latLng(ll);
  if (state && state.ready && !insideArea(ll)) {
    const pop = L.popup({ minWidth: 220 }).setLatLng(ll).setContent(
      `<div class="pinpop"><b>Outside the simulated 10 km area</b>
        <div class="sub">Ambulances only drive inside the dashed box.</div>
        <button type="button" id="moveHere">Move the area here</button></div>`).openOn(map);
    $("moveHere").onclick = () => { map.closePopup(pop); setupAt(ll.lat, ll.lng, "the selected point"); };
    return;
  }
  pin.setLatLng(ll);
  if (zoom) map.setView(ll, zoom);
  showPinInfo();
  if (state && state.ready) renderHospitalList();     // distances change with the pin
  pin.openPopup();
}

// Pin popup: address + one-click request
function pinPopupHtml() {
  const sev = SEV[severity];
  return `<div class="pinpop"><b>Patient location</b>
    <div class="sub">${esc($("pinAddr").textContent)}</div>
    <button type="button" onclick="requestHere()">&#128657; Request ambulance here (${sev})</button></div>`;
}
window.requestHere = () => { pin.closePopup(); $("btnRequest").click(); };

// Show coordinates at once, then the street address (looked up on OpenStreetMap, ~10 m cache)
let addrToken = 0;
function showPinInfo() {
  const p = pin.getLatLng();
  $("pinInfo").textContent = `${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}`;
  $("pinAddr").textContent = "Finding the address…";
  const token = ++addrToken;
  clearTimeout(showPinInfo.timer);
  showPinInfo.timer = setTimeout(async () => {
    let text = "Address not available (the pin still works)";
    try {
      const r = await api(`/api/osm/reverse?lat=${p.lat}&lng=${p.lng}`, null, 10000);
      if (r.format === "photon") {
        const f = r.data.features[0]?.properties || {};
        text = [f.name, f.street && (f.housenumber ? f.housenumber + " " : "") + f.street, f.district || f.locality, f.city]
          .filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(", ") || text;
      } else {
        text = r.data.display_name.split(",").slice(0, 4).join(",");
      }
    } catch (e) { /* keep the fallback text */ }
    if (token !== addrToken) return;                  // the pin moved again meanwhile
    $("pinAddr").textContent = text;
    if (pin.isPopupOpen()) pin.setPopupContent(pinPopupHtml());
  }, 350);
}

// "Pick on map" mode: crosshair cursor + banner until the next click
function setPicking(on) {
  $("pickBanner").hidden = !on;
  $("btnPick").classList.toggle("on", on);
  map.getContainer().classList.toggle("picking", on);
}

function setStatus(html, isHtml = false) {
  if (isHtml) $("status").innerHTML = html; else $("status").textContent = html;
}

// ================================================================ helpers
async function api(path, params, timeoutMs = 15000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const opts = { signal: ctrl.signal };
    if (params) Object.assign(opts, { method: "POST", body: params,
      headers: { "Content-Type": "application/x-www-form-urlencoded" } });
    const r = await fetch(path, opts);
    const data = await r.json();
    if (!r.ok) throw new Error(data.error || r.statusText);
    return data;
  } finally { clearTimeout(timer); }
}

function distKm(a, b) {     // haversine (straight-line) distance between [lat,lng] points
  const R = 6371, t = Math.PI / 180;
  const dLat = (b[0] - a[0]) * t, dLng = (b[1] - a[1]) * t;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * t) * Math.cos(b[0] * t) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

const speedColor = (s) => s >= 50 ? "#2e7d32" : s >= 30 ? "#f9a825" : s >= 20 ? "#ef6c00" : "#c62828";

// ================================================================ real hospitals from OpenStreetMap
function fromNominatim(list) {
  return list.filter((e) => e.category === "amenity" && e.type === "hospital").map((e) => ({
    name: e.name || "Hospital",
    lat: +e.lat, lng: +e.lon,
    phone: (e.extratags && (e.extratags.phone || e.extratags["contact:phone"])) || "",
  }));
}
function fromPhoton(geo) {
  return geo.features.filter((f) => f.properties.osm_value === "hospital").map((f) => ({
    name: f.properties.name || "Hospital",
    lat: f.geometry.coordinates[1], lng: f.geometry.coordinates[0], phone: "",
  }));
}
function fromOverpass(data) {
  return data.elements.map((e) => ({
    name: (e.tags && (e.tags.name || e.tags["name:en"])) || "Hospital",
    lat: e.lat ?? e.center?.lat, lng: e.lon ?? e.center?.lon,
    phone: (e.tags && (e.tags.phone || e.tags["contact:phone"])) || "",
  })).filter((h) => h.lat != null && h.lng != null);
}

// Browser cache: an area you already visited loads instantly (kept 7 days, about 1 km precision)
const cacheKey = (lat, lng) => `hosp:${(+lat).toFixed(2)},${(+lng).toFixed(2)}`;
function readCache(lat, lng) {
  try {
    const v = JSON.parse(localStorage.getItem(cacheKey(lat, lng)));
    if (v && Date.now() - v.t < 7 * 864e5) return v.res;
  } catch (e) { /* storage blocked: just fetch again */ }
  return null;
}
function writeCache(lat, lng, res) {
  try { localStorage.setItem(cacheKey(lat, lng), JSON.stringify({ t: Date.now(), res })); } catch (e) { }
}

// Ask the Java server (Nominatim, then Overpass) AND the Overpass servers directly; first good answer wins.
async function fetchHospitals(lat, lng) {
  const cached = readCache(lat, lng);
  if (cached) return cached;
  const viaServer = api(`/api/osm/hospitals?lat=${lat}&lng=${lng}`, null, 15000).then((r) => ({
    source: r.source,
    list: r.format === "nominatim" ? fromNominatim(r.data) : r.format === "photon" ? fromPhoton(r.data) : fromOverpass(r.data) }));
  const query = `[out:json][timeout:12];nwr["amenity"="hospital"](around:5000,${lat},${lng});out center tags;`;
  const direct = OVERPASS.map(async (url) => {
    const ctrl = new AbortController();
    setTimeout(() => ctrl.abort(), 12000);
    const r = await fetch(url, { method: "POST", signal: ctrl.signal, body: "data=" + encodeURIComponent(query),
                                 headers: { "Content-Type": "application/x-www-form-urlencoded" } });
    if (!r.ok) throw new Error(url + " " + r.status);
    return { source: "OpenStreetMap (Overpass)", list: fromOverpass(await r.json()) };
  });
  const nonEmpty = (p) => p.then((x) => { if (!x.list.length) throw new Error("no hospitals"); return x; });
  try {
    const res = await Promise.any([viaServer, ...direct].map(nonEmpty));
    const seen = new Set();
    const list = res.list
      .filter((h) => !/veterinar|animal|pet /i.test(h.name))                       // not for people
      .filter((h) => { const k = h.name + Math.round(h.lat * 1e3) + Math.round(h.lng * 1e3); if (seen.has(k)) return false; seen.add(k); return true; })
      .sort((a, b) => distKm([lat, lng], [a.lat, a.lng]) - distKm([lat, lng], [b.lat, b.lng]))
      .slice(0, MAX_HOSPITALS);
    const out = { source: res.source, list };
    writeCache(lat, lng, out);
    return out;
  } catch (e) {
    console.warn("No live hospital data", e);
    return null;
  }
}

function hospitalParams(p, list) {
  for (const h of list) { p.append("hname", h.name); p.append("hlat", h.lat); p.append("hlng", h.lng); p.append("hphone", h.phone); }
  return p;
}
const cap = (s) => s[0].toUpperCase() + s.slice(1);
let setupToken = 0;        // a newer area choice cancels an older one that is still loading

// Build a new simulated city around (lat, lng).
// The map, roads and ambulances appear AT ONCE; real hospitals are added as soon as they arrive.
async function setupAt(lat, lng, label) {
  const token = ++setupToken;
  busy = true;
  setRequestReady(false, "Loading hospitals…");
  pin.setLatLng([lat, lng]);
  try {
    const cached = readCache(lat, lng);
    state = await api("/api/setup", cached
      ? hospitalParams(new URLSearchParams({ lat, lng, source: cached.source }), cached.list)
      : new URLSearchParams({ lat, lng, source: "loading", pending: 1 }));
    trackedId = null;
    await loadRoads();
    drawCity();
    render();
    map.fitBounds(state.bounds, { padding: [8, 8] });
    showPinInfo();
    busy = false;
    if (cached) {
      setStatus(`${cap(label)}: ${cached.list.length} real hospitals from ${cached.source} (saved earlier, instant).`);
      setRequestReady(true);
      return;
    }
    setStatus(`<span class="spin"></span> Map ready. Finding real hospitals near ${esc(label)}…`, true);

    let res = await fetchHospitals(lat, lng);
    if (token !== setupToken) return;                 // the user already chose another area
    let msg;
    if (res) {
      msg = `${cap(label)}: ${res.list.length} real hospitals found on ${res.source}.`;
    } else {
      const fb = await api("/api/fallback-hospitals");
      if (distKm([lat, lng], fb.center) < 15) {
        res = { source: "Offline sample list (approximate)", list: fb.hospitals };
        msg = `Could not reach OpenStreetMap, so the offline Bengaluru sample list is used.`;
      } else {
        res = { source: "No data", list: [] };
        msg = `Could not reach OpenStreetMap for ${label}. A placeholder hospital is used. Press Reset to try again.`;
      }
    }
    state = await api("/api/add-hospitals",
      hospitalParams(new URLSearchParams({ cityId: state.cityId, source: res.source }), res.list));
    if (token !== setupToken) return;
    drawHospitals();
    render();
    setStatus(msg);
    setRequestReady(true);
  } catch (e) {
    busy = false;
    setStatus("Could not talk to the Java server: " + e.message + ". Is WebServer running?");
  }
}

function setRequestReady(ok, text) {
  $("btnRequest").disabled = !ok;
  $("btnRequest").textContent = ok ? "Request ambulance" : text;
}

// ================================================================ drawing
async function loadRoads() {
  const r = await api("/api/roads");
  trafficLayer.clearLayers();
  for (const [a, b, c, d, speed] of r.roads) {
    L.polyline([[a, b], [c, d]], { renderer: roadRenderer, color: speedColor(speed), weight: 3, opacity: 0.55, interactive: false })
      .addTo(trafficLayer);
  }
}

function drawCity() {
  areaLayer.clearLayers(); ambLayer.clearLayers(); callLayer.clearLayers(); routeLayer.clearLayers();
  for (const k in ambMarkers) delete ambMarkers[k];
  L.rectangle(state.bounds, { color: "#455a64", weight: 1.5, dashArray: "6 6", fill: false, interactive: false }).addTo(areaLayer);
  drawHospitals();
}

function drawHospitals() {
  hospLayer.clearLayers();
  for (const k in hospMarkers) delete hospMarkers[k];
  // hospitals arrive sorted by distance from the centre: the first 12 keep their label at every zoom
  state.hospitals.forEach((h, i) => {
    const m = L.marker([h.lat, h.lng], { icon: icon("hosp", "H", 22), zIndexOffset: 300 }).addTo(hospLayer);
    const short = h.name.length > 30 ? h.name.slice(0, 29) + "…" : h.name;
    m.bindTooltip(esc(short), { permanent: true, direction: "right", offset: [12, 0], className: "hlabel" + (i >= 12 ? " far" : "") });
    m.bindPopup(() => hospitalPopup(h.id));
    hospMarkers[h.id] = m;
  });
  updateLabelVisibility();
}

function hospitalPopup(id) {
  const h = state.hospitals.find((x) => x.id === id);
  const p = pin.getLatLng();
  const dir = `https://www.openstreetmap.org/directions?engine=fossgis_osrm_car&route=${p.lat.toFixed(5)}%2C${p.lng.toFixed(5)}%3B${h.lat.toFixed(5)}%2C${h.lng.toFixed(5)}`;
  return `<b>${esc(h.name)}</b> <small>(${esc(h.id)})</small><br>` +
    `${fmt(distKm([p.lat, p.lng], [h.lat, h.lng]))} km from the pin (straight line)<br>` +
    `Simulated free beds: <b>${h.beds}/${h.capacity}</b>` +
    (h.phone ? `<br>Phone (OSM, may be outdated): <a href="tel:${esc(h.phone)}">${esc(h.phone)}</a>` : "") +
    `<br><a href="${dir}" target="_blank" rel="noopener">Real road directions on OpenStreetMap ↗</a>`;
}

function render() {
  if (!state || !state.ready) return;
  const free = state.ambulances.filter((a) => a.status === "AVAILABLE").length;
  $("chipClock").textContent = `${fmt(state.clock)} min`;
  $("chipFree").textContent = `${free} / ${state.ambulances.length}`;
  $("chipWait").textContent = state.queue.length;
  $("chipHosp").textContent = state.hospitals.length;
  $("chipTraffic").textContent = { LIGHT: "Light", NORMAL: "Normal", HEAVY: "Rush hour" }[state.traffic];
  $("qCount").textContent = state.queue.length;
  document.querySelectorAll("#trafficGroup button").forEach((b) => b.classList.toggle("on", b.dataset.level === state.traffic));

  // ambulances
  for (const a of state.ambulances) {
    let m = ambMarkers[a.id];
    if (!m) {
      m = ambMarkers[a.id] = L.marker([a.lat, a.lng], { zIndexOffset: 1000 }).addTo(ambLayer);
      m.on("click", () => { if (m.callId) track(m.callId); });
    }
    m.callId = a.callId;
    m.setLatLng([a.lat, a.lng]);
    const cls = a.status === "AVAILABLE" ? "amb" : "busy";
    if (m.cls !== cls) { m.setIcon(icon(cls, "&#128657;", 28)); m.cls = cls; }
    m.unbindTooltip().bindTooltip(`<b>${esc(a.id)}</b>: ${esc(a.phase)}${a.callId ? " – " + esc(a.callId) : ""}`);
  }
  // hospital markers grey out when full
  for (const h of state.hospitals) {
    const m = hospMarkers[h.id];
    if (m) { const full = h.beds === 0; if (m.full !== full) { m.setIcon(icon(full ? "hosp full" : "hosp", "H", 22)); m.full = full; } }
  }

  // active calls and their routes
  callLayer.clearLayers(); routeLayer.clearLayers();
  for (const c of state.calls) {
    if (c.status === "COMPLETED") continue;
    L.marker([c.lat, c.lng], { icon: icon("call", esc(c.id), 26), zIndexOffset: 900 })
      .bindTooltip(`${esc(c.id)} ${esc(c.name)} – ${SEV[c.severity]} – ${c.status === "WAITING" ? "waiting" : "dispatched"}`)
      .on("click", () => track(c.id)).addTo(callLayer);
    if (c.status === "DISPATCHED") {
      const sel = c.id === trackedId;
      L.polyline(c.route1, { color: "#fff", weight: sel ? 9 : 7, opacity: 0.9 }).addTo(routeLayer);
      L.polyline(c.route1, { color: "#ef6c00", weight: sel ? 5 : 4 }).addTo(routeLayer);
      L.polyline(c.route2, { color: "#fff", weight: sel ? 9 : 7, opacity: 0.9 }).addTo(routeLayer);
      L.polyline(c.route2, { color: "#1565c0", weight: sel ? 5 : 4, dashArray: "8 7" }).addTo(routeLayer);
    }
  }

  renderLists();
  renderTrack();
  $("log").textContent = state.log.join("\n");
}

function renderLists() {
  fill($("callList"), state.calls, "No calls yet. Use the form above to request one.", (c) =>
    `<li class="click${c.id === trackedId ? " sel" : ""}" data-call="${esc(c.id)}">
      <span class="pill p${c.severity}">${SEV[c.severity]}</span>
      <div class="grow"><b>${esc(c.id)}</b> ${esc(c.name)}
        <div class="sub">${c.status === "WAITING" ? "Waiting for a free ambulance" :
          `${esc(c.ambulance)} → ${esc(hospName(c.hospital))}`}</div></div>
      <span class="pill st">${c.status === "DISPATCHED" ? "Active" : c.status === "WAITING" ? "Queued" : "Done"}</span></li>`);

  fill($("queueList"), state.queue, "No calls waiting.", (q) =>
    `<li class="click" data-call="${esc(q.id)}"><div class="grow"><b>${esc(q.id)}</b> ${esc(q.name)}
      <div class="sub">since t = ${q.arrival} min</div></div><span class="pill p${q.severity}">${SEV[q.severity]}</span></li>`);

  fill($("ambList"), state.ambulances, "", (a) =>
    `<li class="click" data-amb="${esc(a.id)}"><span class="lg ${a.status === "AVAILABLE" ? "amb" : "busy"}">&#128657;</span>
      <div class="grow"><b>${esc(a.id)}</b> – ${esc(a.phase)}
        <div class="sub">${a.callId ? `Call ${esc(a.callId)}, free again at t = ${a.freeAt} min` : "Ready at its station"}</div></div></li>`);
  renderHospitalList();
}

function renderHospitalList() {
  $("hospSource").textContent = state.source;
  const p = pin.getLatLng();
  const list = state.hospitals.map((h) => ({ ...h, d: distKm([p.lat, p.lng], [h.lat, h.lng]) })).sort((a, b) => a.d - b.d);
  fill($("hospList"), list, "No hospitals.", (h) =>
    `<li class="click" data-hosp="${esc(h.id)}"><span class="lg hosp">H</span>
      <div class="grow"><b>${esc(h.name)}</b>
        <div class="sub">${fmt(h.d)} km · beds ${h.beds}/${h.capacity}${h.phone ? " · " + esc(h.phone) : ""}</div>
        <div class="beds"><i style="width:${(100 * h.beds / h.capacity).toFixed(0)}%"></i></div></div></li>`);
}

function fill(el, items, emptyText, fn) {
  el.innerHTML = items.length ? items.map(fn).join("") : `<li class="empty">${esc(emptyText)}</li>`;
}

const hospName = (id) => (state.hospitals.find((h) => h.id === id) || { name: id }).name;

function stepIndex(c, amb) {
  if (c.status === "WAITING") return 0;
  if (c.status === "COMPLETED") return 5;
  return { "Driving to patient": 1, "On scene": 2, "Driving to hospital": 3, "Hospital handover": 4 }[amb?.phase] ?? 1;
}

function renderTrack() {
  const c = trackedId && state.calls.find((x) => x.id === trackedId);
  $("trackCard").hidden = !c;
  if (!c) return;
  const amb = state.ambulances.find((a) => a.id === c.ambulance && a.callId === c.id);
  const step = stepIndex(c, amb);
  const steps = STEPS.map((s, i) => `<div class="${i < step ? "done" : i === step ? "now" : ""}">${s}</div>`).join("");
  let body;
  if (c.status === "WAITING") {
    const pos = state.queue.findIndex((q) => q.id === c.id) + 1;
    body = `<p>All ambulances are busy. This call is number <b>${pos}</b> of ${state.queue.length} in the priority queue
      and will be sent as soon as one is free.</p>`;
  } else {
    const toPatient = Math.max(0, c.arriveAt - state.clock);
    const toHosp = Math.max(0, c.dispatchTime + c.eta + 3 + c.toHospital - state.clock);
    const bigLabel = step === 1 ? `Ambulance arrives in` : step <= 3 ? `Reaches hospital in` : "";
    const big = step === 1 ? toPatient : toHosp;
    body = `${bigLabel ? `<div class="sub">${bigLabel}</div><div class="big-eta">${fmt(big)} min</div>` : ""}
      <div class="kv">
        <span>Ambulance</span><b>${esc(c.ambulance)}</b>
        <span>Road ETA</span><span>${c.eta} min (A* on the road graph)</span>
        <span>Hospital</span><b>${esc(hospName(c.hospital))}</b>
        <span>Patient → hospital</span><span>${c.toHospital} min (Dijkstra, nearest free bed)</span>
        <span>Waited in queue</span><span>${c.wait} min</span>
        <span>KD-Tree picks</span><span>${c.candidates.map(esc).join(", ")}</span>
      </div>`;
  }
  $("track").innerHTML = `<div class="trackhead"><b>${esc(c.id)}</b> ${esc(c.name)}
      <span class="pill p${c.severity}">${SEV[c.severity]}</span>
      <button class="ghost" id="zoomCall" style="margin-left:auto">Show on map</button></div>
    <div class="steps">${steps}</div>${body}`;
  $("zoomCall").onclick = () => {
    const pts = [[c.lat, c.lng], ...(c.route1 || []), ...(c.route2 || [])];
    map.fitBounds(pts, { padding: [40, 40], maxZoom: 16 });
  };
}

function track(id) { trackedId = id; render(); }

// ================================================================ buttons
document.querySelectorAll("#sevGroup button").forEach((b) => b.onclick = () => {
  severity = +b.dataset.sev;
  document.querySelectorAll("#sevGroup button").forEach((x) => x.classList.toggle("on", x === b));
});

$("btnRequest").onclick = async () => {
  const p = pin.getLatLng();
  $("btnRequest").disabled = true;
  try {
    const res = await api("/api/request", new URLSearchParams({ lat: p.lat, lng: p.lng, severity, name: $("pName").value.trim() || "Unknown" }));
    state = res.state;
    trackedId = res.callId;
    render();
    $("trackCard").scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (e) {
    setStatus("Request failed: " + e.message);
  } finally {
    $("btnRequest").disabled = false;
  }
};

// Go to a point: inside the current area just move the pin; otherwise build a new area there
function goTo(lat, lng, label) {
  const ll = L.latLng(lat, lng);
  if (insideArea(ll)) {
    movePin(ll, Math.max(map.getZoom(), 16));
    setStatus(`Patient pin moved to ${label}.`);
  } else {
    setupAt(lat, lng, label);
  }
}

function useMyLocation() {
  if (!navigator.geolocation) { setStatus("This browser cannot share its location. Search a place instead."); return; }
  setStatus("Asking the browser for your location… (allow it in the pop-up)");
  navigator.geolocation.getCurrentPosition(
    (pos) => goTo(pos.coords.latitude, pos.coords.longitude, "your location"),
    (err) => setStatus(`Location not available (${err.message}). Click the map or search an address instead.`),
    { enableHighAccuracy: true, timeout: 15000 });
}
$("btnMyLoc").onclick = useMyLocation;
$("btnHere").onclick = useMyLocation;
$("btnCentre").onclick = () => { setPicking(false); movePin(map.getCenter()); };
$("btnPick").onclick = () => setPicking($("pickBanner").hidden);
$("pickCancel").onclick = (e) => { e.stopPropagation(); setPicking(false); };
document.addEventListener("keydown", (e) => { if (e.key === "Escape") setPicking(false); });

// place search (Nominatim / Photon, through the Java server). Coordinates are used directly.
$("searchForm").onsubmit = async (e) => {
  e.preventDefault();
  const q = $("searchBox").value.trim();
  const box = $("searchResults");
  if (!q) return;
  const coords = q.match(/^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/);
  if (coords && Math.abs(+coords[1]) <= 90 && Math.abs(+coords[2]) <= 180) {
    box.hidden = true;
    goTo(+coords[1], +coords[2], `${(+coords[1]).toFixed(5)}, ${(+coords[2]).toFixed(5)}`);
    return;
  }
  box.hidden = false;
  box.innerHTML = `<li class="none">Searching…</li>`;
  try {
    // results near the current area come first (search is biased to the map's position)
    const c = map.getCenter();
    const r = await api(`/api/osm/search?q=${encodeURIComponent(q)}&lat=${c.lat}&lng=${c.lng}`, null, 15000);
    // Nominatim and Photon answer in different shapes; turn both into {lat, lon, name, display_name}
    const places = r.format === "photon"
      ? r.data.features.map((f) => {
          const p = f.properties;
          const name = p.name || p.city || p.state || "Place";
          return { lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0], name,
                   display_name: [name, p.city !== name ? p.city : "", p.state, p.country].filter(Boolean).join(", ") };
        })
      : r.data;
    // Start loading the first result's hospitals NOW, while the user reads the list (server caches it)
    if (places[0] && !insideArea(L.latLng(places[0].lat, places[0].lon)) && !readCache(places[0].lat, places[0].lon)) {
      fetch(`/api/osm/hospitals?lat=${places[0].lat}&lng=${places[0].lon}`).catch(() => {});
    }
    box.innerHTML = places.length ? places.map((p) =>
      `<li tabindex="0" data-lat="${esc(p.lat)}" data-lng="${esc(p.lon)}" data-name="${esc(p.name || p.display_name)}">${esc(p.display_name)}</li>`).join("")
      : `<li class="none">No place found for “${esc(q)}”.</li>`;
  } catch (err) {
    box.innerHTML = `<li class="none">Search failed: ${esc(err.message)}</li>`;
  }
};
$("searchResults").onclick = $("searchResults").onkeydown = (e) => {
  const li = e.target.closest("li[data-lat]");
  if (!li || (e.type === "keydown" && e.key !== "Enter")) return;
  $("searchResults").hidden = true;
  goTo(+li.dataset.lat, +li.dataset.lng, li.dataset.name);
};
document.addEventListener("click", (e) => { if (!$("searchForm").contains(e.target)) $("searchResults").hidden = true; });

// tabs
document.querySelectorAll(".tab").forEach((t) => t.onclick = () => {
  document.querySelectorAll(".tab").forEach((x) => x.classList.toggle("on", x === t));
  document.querySelectorAll(".tabpanel").forEach((p) => p.hidden = p.id !== "tab-" + t.dataset.tab);
});

// clicks inside the lists (one listener per list = event delegation)
document.querySelector(".sidebar").addEventListener("click", (e) => {
  const li = e.target.closest("li.click");
  if (!li || !state) return;
  if (li.dataset.call) track(li.dataset.call);
  if (li.dataset.amb) {
    const a = state.ambulances.find((x) => x.id === li.dataset.amb);
    map.setView([a.lat, a.lng], 16);
    if (a.callId) track(a.callId);
  }
  if (li.dataset.hosp) {
    const m = hospMarkers[li.dataset.hosp];
    map.setView(m.getLatLng(), 16);
    m.openPopup();
  }
});

// traffic
document.querySelectorAll("#trafficGroup button").forEach((b) => b.onclick = async () => {
  if (!state || !state.ready) return;
  state = await api("/api/traffic", new URLSearchParams({ level: b.dataset.level }));
  await loadRoads();
  render();
  setStatus(`Traffic set to ${b.textContent}. New dispatches use the new road speeds (edge weights).`);
});
$("showTraffic").onchange = (e) => e.target.checked ? trafficLayer.addTo(map) : trafficLayer.remove();
map.on("overlayadd overlayremove", (e) => { if (e.layer === trafficLayer) $("showTraffic").checked = e.type === "overlayadd"; });

// clock
$("btnLive").onclick = () => {
  live = !live;
  $("btnLive").innerHTML = live ? "&#10074;&#10074; Pause" : "&#9654; Play";
};
$("btnStep").onclick = async () => {
  if (!state || !state.ready) return;
  state = await api("/api/advance", new URLSearchParams({ minutes: 5 }));
  render();
};
$("btnReset").onclick = () => {
  if (!state || !state.ready) return;
  setupAt(state.center[0], state.center[1], "this area");
};

setInterval(async () => {
  if (!live || busy || !state || !state.ready) return;
  try {
    state = await api("/api/advance", new URLSearchParams({ minutes: $("speed").value }));
    render();
  } catch (e) { console.warn(e); }
}, 1000);

// ================================================================ start
(async () => {
  showPinInfo();
  try {
    state = await api("/api/state");
  } catch (e) {
    setStatus("Cannot reach the Java server. Start it with:  java -cp out WebServer");
    return;
  }
  if (state.ready && state.hospitals.length === 0) {
    setupAt(state.center[0], state.center[1], "this area");       // page was reloaded while hospitals were loading
  } else if (state.ready) {
    pin.setLatLng(state.center);
    await loadRoads();
    drawCity();
    render();
    map.fitBounds(state.bounds, { padding: [8, 8] });
    showPinInfo();
    setStatus(`Connected. Hospitals: ${state.source}.`);
    $("btnRequest").disabled = false;
  } else {
    setupAt(DEMO.lat, DEMO.lng, DEMO.label);
  }
})();

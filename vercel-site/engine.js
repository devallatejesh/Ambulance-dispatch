// engine.js - the SAME dispatch algorithms as the Java project (src/*.java), ported to JavaScript
// so the website can run entirely in the browser and be hosted on Vercel (which cannot run Java).
//
//   MinHeap        <- MinHeap.java        binary min-heap (calls, Dijkstra/A* frontier, events)
//   RoadNetwork    <- RoadNetwork.java    weighted graph (adjacency list) + simulated traffic
//   dijkstra/aStar <- PathFinder.java     shortest travel time
//   KDTree         <- KDTree.java         k-nearest free ambulances
//   DispatchSystem <- DispatchSystem.java hash tables (Map) + the dispatcher
//   Engine.api     <- WebServer.java      the same /api/... answers, but computed locally
(function () {
  "use strict";

  // Seeded random numbers (Java's java.util.Random is replaced by mulberry32)
  function rng(seed) {
    let a = seed >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const randInt = (r, n) => Math.floor(r() * n);

  // ======================================================================== MinHeap
  // Complete binary tree in an array: parent (i-1)/2, children 2i+1 and 2i+2. push/pop O(log n).
  class MinHeap {
    constructor(cmp) { this.a = []; this.cmp = cmp; }
    get size() { return this.a.length; }
    isEmpty() { return this.a.length === 0; }
    peek() { return this.a[0]; }
    push(item) {
      const a = this.a;
      a.push(item);
      let i = a.length - 1;
      while (i > 0) {                                   // sift up
        const p = (i - 1) >> 1;
        if (this.cmp(a[i], a[p]) >= 0) break;
        [a[i], a[p]] = [a[p], a[i]];
        i = p;
      }
    }
    pop() {
      const a = this.a;
      if (!a.length) return undefined;
      const top = a[0], last = a.pop();
      if (a.length) {
        a[0] = last;
        let i = 0;
        const n = a.length;
        for (;;) {                                      // sift down
          const l = 2 * i + 1, r = l + 1;
          let s = i;
          if (l < n && this.cmp(a[l], a[s]) < 0) s = l;
          if (r < n && this.cmp(a[r], a[s]) < 0) s = r;
          if (s === i) break;
          [a[i], a[s]] = [a[s], a[i]];
          i = s;
        }
      }
      return top;
    }
    sortedCopy() {                                      // priority order, heap unchanged
      const h = new MinHeap(this.cmp);
      h.a = this.a.slice();
      const out = [];
      while (!h.isEmpty()) out.push(h.pop());
      return out;
    }
  }

  // ======================================================================== RoadNetwork
  // Grid of intersections; every road stored in BOTH directions; weight = travel minutes.
  const SPEEDS = [20, 30, 40, 50, 60], MAX_SPEED = 60;
  const TRAFFIC_LEVELS = ["LIGHT", "NORMAL", "HEAVY"];

  class RoadNetwork {
    constructor(rows, cols, blockKm, seed) {
      this.rows = rows; this.cols = cols; this.blockKm = blockKm;
      const n = rows * cols;
      this.x = new Float64Array(n);
      this.y = new Float64Array(n);
      this.adj = Array.from({ length: n }, () => []);
      this.roads = [];
      this.traffic = "NORMAL";
      const r = rng(seed);
      for (let row = 0; row < rows; row++) {
        for (let c = 0; c < cols; c++) {
          const u = this.node(row, c);
          this.x[u] = c * blockKm;
          this.y[u] = row * blockKm;
          if (c + 1 < cols) this.addRoad(u, this.node(row, c + 1), SPEEDS[randInt(r, SPEEDS.length)]);
          if (row + 1 < rows) this.addRoad(u, this.node(row + 1, c), SPEEDS[randInt(r, SPEEDS.length)]);
        }
      }
    }
    addRoad(u, v, speed) {
      const minutes = (this.blockKm / speed) * 60;
      const f = { to: v, minutes }, b = { to: u, minutes };
      this.adj[u].push(f);
      this.adj[v].push(b);
      this.roads.push({ u, v, baseSpeed: speed, speed, f, b });
    }
    // LIGHT: +20 km/h. HEAVY: centre slowest + ~8% random jams at 10 km/h. Never above 60 km/h.
    applyTraffic(level, seed) {
      this.traffic = level;
      const r = rng(seed);
      const cx = ((this.cols - 1) * this.blockKm) / 2, cy = ((this.rows - 1) * this.blockKm) / 2;
      const maxD = Math.hypot(cx, cy);
      for (const road of this.roads) {
        let s = road.baseSpeed;
        if (level === "LIGHT") s = Math.min(60, s + 20);
        else if (level === "HEAVY") {
          const mx = (this.x[road.u] + this.x[road.v]) / 2 - cx, my = (this.y[road.u] + this.y[road.v]) / 2 - cy;
          const d = Math.hypot(mx, my) / maxD;
          s = Math.round(s * (0.3 + 0.7 * d));
          if (r() < 0.08) s = 10;
          s = Math.max(10, s);
        }
        road.speed = s;
        road.f.minutes = road.b.minutes = (this.blockKm / s) * 60;   // update BOTH directions
      }
    }
    node(r, c) { return r * this.cols + c; }
    size() { return this.rows * this.cols; }
    euclidKm(a, b) { return Math.hypot(this.x[a] - this.x[b], this.y[a] - this.y[b]); }
    heuristicMinutes(a, b) { return (this.euclidKm(a, b) / MAX_SPEED) * 60; }   // admissible
    nearestNode(xKm, yKm) {
      const c = Math.max(0, Math.min(this.cols - 1, Math.round(xKm / this.blockKm)));
      const r = Math.max(0, Math.min(this.rows - 1, Math.round(yKm / this.blockKm)));
      return this.node(r, c);
    }
  }

  // ======================================================================== PathFinder
  function buildPath(prev, target) {
    const path = [];
    for (let v = target; v !== -1; v = prev[v]) path.push(v);
    return path.reverse();
  }
  function search(g, src, isTarget, h) {               // shared loop of Dijkstra (h = 0) and A*
    const n = g.size();
    const dist = new Float64Array(n).fill(Infinity), prev = new Int32Array(n).fill(-1), done = new Uint8Array(n);
    dist[src] = 0;
    const heap = new MinHeap((p, q) => p.key - q.key);
    heap.push({ key: h(src), node: src });
    let expanded = 0;
    while (!heap.isEmpty()) {
      const u = heap.pop().node;
      if (done[u]) continue;
      done[u] = 1;
      expanded++;
      if (isTarget(u)) return { minutes: dist[u], path: buildPath(prev, u), target: u, expanded };
      for (const e of g.adj[u]) {                       // relax every road leaving u
        const nd = dist[u] + e.minutes;
        if (nd < dist[e.to]) {
          dist[e.to] = nd;
          prev[e.to] = u;
          heap.push({ key: nd + h(e.to), node: e.to });
        }
      }
    }
    return { minutes: Infinity, path: [], target: -1, expanded };
  }
  const dijkstra = (g, src, targets) => search(g, src, (u) => targets.has(u), () => 0);
  const aStar = (g, src, goal) => search(g, src, (u) => u === goal, (v) => g.heuristicMinutes(v, goal));

  // ======================================================================== KDTree
  class KDTree {
    constructor(points) { this.root = this.build(points.slice(), 0); }
    build(arr, depth) {
      if (!arr.length) return null;
      const axis = depth % 2;
      arr.sort((p, q) => (axis === 0 ? p.x - q.x : p.y - q.y));
      const mid = arr.length >> 1;                     // median = root -> balanced
      return { p: arr[mid], axis, left: this.build(arr.slice(0, mid), depth + 1), right: this.build(arr.slice(mid + 1), depth + 1) };
    }
    nearest(qx, qy, k, ok) {
      const best = [];                                  // sorted [{d2, p}]
      const visit = (node) => {
        if (!node) return;
        const p = node.p;
        if (ok(p)) {
          best.push({ d2: (p.x - qx) ** 2 + (p.y - qy) ** 2, p });
          best.sort((a, b) => a.d2 - b.d2);
          if (best.length > k) best.length = k;
        }
        const diff = node.axis === 0 ? qx - p.x : qy - p.y;
        visit(diff < 0 ? node.left : node.right);       // near side first
        if (best.length < k || diff * diff < best[best.length - 1].d2) visit(diff < 0 ? node.right : node.left);   // pruning
      };
      visit(this.root);
      return best.map((b) => b.p);
    }
  }

  // ======================================================================== DispatchSystem
  const ON_SCENE = 3, HANDOVER = 5, BED_RELEASE_AFTER = 45, K = 3;
  const SEV_NAME = ["", "CRITICAL", "SERIOUS", "MINOR"];
  const byUrgency = (a, b) => a.severity - b.severity || a.arrivalTime - b.arrivalTime || a.seq - b.seq;

  class DispatchSystem {
    constructor(g) {
      this.g = g;
      this.ambulances = new Map();    // HASH TABLES
      this.hospitals = new Map();
      this.calls = new Map();
      this.hospitalByNode = new Map();
      this.pending = new MinHeap(byUrgency);                                  // priority queue of calls
      this.events = new MinHeap((a, b) => a.time - b.time || a.seq - b.seq);  // simulation events
      this.tree = null; this.treeDirty = true;
      this.now = 0; this.seq = 0; this.callCounter = 0; this.availableCount = 0;
      this.log = [];
    }
    addAmbulance(id, node) {
      this.ambulances.set(id, { id, node, status: "AVAILABLE", callId: null, freeAt: 0, tripNodes: [], tripTimes: [] });
      this.availableCount++;
      this.treeDirty = true;
    }
    addHospital(id, name, node, beds) {
      if (this.hospitalByNode.has(node)) {
        for (const e of this.g.adj[node]) if (!this.hospitalByNode.has(e.to)) { node = e.to; break; }
      }
      const h = { id, name, node, capacity: beds, beds, lat: 0, lng: 0, phone: "" };
      this.hospitals.set(id, h);
      this.hospitalByNode.set(node, id);
      return h;
    }
    receiveCall(patientName, severity, node) {
      this.callCounter++;
      const id = "C" + String(this.callCounter).padStart(2, "0");
      const c = { id, patientName, severity, node, arrivalTime: this.now, seq: this.seq++, incidentX: this.g.x[node], incidentY: this.g.y[node],
                  status: "WAITING", ambulanceId: null, hospitalId: null, dispatchTime: -1, travelToPatient: 0, travelToHospital: 0,
                  routeToPatient: [], routeToHospital: [], candidates: [] };
      this.calls.set(id, c);
      this.pending.push(c);
      this.say(`CALL ${id} ${SEV_NAME[severity].padEnd(8)} at node ${String(node).padEnd(3)} -> queue length ${this.pending.size}`);
      this.tryDispatch();
      return c;
    }
    tryDispatch() { while (this.pending.size > 0 && this.availableCount > 0) this.dispatchNext(); }
    dispatchNext() {
      const g = this.g;
      const call = this.pending.pop();                                         // 1. most urgent call
      if (this.treeDirty) this.rebuildTree();
      const cands = this.tree.nearest(g.x[call.node], g.y[call.node], K, (p) => this.ambulances.get(p.id).status === "AVAILABLE");   // 2. KD-Tree
      let best = null, bestRoute = null;
      for (const p of cands) {                                                 // 3. A* from each candidate
        const a = this.ambulances.get(p.id);
        const r = aStar(g, a.node, call.node);
        call.candidates.push(`${a.id} (${r.minutes.toFixed(1)} min)`);
        if (!bestRoute || r.minutes < bestRoute.minutes) { best = a; bestRoute = r; }
      }
      const open = new Set();                                                  // 4. Dijkstra to a free bed
      for (const h of this.hospitals.values()) if (h.beds > 0) open.add(h.node);
      const overflow = open.size === 0;
      if (overflow) for (const h of this.hospitals.values()) open.add(h.node);
      const toHosp = dijkstra(g, call.node, open);
      const hosp = this.hospitals.get(this.hospitalByNode.get(toHosp.target));
      const t1 = bestRoute.minutes, t2 = toHosp.minutes;                       // 5. update records + events
      if (hosp.beds > 0) hosp.beds--;
      best.status = "EN_ROUTE";
      best.callId = call.id;
      this.availableCount--;
      best.freeAt = this.now + t1 + ON_SCENE + t2 + HANDOVER;
      this.events.push({ time: best.freeAt, seq: this.seq++, type: "FREE", id: best.id });
      this.events.push({ time: best.freeAt + BED_RELEASE_AFTER, seq: this.seq++, type: "BED", id: hosp.id });
      Object.assign(call, { status: "DISPATCHED", ambulanceId: best.id, hospitalId: hosp.id, dispatchTime: this.now,
                            travelToPatient: t1, travelToHospital: t2, routeToPatient: bestRoute.path, routeToHospital: toHosp.path });
      this.buildTrip(best, call);
      this.say(`DISPATCH ${call.id} -> ${best.id} (ETA ${t1.toFixed(1).padStart(4)} min) -> ${hosp.id} (+${t2.toFixed(1).padStart(4)} min); ` +
               `waited ${(call.dispatchTime - call.arrivalTime).toFixed(1)} min${overflow ? "  [all hospitals full!]" : ""}`);
    }
    rebuildTree() {
      const pts = [];
      for (const a of this.ambulances.values()) if (a.status === "AVAILABLE") pts.push({ x: this.g.x[a.node], y: this.g.y[a.node], id: a.id });
      this.tree = new KDTree(pts);
      this.treeDirty = false;
    }
    advanceTo(t) {
      while (!this.events.isEmpty() && this.events.peek().time <= t) {
        const e = this.events.pop();
        this.now = e.time;
        this.handle(e);
        this.tryDispatch();
      }
      if (t > this.now && isFinite(t)) this.now = t;
    }
    handle(e) {
      if (e.type === "FREE") {
        const a = this.ambulances.get(e.id), c = this.calls.get(a.callId);
        a.node = this.hospitals.get(c.hospitalId).node;
        Object.assign(a, { status: "AVAILABLE", callId: null, tripNodes: [], tripTimes: [] });
        this.availableCount++;
        this.treeDirty = true;                                                 // position changed -> rebuild KD-Tree
        c.status = "COMPLETED";
        this.say(`FREE ${a.id} available again at node ${a.node}`);
      } else if (e.type === "BED") {
        const h = this.hospitals.get(e.id);
        if (h.beds < h.capacity) h.beds++;
      }
    }
    buildTrip(a, c) {
      a.tripNodes = []; a.tripTimes = [];
      this.addLeg(a, c.routeToPatient, this.now);
      this.addLeg(a, c.routeToHospital, this.now + c.travelToPatient + ON_SCENE);
    }
    addLeg(a, path, start) {
      let t = start;
      path.forEach((v, i) => {
        if (i > 0) t += (this.g.adj[path[i - 1]].find((e) => e.to === v) || { minutes: 0 }).minutes;
        a.tripNodes.push(v);
        a.tripTimes.push(t);
      });
    }
    positionOf(a) {
      const g = this.g;
      if (!a.tripNodes.length || this.now <= a.tripTimes[0]) return [g.x[a.node], g.y[a.node]];
      for (let i = 1; i < a.tripNodes.length; i++) {
        const t0 = a.tripTimes[i - 1], t1 = a.tripTimes[i];
        if (this.now <= t1) {
          const u = a.tripNodes[i - 1], v = a.tripNodes[i], f = t1 > t0 ? (this.now - t0) / (t1 - t0) : 1;
          return [g.x[u] + f * (g.x[v] - g.x[u]), g.y[u] + f * (g.y[v] - g.y[u])];
        }
      }
      const end = a.tripNodes[a.tripNodes.length - 1];
      return [g.x[end], g.y[end]];
    }
    phaseOf(a) {
      if (a.status === "AVAILABLE") return "Available";
      const c = this.calls.get(a.callId);
      let t = this.now - c.dispatchTime;
      if (t < c.travelToPatient) return "Driving to patient";
      t -= c.travelToPatient;
      if (t < ON_SCENE) return "On scene";
      t -= ON_SCENE;
      if (t < c.travelToHospital) return "Driving to hospital";
      return "Hospital handover";
    }
    say(msg) { this.log.push(`[t=${this.now.toFixed(1).padStart(6)} min] ${msg}`); }
  }

  // ======================================================================== "server" (WebServer.java, locally)
  const GRID = 26, BLOCK_KM = 0.4, AMBULANCES = 8, KM_PER_DEG_LAT = 110.574;
  const DEMO = [12.9716, 77.5946];
  const FALLBACK = [                                   // offline sample list, APPROXIMATE positions
    ["Bowring and Lady Curzon Hospital", 12.9826, 77.6045], ["Victoria Hospital", 12.9636, 77.5733],
    ["Vani Vilas Hospital", 12.9617, 77.5741], ["St. Martha's Hospital", 12.9707, 77.5888],
    ["Mallya Hospital", 12.9675, 77.5958], ["HOSMAT Hospital", 12.9654, 77.6107], ["NIMHANS", 12.9406, 77.5963],
  ];
  let ds = null, minLat = 0, minLng = 0, kmPerDegLng = 1, centerLat = 0, centerLng = 0, source = "", cityId = 0;
  const lat = (yKm) => minLat + yKm / KM_PER_DEG_LAT;
  const lng = (xKm) => minLng + xKm / kmPerDegLng;
  const r1 = (v) => (isFinite(v) ? Math.round(v * 10) / 10 : null);
  const num = (p, k) => {
    const v = Number(p.get(k));
    if (!isFinite(v)) throw new Error("bad number for " + k);
    return v;
  };

  function setup(p) {
    cityId++;
    centerLat = num(p, "lat"); centerLng = num(p, "lng");
    source = p.get("source") || "";
    const half = ((GRID - 1) * BLOCK_KM) / 2;
    kmPerDegLng = 111.32 * Math.cos((centerLat * Math.PI) / 180);
    minLat = centerLat - half / KM_PER_DEG_LAT;
    minLng = centerLng - half / kmPerDegLng;
    ds = new DispatchSystem(new RoadNetwork(GRID, GRID, BLOCK_KM, 42));
    const r = rng(7);
    for (let i = 1; i <= AMBULANCES; i++) ds.addAmbulance("A" + i, randInt(r, ds.g.size()));
    if (p.get("pending") !== "1") addHospitals(p);
  }

  function addHospitals(p) {
    const g = ds.g, names = p.getAll("hname"), lats = p.getAll("hlat"), lngs = p.getAll("hlng"), phones = p.getAll("hphone");
    const r = rng(11);
    const count = Math.min(40, names.length, lats.length, lngs.length);
    for (let i = 0; i < count; i++) {
      const hl = Number(lats[i]), hg = Number(lngs[i]);
      const node = g.nearestNode((hg - minLng) * kmPerDegLng, (hl - minLat) * KM_PER_DEG_LAT);
      const h = ds.addHospital("H" + (i + 1), names[i].slice(0, 80), node, 5 + randInt(r, 16));   // SIMULATED beds
      Object.assign(h, { lat: hl, lng: hg, phone: phones[i] || "" });
    }
    if (!ds.hospitals.size) {
      const h = ds.addHospital("H1", "Placeholder hospital (no data found)", g.node(GRID >> 1, GRID >> 1), 10);
      Object.assign(h, { lat: centerLat, lng: centerLng });
    }
  }

  function routeLatLng(nodes, endLat, endLng) {
    return [...nodes.map((v) => [lat(ds.g.y[v]), lng(ds.g.x[v])]), [endLat, endLng]];
  }

  function callJson(c) {
    const o = { id: c.id, name: c.patientName, severity: c.severity, status: c.status, arrival: r1(c.arrivalTime),
                lat: lat(c.incidentY), lng: lng(c.incidentX) };
    if (c.status !== "WAITING") {
      const h = ds.hospitals.get(c.hospitalId);
      Object.assign(o, { ambulance: c.ambulanceId, hospital: c.hospitalId, dispatchTime: r1(c.dispatchTime),
        wait: r1(c.dispatchTime - c.arrivalTime), eta: r1(c.travelToPatient), toHospital: r1(c.travelToHospital),
        arriveAt: r1(c.dispatchTime + c.travelToPatient), candidates: c.candidates.slice(),
        route1: routeLatLng(c.routeToPatient, lat(c.incidentY), lng(c.incidentX)), route2: routeLatLng(c.routeToHospital, h.lat, h.lng) });
    }
    return o;
  }

  function state() {
    if (!ds) return { ready: false };
    const g = ds.g, last = g.size() - 1;
    return {
      ready: true, source, traffic: g.traffic, cityId, clock: r1(ds.now), center: [centerLat, centerLng],
      bounds: [[minLat, minLng], [lat(g.y[last]), lng(g.x[last])]], grid: GRID,
      ambulances: [...ds.ambulances.values()].map((a) => {
        const [x, y] = ds.positionOf(a);
        return { id: a.id, status: a.status, phase: ds.phaseOf(a), callId: a.callId, freeAt: r1(a.freeAt), lat: lat(y), lng: lng(x) };
      }),
      hospitals: [...ds.hospitals.values()].map((h) => ({ id: h.id, name: h.name, beds: h.beds, capacity: h.capacity, phone: h.phone, lat: h.lat, lng: h.lng })),
      calls: [...ds.calls.values()].reverse().map(callJson),
      queue: ds.pending.sortedCopy().map((c) => ({ id: c.id, name: c.patientName, severity: c.severity, arrival: r1(c.arrivalTime) })),
      log: ds.log.slice(-30).reverse(),
    };
  }

  function roads() {
    if (!ds) return { roads: [] };
    const g = ds.g;
    return { traffic: g.traffic, roads: g.roads.map((r) => [lat(g.y[r.u]), lng(g.x[r.u]), lat(g.y[r.v]), lng(g.x[r.v]), r.speed]) };
  }

  // ======================================================================== OpenStreetMap, straight from the browser
  // Three free services (no API key) are asked at the same time; the first good answer wins.
  // Answers are kept in a hash table (Map) and in localStorage, so a revisited area is instant.
  const osmCache = new Map();
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  function cacheGet(key) {
    if (osmCache.has(key)) return osmCache.get(key);
    try { const v = localStorage.getItem("osm:" + key); if (v) { const o = JSON.parse(v); osmCache.set(key, o); return o; } } catch (e) { }
    return null;
  }
  function cachePut(key, value) {
    osmCache.set(key, value);
    try { localStorage.setItem("osm:" + key, JSON.stringify(value)); } catch (e) { /* storage full or blocked */ }
  }
  async function getJson(url, ms, opts = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), ms);
    try {
      const r = await fetch(url, { ...opts, signal: ctrl.signal });
      if (!r.ok) throw new Error(url + " answered " + r.status);
      return await r.json();
    } finally { clearTimeout(timer); }
  }
  let nominatimNext = 0;                               // Nominatim rule: at most 1 request per second
  async function nominatim(path) {
    const wait = Math.max(0, nominatimNext - Date.now());
    nominatimNext = Date.now() + wait + 1100;
    if (wait) await sleep(wait);
    return getJson("https://nominatim.openstreetmap.org" + path, 8000);
  }
  function firstGood(tasks, ms) {
    return Promise.any([...tasks, sleep(ms).then(() => { throw new Error("timed out"); })]);
  }
  const need = (ok, what) => { if (!ok) throw new Error(what + ": no results"); };
  const f5 = (v) => v.toFixed(5);

  async function osmHospitals(la, lo) {
    const key = `h:${la.toFixed(2)},${lo.toFixed(2)}`;
    const hit = cacheGet(key);
    if (hit) return hit;
    const dLat = 5 / KM_PER_DEG_LAT, dLng = 5 / (111.32 * Math.cos((la * Math.PI) / 180));
    const query = `[out:json][timeout:12];nwr["amenity"="hospital"](around:5000,${f5(la)},${f5(lo)});out center tags;`;
    const res = await firstGood([
      nominatim(`/search?q=hospital&format=jsonv2&limit=50&bounded=1&extratags=1&viewbox=${f5(lo - dLng)},${f5(la + dLat)},${f5(lo + dLng)},${f5(la - dLat)}`)
        .then((d) => { need(d.length, "Nominatim"); return { source: "OpenStreetMap (Nominatim)", format: "nominatim", data: d }; }),
      getJson(`https://photon.komoot.io/api/?q=hospital&osm_tag=amenity:hospital&limit=50&bbox=${f5(lo - dLng)},${f5(la - dLat)},${f5(lo + dLng)},${f5(la + dLat)}`, 10000)
        .then((d) => { need(d.features && d.features.length, "Photon"); return { source: "OpenStreetMap (Photon)", format: "photon", data: d }; }),
      getJson("https://overpass-api.de/api/interpreter", 12000, { method: "POST", body: "data=" + encodeURIComponent(query),
        headers: { "Content-Type": "application/x-www-form-urlencoded" } })
        .then((d) => { need(d.elements && d.elements.length, "Overpass"); return { source: "OpenStreetMap (Overpass)", format: "overpass", data: d }; }),
      getJson("https://maps.mail.ru/osm/tools/overpass/api/interpreter", 12000, { method: "POST", body: "data=" + encodeURIComponent(query),
        headers: { "Content-Type": "application/x-www-form-urlencoded" } })
        .then((d) => { need(d.elements && d.elements.length, "Overpass"); return { source: "OpenStreetMap (Overpass)", format: "overpass", data: d }; }),
    ], 13000);
    cachePut(key, res);
    return res;
  }

  async function osmSearch(text, la, lo) {
    text = (text || "").trim();
    if (!text || text.length > 100) throw new Error("Type a place name");
    const near = isFinite(la) && isFinite(lo);
    const key = "s:" + text.toLowerCase() + (near ? `@${la.toFixed(1)},${lo.toFixed(1)}` : "");
    const hit = cacheGet(key);
    if (hit) return hit;
    const q = encodeURIComponent(text);
    const res = await firstGood([
      nominatim(`/search?format=jsonv2&limit=5&q=${q}` + (near ? `&viewbox=${(lo - 0.15).toFixed(3)},${(la + 0.15).toFixed(3)},${(lo + 0.15).toFixed(3)},${(la - 0.15).toFixed(3)}` : ""))
        .then((d) => { need(d.length, "Nominatim"); return { source: "Nominatim", format: "nominatim", data: d }; }),
      getJson(`https://photon.komoot.io/api/?limit=5&q=${q}` + (near ? `&lat=${la.toFixed(3)}&lon=${lo.toFixed(3)}` : ""), 10000)
        .then((d) => { need(d.features && d.features.length, "Photon"); return { source: "Photon", format: "photon", data: d }; }),
    ], 11000);
    cachePut(key, res);
    return res;
  }

  async function osmReverse(la, lo) {
    const key = `r:${la.toFixed(4)},${lo.toFixed(4)}`;
    const hit = cacheGet(key);
    if (hit) return hit;
    const res = await firstGood([
      nominatim(`/reverse?format=jsonv2&zoom=18&lat=${la.toFixed(6)}&lon=${lo.toFixed(6)}`)
        .then((d) => { need(d.display_name, "Nominatim"); return { source: "Nominatim", format: "nominatim", data: d }; }),
      getJson(`https://photon.komoot.io/reverse?lat=${la.toFixed(6)}&lon=${lo.toFixed(6)}`, 8000)
        .then((d) => { need(d.features && d.features.length, "Photon"); return { source: "Photon", format: "photon", data: d }; }),
    ], 9000);
    cachePut(key, res);
    return res;
  }

  // ======================================================================== the /api/... router
  async function api(path, params) {
    const u = new URL(path, location.href);
    const p = new URLSearchParams(u.search);
    if (params) for (const [k, v] of params) p.append(k, v);
    switch (u.pathname) {
      case "/api/osm/hospitals": return osmHospitals(num(p, "lat"), num(p, "lng"));
      case "/api/osm/search": return osmSearch(p.get("q"), Number(p.get("lat") ?? NaN), Number(p.get("lng") ?? NaN));
      case "/api/osm/reverse": return osmReverse(num(p, "lat"), num(p, "lng"));
      case "/api/state": return state();
      case "/api/roads": return roads();
      case "/api/fallback-hospitals":
        return { center: DEMO, hospitals: FALLBACK.map(([name, la, lo]) => ({ name, lat: la, lng: lo, phone: "" })) };
      case "/api/setup": setup(p); return state();
      case "/api/add-hospitals":
        if (ds && num(p, "cityId") === cityId && ds.hospitals.size === 0) { source = p.get("source") || ""; addHospitals(p); }
        return state();
      case "/api/request": {
        if (!ds) throw new Error("Set a location first");
        if (!ds.hospitals.size) throw new Error("Hospitals are still loading, try again in a moment");
        let sev = Math.trunc(num(p, "severity"));
        if (sev < 1 || sev > 3) sev = 3;
        const name = ((p.get("name") || "").trim() || "Unknown").slice(0, 40);
        const xKm = (num(p, "lng") - minLng) * kmPerDegLng, yKm = (num(p, "lat") - minLat) * KM_PER_DEG_LAT;
        const c = ds.receiveCall(name, sev, ds.g.nearestNode(xKm, yKm));
        c.incidentX = xKm;                               // keep the exact pin
        c.incidentY = yKm;
        return { callId: c.id, state: state() };
      }
      case "/api/advance":
        if (ds) ds.advanceTo(ds.now + Math.max(0, Math.min(120, num(p, "minutes"))));
        return state();
      case "/api/traffic": {
        const level = (p.get("level") || "").toUpperCase();
        if (ds && TRAFFIC_LEVELS.includes(level)) {
          ds.g.applyTraffic(level, 7 + Math.floor(ds.now));
          ds.say(`TRAFFIC set to ${level} - new routes use the new road speeds`);
        }
        return state();
      }
      default: throw new Error("not found: " + u.pathname);
    }
  }

  window.Engine = { api, MinHeap, RoadNetwork, KDTree, DispatchSystem, dijkstra, aStar };
})();

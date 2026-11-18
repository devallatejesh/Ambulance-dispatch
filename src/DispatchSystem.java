import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE DISPATCHER  -- Report Section 3.2 (Figure 1) and Section 4 "Dispatch algorithm"
 *
 * Puts all the data structures together:
 *   HASH TABLES   ambulances, hospitals, calls (by ID)      -> O(1) lookup and update
 *   MIN-HEAP      pending calls, key = (severity, arrival)  -> most urgent call first
 *   QUEUE (FIFO)  pending calls for the BASELINE policy     -> first come, first served
 *   KD-TREE       positions of available ambulances         -> k = 3 nearest candidates
 *   A*            candidate ambulance -> patient            -> pick the fastest by ROAD
 *   DIJKSTRA      patient -> nearest hospital with a free bed
 *   MIN-HEAP      event queue of the simulation (time ordered)
 *
 * Two policies are supported so they can be compared (Report Experiment 1):
 *   PROPOSED = heap + KD-Tree + A*
 *   BASELINE = FIFO queue + linear scan over every ambulance
 */
public class DispatchSystem {

    // Times from the report's implementation section (minutes)
    public static final double ON_SCENE = 3, HANDOVER = 5, BED_RELEASE_AFTER = 45;
    public static final int K_CANDIDATES = 3;

    /** Priority of a call: lower severity number first, then the older call, then lower seq. */
    public static final Comparator<EmergencyCall> BY_URGENCY = Comparator
            .comparingInt((EmergencyCall c) -> c.severity)
            .thenComparingDouble(c -> c.arrivalTime)
            .thenComparingLong(c -> c.seq);

    /** Something that will happen at a future simulated time. */
    private static class Event {
        final double time;
        final long seq;
        final String type;            // "CALL", "FREE" or "BED"
        final String id;              // call id / ambulance id / hospital id
        Event(double time, long seq, String type, String id) {
            this.time = time; this.seq = seq; this.type = type; this.id = id;
        }
    }

    public final RoadNetwork g;
    public final boolean proposed;

    // ---------- HASH TABLES (LinkedHashMap = HashMap that also remembers insertion order) ----------
    public final Map<String, Ambulance> ambulances = new LinkedHashMap<>();
    public final Map<String, Hospital> hospitals = new LinkedHashMap<>();
    public final Map<String, EmergencyCall> calls = new LinkedHashMap<>();
    private final Map<Integer, String> hospitalByNode = new HashMap<>();

    // ---------- QUEUES ----------
    private final MinHeap<EmergencyCall> pending = new MinHeap<>(BY_URGENCY);   // proposed
    private final ArrayDeque<EmergencyCall> fifo = new ArrayDeque<>();           // baseline
    private final MinHeap<Event> events = new MinHeap<>(Comparator
            .comparingDouble((Event e) -> e.time).thenComparingLong(e -> e.seq));

    // ---------- KD-TREE of AVAILABLE ambulances (rebuilt only when one becomes free again) ----------
    private KDTree tree;
    private boolean treeDirty = true;

    public double now = 0;            // simulated clock in minutes
    private long seq = 0;
    private int callCounter = 0;
    private int availableCount = 0;

    public boolean printLog = true;
    public final List<String> log = new ArrayList<>();

    public DispatchSystem(RoadNetwork g, boolean proposed) {
        this.g = g;
        this.proposed = proposed;
    }

    // =====================================================================================
    // Setup
    // =====================================================================================

    public Ambulance addAmbulance(String id, int node) {
        Ambulance a = new Ambulance(id, node);
        ambulances.put(id, a);                 // HASH TABLE insert, O(1)
        availableCount++;
        treeDirty = true;
        return a;
    }

    public Hospital addHospital(String id, String name, int node, int beds) {
        // Two hospitals cannot share one intersection in hospitalByNode, so move to a free neighbour.
        if (hospitalByNode.containsKey(node)) {
            for (RoadNetwork.Edge e : g.adj.get(node)) {
                if (!hospitalByNode.containsKey(e.to)) { node = e.to; break; }
            }
        }
        Hospital h = new Hospital(id, name, node, beds);
        hospitals.put(id, h);
        hospitalByNode.put(node, id);
        return h;
    }

    // =====================================================================================
    // Step 1-2 of Figure 1: call received, scored, stored in hash table, inserted in queue
    // =====================================================================================

    /** A call arriving NOW. It is stored, queued, and dispatched at once if an ambulance is free. */
    public EmergencyCall receiveCall(String patientName, int severity, int node) {
        callCounter++;
        String id = String.format("C%02d", callCounter);
        EmergencyCall c = new EmergencyCall(id, patientName, severity, node, now, seq++);
        c.incidentX = g.x[node];
        c.incidentY = g.y[node];
        calls.put(id, c);                                 // HASH TABLE
        if (proposed) pending.push(c); else fifo.add(c);  // MIN-HEAP  or  FIFO QUEUE
        say(String.format("CALL %s %-8s at node %-3d -> queue length %d",
                id, c.severityName(), node, waitingCount()));
        tryDispatch();
        return c;
    }

    /** For the simulation: a call that will arrive at a future time. */
    public void scheduleCall(String patientName, int severity, int node, double time) {
        // We create the call when the event fires, so we only remember the details here.
        String key = patientName + "|" + severity + "|" + node;
        events.push(new Event(time, seq++, "CALL", key));
    }

    public int waitingCount() { return proposed ? pending.size() : fifo.size(); }
    public int availableCount() { return availableCount; }

    /** Waiting calls in the order they will be served. */
    public List<EmergencyCall> waitingInOrder() {
        return proposed ? pending.sortedCopy() : new ArrayList<>(fifo);
    }

    // =====================================================================================
    // Step 3-6 of Figure 1: the dispatcher
    // =====================================================================================

    /** While there is a waiting call AND a free ambulance, dispatch. */
    public void tryDispatch() {
        while (waitingCount() > 0 && availableCount > 0) dispatchNext();
    }

    private void dispatchNext() {
        // 1. Extract the most urgent call (heap) or the oldest call (FIFO baseline)
        EmergencyCall call = proposed ? pending.pop() : fifo.poll();
        double qx = g.x[call.node], qy = g.y[call.node];

        Ambulance best = null;
        PathFinder.Result bestRoute = null;

        if (proposed) {
            // 2. KD-Tree: k = 3 nearest AVAILABLE ambulances (straight-line), no full scan
            if (treeDirty) rebuildTree();
            List<KDTree.Point> cands = tree.nearest(qx, qy, K_CANDIDATES,
                    p -> ambulances.get(p.id).status.equals("AVAILABLE"));
            // 3. A* from each candidate: keep the one with the smallest ROAD travel time
            for (KDTree.Point p : cands) {
                Ambulance a = ambulances.get(p.id);
                PathFinder.Result r = PathFinder.aStar(g, a.node, call.node);
                call.candidates.add(String.format("%s (%.1f min)", a.id, r.minutes));
                if (bestRoute == null || r.minutes < bestRoute.minutes) { best = a; bestRoute = r; }
            }
        } else {
            // BASELINE: linear scan over EVERY ambulance by straight-line distance, O(m)
            double bestD = Double.POSITIVE_INFINITY;
            for (Ambulance a : ambulances.values()) {
                if (!a.status.equals("AVAILABLE")) continue;
                double d = g.euclidKm(a.node, call.node);
                if (d < bestD) { bestD = d; best = a; }
            }
            bestRoute = PathFinder.aStar(g, best.node, call.node);
        }

        // 4. Dijkstra from the patient, stopping at the nearest hospital with a free bed
        Set<Integer> open = new HashSet<>();
        for (Hospital h : hospitals.values()) if (h.beds > 0) open.add(h.node);
        boolean overflow = open.isEmpty();
        if (overflow) for (Hospital h : hospitals.values()) open.add(h.node);   // all full: go to nearest anyway
        PathFinder.Result toHosp = PathFinder.dijkstra(g, call.node, open);
        Hospital hosp = hospitals.get(hospitalByNode.get(toHosp.target));

        // 5. Update the records (hash tables) and schedule the future events
        double t1 = bestRoute.minutes, t2 = toHosp.minutes;
        if (hosp.beds > 0) hosp.beds--;
        best.status = "EN_ROUTE";
        best.callId = call.id;
        availableCount--;
        double busy = t1 + ON_SCENE + t2 + HANDOVER;   // to patient, on scene, to hospital, handover
        best.freeAt = now + busy;
        events.push(new Event(best.freeAt, seq++, "FREE", best.id));
        events.push(new Event(best.freeAt + BED_RELEASE_AFTER, seq++, "BED", hosp.id));

        call.status = "DISPATCHED";
        call.ambulanceId = best.id;
        call.hospitalId = hosp.id;
        call.dispatchTime = now;
        call.travelToPatient = t1;
        call.travelToHospital = t2;
        call.routeToPatient = bestRoute.path;
        call.routeToHospital = toHosp.path;
        buildTrip(best, call);

        say(String.format("DISPATCH %s -> %s (ETA %4.1f min) -> %s (+%4.1f min); waited %.1f min%s",
                call.id, best.id, t1, hosp.id, t2, call.waitMinutes(),
                overflow ? "  [all hospitals full!]" : ""));
    }

    private void rebuildTree() {
        List<KDTree.Point> pts = new ArrayList<>();
        for (Ambulance a : ambulances.values()) {
            if (a.status.equals("AVAILABLE")) pts.add(new KDTree.Point(g.x[a.node], g.y[a.node], a.id));
        }
        tree = new KDTree(pts);
        treeDirty = false;
    }

    // =====================================================================================
    // Step 7: the simulated clock and the event queue
    // =====================================================================================

    /** Move the clock forward to time t, handling every event that happens before then. */
    public void advanceTo(double t) {
        while (!events.isEmpty() && events.peek().time <= t) {
            Event e = events.pop();
            now = e.time;
            handle(e);
            tryDispatch();
        }
        if (t > now && !Double.isInfinite(t)) now = t;
    }

    /** Run until nothing is left to happen (used by the experiments). */
    public void runToEnd() { advanceTo(Double.POSITIVE_INFINITY); }

    private void handle(Event e) {
        switch (e.type) {
            case "CALL": {
                String[] p = e.id.split("\\|");
                receiveCall(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                break;
            }
            case "FREE": {
                Ambulance a = ambulances.get(e.id);
                EmergencyCall c = calls.get(a.callId);
                a.node = hospitals.get(c.hospitalId).node;   // it is now at the hospital
                a.status = "AVAILABLE";
                a.callId = null;
                a.tripNodes.clear();
                a.tripTimes.clear();
                availableCount++;
                treeDirty = true;                            // position changed -> rebuild KD-Tree
                c.status = "COMPLETED";
                say(String.format("FREE %s available again at node %d", a.id, a.node));
                break;
            }
            case "BED": {
                Hospital h = hospitals.get(e.id);
                if (h.beds < h.capacity) h.beds++;
                break;
            }
        }
    }

    // =====================================================================================
    // Helpers for the website: where is an ambulance right now, and what is it doing?
    // =====================================================================================

    private void buildTrip(Ambulance a, EmergencyCall c) {
        a.tripNodes.clear();
        a.tripTimes.clear();
        double t = now;
        addLeg(a, c.routeToPatient, t);
        t += c.travelToPatient + ON_SCENE;
        addLeg(a, c.routeToHospital, t);
    }

    private void addLeg(Ambulance a, List<Integer> path, double startTime) {
        double t = startTime;
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) t += edgeMinutes(path.get(i - 1), path.get(i));
            a.tripNodes.add(path.get(i));
            a.tripTimes.add(t);
        }
    }

    private double edgeMinutes(int u, int v) {
        for (RoadNetwork.Edge e : g.adj.get(u)) if (e.to == v) return e.minutes;
        return 0;
    }

    /** Current position {xKm, yKm}, interpolated along the trip timeline. */
    public double[] positionOf(Ambulance a) {
        if (a.tripNodes.isEmpty() || now <= a.tripTimes.get(0)) return new double[]{g.x[a.node], g.y[a.node]};
        int last = a.tripNodes.size() - 1;
        for (int i = 1; i <= last; i++) {
            double t0 = a.tripTimes.get(i - 1), t1 = a.tripTimes.get(i);
            if (now <= t1) {
                int u = a.tripNodes.get(i - 1), v = a.tripNodes.get(i);
                double f = (t1 > t0) ? (now - t0) / (t1 - t0) : 1;
                return new double[]{g.x[u] + f * (g.x[v] - g.x[u]), g.y[u] + f * (g.y[v] - g.y[u])};
            }
        }
        int end = a.tripNodes.get(last);
        return new double[]{g.x[end], g.y[end]};
    }

    /** Human-readable phase of an ambulance's current trip. */
    public String phaseOf(Ambulance a) {
        if (a.status.equals("AVAILABLE")) return "Available";
        EmergencyCall c = calls.get(a.callId);
        double t = now - c.dispatchTime;
        if (t < c.travelToPatient) return "Driving to patient";
        t -= c.travelToPatient;
        if (t < ON_SCENE) return "On scene";
        t -= ON_SCENE;
        if (t < c.travelToHospital) return "Driving to hospital";
        return "Hospital handover";
    }

    private void say(String msg) {
        String line = String.format("[t=%6.1f min] %s", now, msg);
        log.add(line);
        if (printLog) System.out.println(line);
    }
}

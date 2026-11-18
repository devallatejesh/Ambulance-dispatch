import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * DSA CONCEPT: Weighted Graph using an ADJACENCY LIST  -- Report Table 1 and Table 3
 *
 * The city is a rows x cols grid of intersections (nodes).
 * Every node is joined to its right and down neighbour by a two-way road (edge).
 * Edge weight = TRAVEL TIME in minutes = length / speed, so the shortest path
 * is the FASTEST path, not just the shortest distance (Report Section 3.3).
 *
 * TRAFFIC (simulated): every road has a base speed of 20/30/40/50/60 km/h.
 * applyTraffic() changes the speeds (light / normal / heavy rush hour), which changes
 * the edge WEIGHTS, so Dijkstra and A* automatically pick different, faster routes.
 * Speeds never go above 60 km/h, so the A* heuristic stays admissible.
 *
 * Space: O(V + E).  Visiting all neighbours of a node: O(degree).
 */
public class RoadNetwork {

    /** One directed edge in the adjacency list: to which node, and how many minutes it takes. */
    public static class Edge {
        public final int to;
        public double minutes;          // changes when traffic changes
        Edge(int to, double minutes) { this.to = to; this.minutes = minutes; }
    }

    /** One two-way road = two directed edges that always share the same speed. */
    public static class Road {
        public final int u, v, baseSpeed;
        public int speed;               // current speed in km/h (after traffic)
        final Edge forward, backward;
        Road(int u, int v, int baseSpeed, Edge forward, Edge backward) {
            this.u = u; this.v = v; this.baseSpeed = baseSpeed; this.speed = baseSpeed;
            this.forward = forward; this.backward = backward;
        }
    }

    public static final double MAX_SPEED_KMPH = 60.0;
    public static final String[] TRAFFIC_LEVELS = {"LIGHT", "NORMAL", "HEAVY"};
    private static final int[] SPEEDS = {20, 30, 40, 50, 60};

    public final int rows, cols;
    public final double blockKm;            // distance between two neighbouring intersections
    public final double[] x, y;             // position of each node in km
    public final List<List<Edge>> adj;      // adjacency list: adj.get(u) = roads leaving u
    public final List<Road> roads = new ArrayList<>();
    public String traffic = "NORMAL";

    public RoadNetwork(int rows, int cols, double blockKm, long seed) {
        this.rows = rows;
        this.cols = cols;
        this.blockKm = blockKm;
        int n = rows * cols;
        x = new double[n];
        y = new double[n];
        adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        Random rnd = new Random(seed);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int u = node(r, c);
                x[u] = c * blockKm;
                y[u] = r * blockKm;
                if (c + 1 < cols) addRoad(u, node(r, c + 1), SPEEDS[rnd.nextInt(SPEEDS.length)]);
                if (r + 1 < rows) addRoad(u, node(r + 1, c), SPEEDS[rnd.nextInt(SPEEDS.length)]);
            }
        }
    }

    /** Adds a two-way road: stored in BOTH directions in the adjacency list. */
    private void addRoad(int u, int v, int speedKmph) {
        double minutes = blockKm / speedKmph * 60.0;
        Edge f = new Edge(v, minutes), b = new Edge(u, minutes);
        adj.get(u).add(f);
        adj.get(v).add(b);
        roads.add(new Road(u, v, speedKmph, f, b));
    }

    /**
     * Simulated traffic.
     *   LIGHT  : every road 20 km/h faster (max 60)
     *   NORMAL : the base speeds
     *   HEAVY  : rush hour - roads near the city centre slow down most, and about 8% of
     *            roads get a random jam at 10 km/h
     */
    public void applyTraffic(String level, long seed) {
        traffic = level;
        Random rnd = new Random(seed);
        double cx = (cols - 1) * blockKm / 2, cy = (rows - 1) * blockKm / 2;
        double maxD = Math.sqrt(cx * cx + cy * cy);
        for (Road road : roads) {
            int s = road.baseSpeed;
            if (level.equals("LIGHT")) {
                s = Math.min(60, s + 20);
            } else if (level.equals("HEAVY")) {
                double mx = (x[road.u] + x[road.v]) / 2 - cx, my = (y[road.u] + y[road.v]) / 2 - cy;
                double d = Math.sqrt(mx * mx + my * my) / maxD;      // 0 at centre, 1 at the corner
                s = (int) Math.round(s * (0.3 + 0.7 * d));           // centre is slowest
                if (rnd.nextDouble() < 0.08) s = 10;                 // random jam
                s = Math.max(10, s);
            }
            road.speed = s;
            double minutes = blockKm / s * 60.0;
            road.forward.minutes = minutes;                          // update BOTH directions
            road.backward.minutes = minutes;
        }
    }

    public int node(int r, int c)  { return r * cols + c; }
    public int size()              { return rows * cols; }
    public int roadCount()         { return roads.size(); }

    /** Straight-line distance between two nodes in km. */
    public double euclidKm(int a, int b) {
        double dx = x[a] - x[b], dy = y[a] - y[b];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * A* heuristic h(v): straight-line km at the MAXIMUM speed, in minutes.
     * No road is faster than 60 km/h and no road path is shorter than a straight line,
     * so this never over-estimates => it is ADMISSIBLE => A* stays optimal.
     */
    public double heuristicMinutes(int a, int b) {
        return euclidKm(a, b) / MAX_SPEED_KMPH * 60.0;
    }

    /** Snap any (x, y) position in km to the nearest intersection. */
    public int nearestNode(double xKm, double yKm) {
        int c = (int) Math.round(xKm / blockKm);
        int r = (int) Math.round(yKm / blockKm);
        c = Math.max(0, Math.min(cols - 1, c));
        r = Math.max(0, Math.min(rows - 1, r));
        return node(r, c);
    }
}

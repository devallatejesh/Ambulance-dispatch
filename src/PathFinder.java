import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * DSA CONCEPT: Shortest paths -- Dijkstra's algorithm and A* search  -- Report Sections 2.3 and 4
 *
 * Both use the same loop:
 *   - keep a MIN-HEAP of (priority, node)
 *   - repeatedly remove the closest unfinished node
 *   - "relax" its edges: if going through u gives a shorter time to v, update v
 *
 * Dijkstra: priority = time travelled so far,             cost O((V + E) log V)
 * A*      : priority = time so far + heuristic h(v),       expands fewer nodes, same answer
 */
public class PathFinder {

    /** Result of one search. */
    public static class Result {
        public final double minutes;        // total travel time (INFINITY if unreachable)
        public final List<Integer> path;    // nodes from source to target
        public final int target;            // which node we reached (useful for "nearest hospital")
        public final int expanded;          // how many nodes were taken out of the heap (work done)

        Result(double minutes, List<Integer> path, int target, int expanded) {
            this.minutes = minutes;
            this.path = path;
            this.target = target;
            this.expanded = expanded;
        }
    }

    /** One heap entry: a node and its priority. */
    private static class Entry {
        final double key;
        final int node;
        Entry(double key, int node) { this.key = key; this.node = node; }
    }

    /**
     * DIJKSTRA from src, stopping at the FIRST node that is in 'targets'.
     * Because Dijkstra finishes nodes in order of distance, the first target finished
     * is the NEAREST target. Used for: "nearest hospital that has a free bed".
     */
    public static Result dijkstra(RoadNetwork g, int src, Set<Integer> targets) {
        int n = g.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[src] = 0;

        MinHeap<Entry> heap = new MinHeap<>((p, q) -> Double.compare(p.key, q.key));
        heap.push(new Entry(0, src));
        int expanded = 0;

        while (!heap.isEmpty()) {
            int u = heap.pop().node;
            if (done[u]) continue;                 // old, out-of-date heap entry
            done[u] = true;
            expanded++;
            if (targets.contains(u)) return new Result(dist[u], buildPath(prev, u), u, expanded);
            for (RoadNetwork.Edge e : g.adj.get(u)) {        // relax every road leaving u
                double nd = dist[u] + e.minutes;
                if (nd < dist[e.to]) {
                    dist[e.to] = nd;
                    prev[e.to] = u;
                    heap.push(new Entry(nd, e.to));
                }
            }
        }
        return new Result(Double.POSITIVE_INFINITY, new ArrayList<>(), -1, expanded);
    }

    /**
     * A* from src to goal. Heap key = dist[v] + h(v), where h = straight-line time at max speed.
     * Used for: travel time from each candidate ambulance to the patient.
     */
    public static Result aStar(RoadNetwork g, int src, int goal) {
        int n = g.size();
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        dist[src] = 0;

        MinHeap<Entry> heap = new MinHeap<>((p, q) -> Double.compare(p.key, q.key));
        heap.push(new Entry(g.heuristicMinutes(src, goal), src));
        int expanded = 0;

        while (!heap.isEmpty()) {
            int u = heap.pop().node;
            if (done[u]) continue;
            done[u] = true;
            expanded++;
            if (u == goal) return new Result(dist[u], buildPath(prev, u), u, expanded);
            for (RoadNetwork.Edge e : g.adj.get(u)) {
                double nd = dist[u] + e.minutes;
                if (nd < dist[e.to]) {
                    dist[e.to] = nd;
                    prev[e.to] = u;
                    heap.push(new Entry(nd + g.heuristicMinutes(e.to, goal), e.to));   // f = g + h
                }
            }
        }
        return new Result(Double.POSITIVE_INFINITY, new ArrayList<>(), -1, expanded);
    }

    /** Walk the 'prev' links backwards from the target, then reverse to get source -> target. */
    private static List<Integer> buildPath(int[] prev, int target) {
        List<Integer> path = new ArrayList<>();
        for (int v = target; v != -1; v = prev[v]) path.add(v);
        Collections.reverse(path);
        return path;
    }
}

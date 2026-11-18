import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * The four runs from the report's "Sample Output" section, re-done in Java.
 * (Numbers will not be exactly the report's numbers: different language and random
 *  generator. The TRENDS are the same, which is what the report argues.)
 */
public class Experiments {

    /** Scenario from the report: 20 x 20 grid, 0.5 km blocks, 4 hospitals with 30 beds each. */
    public static DispatchSystem buildCity(boolean proposed, int ambulances, long seed) {
        RoadNetwork g = new RoadNetwork(20, 20, 0.5, 42);         // same roads every time
        DispatchSystem ds = new DispatchSystem(g, proposed);
        ds.addHospital("H1", "City General",     g.node(4, 4),   30);
        ds.addHospital("H2", "North Medical",    g.node(4, 15),  30);
        ds.addHospital("H3", "South Care",       g.node(15, 4),  30);
        ds.addHospital("H4", "Lakeside Hospital", g.node(15, 15), 30);
        Random rnd = new Random(seed);
        for (int i = 1; i <= ambulances; i++) ds.addAmbulance("A" + i, rnd.nextInt(g.size()));
        return ds;
    }

    /** make_calls(): random calls, exponential gaps (mean 2 min), severity 20% / 30% / 50%. */
    public static void scheduleRandomCalls(DispatchSystem ds, int count, long seed) {
        Random rnd = new Random(seed);
        double t = 0;
        for (int i = 1; i <= count; i++) {
            t += -2.0 * Math.log(1 - rnd.nextDouble());              // exponential, mean 2.0
            double p = rnd.nextDouble();
            int sev = p < 0.2 ? 1 : (p < 0.5 ? 2 : 3);
            ds.scheduleCall("Patient" + i, sev, rnd.nextInt(ds.g.size()), t);
        }
    }

    // ---------------------------------------------------------------- 1. dispatch trace
    public static void traceDemo() {
        System.out.println("=== 1. Dispatch trace (small scenario: 8 calls, 3 ambulances) ===");
        DispatchSystem ds = buildCity(true, 3, 7);
        scheduleRandomCalls(ds, 8, 11);
        ds.runToEnd();
    }

    // ---------------------------------------------------------------- 2. heap vs FIFO
    public static void compareQueues() {
        System.out.println("=== 2. Priority queue vs first-come-first-served (200 calls, 10 ambulances, mean of 3 runs) ===");
        double[][] res = new double[2][4];
        for (int run = 0; run < 3; run++) {
            for (int policy = 0; policy < 2; policy++) {
                DispatchSystem ds = buildCity(policy == 1, 10, 100 + run);
                ds.printLog = false;
                scheduleRandomCalls(ds, 200, 500 + run);
                ds.runToEnd();
                double[] sum = new double[4];
                int[] cnt = new int[4];
                for (EmergencyCall c : ds.calls.values()) {
                    sum[c.severity] += c.responseMinutes(); cnt[c.severity]++;
                    sum[0] += c.responseMinutes();          cnt[0]++;
                }
                for (int s = 0; s < 4; s++) res[policy][s] += (cnt[s] == 0 ? 0 : sum[s] / cnt[s]) / 3.0;
            }
        }
        System.out.printf("%-34s %9s %9s %9s %9s%n", "Policy", "Critical", "Serious", "Minor", "All");
        String[] names = {"Baseline (FIFO + linear scan)", "Proposed (heap + KD-tree + A*)"};
        for (int p = 0; p < 2; p++) {
            System.out.printf("%-34s %9.1f %9.1f %9.1f %9.1f%n", names[p], res[p][1], res[p][2], res[p][3], res[p][0]);
        }
        System.out.println("(mean response time in minutes = queue wait + travel to patient)");
    }

    // ---------------------------------------------------------------- 3. KD-Tree vs linear
    public static void kdTreeVsLinear() {
        System.out.println("=== 3. Nearest-ambulance search: linear scan vs KD-tree ===");
        Random rnd = new Random(1);
        for (int n : new int[]{1000, 10000, 100000}) {
            List<KDTree.Point> pts = new ArrayList<>();
            for (int i = 0; i < n; i++) pts.add(new KDTree.Point(rnd.nextDouble() * 100, rnd.nextDouble() * 100, "A" + i));
            long b0 = System.nanoTime();
            KDTree tree = new KDTree(pts);
            double buildMs = (System.nanoTime() - b0) / 1e6;

            int q = 200;
            double[][] qs = new double[q][2];
            for (int i = 0; i < q; i++) { qs[i][0] = rnd.nextDouble() * 100; qs[i][1] = rnd.nextDouble() * 100; }

            int mismatches = 0;
            long t0 = System.nanoTime();
            String[] lin = new String[q];
            for (int i = 0; i < q; i++) {                         // LINEAR SCAN: look at every point
                double best = Double.POSITIVE_INFINITY;
                for (KDTree.Point p : pts) {
                    double d = (p.x - qs[i][0]) * (p.x - qs[i][0]) + (p.y - qs[i][1]) * (p.y - qs[i][1]);
                    if (d < best) { best = d; lin[i] = p.id; }
                }
            }
            double linMs = (System.nanoTime() - t0) / 1e6 / q;

            long t1 = System.nanoTime();
            for (int i = 0; i < q; i++) {                         // KD-TREE query
                String id = tree.nearest(qs[i][0], qs[i][1], 1, p -> true).get(0).id;
                if (!id.equals(lin[i])) mismatches++;
            }
            double kdMs = (System.nanoTime() - t1) / 1e6 / q;

            System.out.printf("N = %6d: linear %8.3f ms/query | KD-tree %7.4f ms/query | speed-up %6.0fx | build %6.1f ms | wrong answers %d%n",
                    n, linMs, kdMs, linMs / kdMs, buildMs, mismatches);
        }
    }

    // ---------------------------------------------------------------- 4. Dijkstra vs A*
    public static void dijkstraVsAStar() {
        System.out.println("=== 4. Routing: Dijkstra vs A* (40 x 40 grid = 1600 intersections) ===");
        RoadNetwork g = new RoadNetwork(40, 40, 0.5, 3);
        Random rnd = new Random(9);
        int pairs = 100, different = 0;
        long dExp = 0, aExp = 0, dNs = 0, aNs = 0;
        for (int i = 0; i < pairs; i++) {
            int s = rnd.nextInt(g.size()), t = rnd.nextInt(g.size());
            HashSet<Integer> target = new HashSet<>();
            target.add(t);
            long t0 = System.nanoTime();
            PathFinder.Result d = PathFinder.dijkstra(g, s, target);
            long t1 = System.nanoTime();
            PathFinder.Result a = PathFinder.aStar(g, s, t);
            long t2 = System.nanoTime();
            dNs += t1 - t0; aNs += t2 - t1;
            dExp += d.expanded; aExp += a.expanded;
            if (Math.abs(d.minutes - a.minutes) > 1e-9) different++;
        }
        System.out.printf("Dijkstra: %7.1f nodes expanded/query, %6.3f ms/query%n", dExp / (double) pairs, dNs / 1e6 / pairs);
        System.out.printf("A*      : %7.1f nodes expanded/query, %6.3f ms/query%n", aExp / (double) pairs, aNs / 1e6 / pairs);
        System.out.printf("Routes with different travel time: %d of %d (A* is optimal)%n", different, pairs);
    }
}

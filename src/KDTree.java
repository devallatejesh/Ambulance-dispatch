import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * DSA CONCEPT: 2-D KD-Tree for nearest-neighbour search  -- Report Sections 2.5 and 4
 *
 * A binary tree that splits points by x at even levels and by y at odd levels.
 * Build: sort by the current axis, take the MEDIAN as the root, recurse on both halves
 *        -> balanced tree, O(n log^2 n) as coded.
 * k-nearest query: go down the NEAR side first; visit the FAR side only if the
 *        splitting line is closer than the k-th best distance found so far (PRUNING).
 *        -> O(log n) expected instead of scanning all n ambulances.
 */
public class KDTree {

    /** A point stored in the tree (an ambulance position). */
    public static class Point {
        public final double x, y;
        public final String id;
        public Point(double x, double y, String id) { this.x = x; this.y = y; this.id = id; }
    }

    private static class Node {
        Point p;
        int axis;              // 0 = split by x, 1 = split by y
        Node left, right;
    }

    private final Node root;
    private final int count;

    public KDTree(List<Point> points) {
        Point[] arr = points.toArray(new Point[0]);
        count = arr.length;
        root = build(arr, 0, arr.length, 0);
    }

    public int size() { return count; }

    private Node build(Point[] arr, int lo, int hi, int depth) {
        if (lo >= hi) return null;
        int axis = depth % 2;
        Comparator<Point> byAxis = (axis == 0)
                ? Comparator.comparingDouble((Point p) -> p.x)
                : Comparator.comparingDouble((Point p) -> p.y);
        Arrays.sort(arr, lo, hi, byAxis);
        int mid = (lo + hi) / 2;                       // median becomes this subtree's root
        Node node = new Node();
        node.p = arr[mid];
        node.axis = axis;
        node.left = build(arr, lo, mid, depth + 1);
        node.right = build(arr, mid + 1, hi, depth + 1);
        return node;
    }

    /**
     * Returns up to k points nearest to (qx, qy), closest first.
     * 'ok' lets the caller skip points (e.g. ambulances that are no longer AVAILABLE).
     */
    public List<Point> nearest(double qx, double qy, int k, Predicate<Point> ok) {
        List<double[]> best = new ArrayList<>();        // sorted list of {dist^2, index into found}
        List<Point> found = new ArrayList<>();
        visit(root, qx, qy, k, ok, best, found);
        List<Point> out = new ArrayList<>();
        for (double[] b : best) out.add(found.get((int) b[1]));
        return out;
    }

    private void visit(Node node, double qx, double qy, int k, Predicate<Point> ok,
                       List<double[]> best, List<Point> found) {
        if (node == null) return;
        Point p = node.p;
        if (ok.test(p)) {
            double d2 = (p.x - qx) * (p.x - qx) + (p.y - qy) * (p.y - qy);
            found.add(p);
            best.add(new double[]{d2, found.size() - 1});
            best.sort(Comparator.comparingDouble(b -> b[0]));
            while (best.size() > k) best.remove(best.size() - 1);    // keep only the k best
        }
        double diff = (node.axis == 0) ? qx - p.x : qy - p.y;
        Node near = diff < 0 ? node.left : node.right;
        Node far  = diff < 0 ? node.right : node.left;
        visit(near, qx, qy, k, ok, best, found);
        if (best.size() < k || diff * diff < best.get(best.size() - 1)[0]) {
            visit(far, qx, qy, k, ok, best, found);                  // PRUNING TEST
        }
    }
}

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DSA CONCEPT: Binary Min-Heap (Priority Queue)  -- Report Table 3, Section 2.4
 *
 * A complete binary tree stored in an array:
 *   parent of i  = (i - 1) / 2
 *   children of i = 2i + 1 and 2i + 2
 * The smallest item (by the Comparator) is always at index 0 (the root).
 *
 * Used in THREE places, exactly like the report says:
 *   1. the emergency-call queue   (key = severity, then arrival time)
 *   2. the frontier of Dijkstra and A*  (key = distance)
 *   3. the event queue of the simulation (key = event time)
 *
 * push  -> O(log n)   (sift-up)
 * pop   -> O(log n)   (sift-down)
 * peek  -> O(1)
 */
public class MinHeap<T> {

    private final ArrayList<T> a = new ArrayList<>();
    private final Comparator<T> cmp;

    public MinHeap(Comparator<T> cmp) {
        this.cmp = cmp;
    }

    public int size()        { return a.size(); }
    public boolean isEmpty() { return a.isEmpty(); }
    public T peek()          { return a.isEmpty() ? null : a.get(0); }

    /** Insert: put the item at the end, then move it UP while it is smaller than its parent. */
    public void push(T item) {
        a.add(item);
        int i = a.size() - 1;
        while (i > 0) {                               // sift up
            int parent = (i - 1) / 2;
            if (cmp.compare(a.get(i), a.get(parent)) >= 0) break;
            swap(i, parent);
            i = parent;
        }
    }

    /** Extract-min: take the root, move the last item to the root, then move it DOWN. */
    public T pop() {
        if (a.isEmpty()) return null;
        T top = a.get(0);
        T last = a.remove(a.size() - 1);
        if (!a.isEmpty()) {
            a.set(0, last);
            int i = 0, n = a.size();
            while (true) {                            // sift down
                int l = 2 * i + 1, r = 2 * i + 2, s = i;
                if (l < n && cmp.compare(a.get(l), a.get(s)) < 0) s = l;
                if (r < n && cmp.compare(a.get(r), a.get(s)) < 0) s = r;
                if (s == i) break;
                swap(i, s);
                i = s;
            }
        }
        return top;
    }

    /** Returns the items in priority order WITHOUT changing this heap (pops a copy). */
    public List<T> sortedCopy() {
        MinHeap<T> copy = new MinHeap<>(cmp);
        copy.a.addAll(a);                             // an array that is already a heap stays a heap
        List<T> out = new ArrayList<>();
        while (!copy.isEmpty()) out.add(copy.pop());
        return out;
    }

    private void swap(int i, int j) {
        T t = a.get(i);
        a.set(i, a.get(j));
        a.set(j, t);
    }
}

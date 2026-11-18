import java.util.List;
import java.util.Scanner;

/**
 * CONSOLE PROGRAM -- menu-driven Ambulance Dispatch System.
 *
 * Run:  javac -d out src/*.java      then      java -cp out Main
 *
 * City: 20 x 20 grid (400 intersections, 0.5 km blocks), 4 hospitals, 5 ambulances.
 * Row/col 0..19 pick an intersection; node number = row * 20 + col.
 */
public class Main {

    private static final Scanner in = new Scanner(System.in);

    public static void main(String[] args) {
        DispatchSystem ds = Experiments.buildCity(true, 5, 7);
        System.out.println("====================================================");
        System.out.println("  AMBULANCE DISPATCH SYSTEM (DSA-2 student project)");
        System.out.println("  Simulation only - in a real emergency call 108/112");
        System.out.println("====================================================");

        while (true) {
            System.out.printf("%nClock: t = %.1f min | waiting calls: %d | free ambulances: %d/%d%n",
                    ds.now, ds.waitingCount(), ds.availableCount(), ds.ambulances.size());
            System.out.println(" 1. Show city, ambulances and hospitals");
            System.out.println(" 2. Receive a new emergency call");
            System.out.println(" 3. Show waiting queue (priority order from the min-heap)");
            System.out.println(" 4. Advance the clock (ambulances finish trips)");
            System.out.println(" 5. Look up a record by ID (hash table)");
            System.out.println(" 6. Show all calls");
            System.out.println(" 7. Run the report's dispatch trace (8 calls, 3 ambulances)");
            System.out.println(" 8. Run the report's experiments (heap vs FIFO, KD-tree, A*)");
            System.out.println(" 9. Reset the city");
            System.out.println("10. Change traffic (now " + ds.g.traffic + ")");
            System.out.println(" 0. Exit");
            int choice = readInt("Choose: ", 0, 10);

            switch (choice) {
                case 1: showCity(ds); break;
                case 2: newCall(ds); break;
                case 3: showQueue(ds); break;
                case 4: ds.advanceTo(ds.now + readInt("Minutes to advance (1-500): ", 1, 500)); break;
                case 5: lookup(ds); break;
                case 6: showCalls(ds); break;
                case 7: Experiments.traceDemo(); break;
                case 8:
                    Experiments.compareQueues();
                    Experiments.kdTreeVsLinear();
                    Experiments.dijkstraVsAStar();
                    break;
                case 9: ds = Experiments.buildCity(true, 5, 7); System.out.println("City reset."); break;
                case 10: {
                    int t = readInt("Traffic: 1 = light, 2 = normal, 3 = heavy (rush hour): ", 1, 3);
                    ds.g.applyTraffic(RoadNetwork.TRAFFIC_LEVELS[t - 1], 99);
                    System.out.println("Traffic is now " + ds.g.traffic
                            + ". Edge weights changed, so new A*/Dijkstra routes adapt.");
                    break;
                }
                case 0: System.out.println("Bye!"); return;
            }
        }
    }

    private static void showCity(DispatchSystem ds) {
        RoadNetwork g = ds.g;
        System.out.printf("Road graph: %d intersections, %d two-way roads, %.1f km blocks, traffic %s%n",
                g.size(), g.roadCount(), g.blockKm, g.traffic);
        System.out.println("-- Ambulances --");
        for (Ambulance a : ds.ambulances.values()) {
            System.out.printf("  %-4s node %-3d (row %2d, col %2d)  %-10s %s%n", a.id, a.node,
                    a.node / g.cols, a.node % g.cols, a.status,
                    a.callId == null ? "" : "serving " + a.callId + ", free at t=" + String.format("%.1f", a.freeAt));
        }
        System.out.println("-- Hospitals --");
        for (Hospital h : ds.hospitals.values()) {
            System.out.printf("  %-4s %-18s node %-3d  free beds %d/%d%n", h.id, h.name, h.node, h.beds, h.capacity);
        }
    }

    private static void newCall(DispatchSystem ds) {
        System.out.print("Patient name: ");
        String name = in.nextLine().trim();
        if (name.isEmpty()) name = "Unknown";
        int sev = readInt("Severity (1 = critical, 2 = serious, 3 = minor): ", 1, 3);
        int row = readInt("Incident row (0-19): ", 0, ds.g.rows - 1);
        int col = readInt("Incident col (0-19): ", 0, ds.g.cols - 1);
        EmergencyCall c = ds.receiveCall(name, sev, ds.g.node(row, col));
        if (c.status.equals("WAITING")) {
            System.out.println("No ambulance free right now - call " + c.id + " is waiting in the priority queue.");
        } else {
            System.out.println("KD-tree candidates (A* road time): " + c.candidates);
            System.out.println("Route to patient : " + c.routeToPatient);
            System.out.println("Route to hospital: " + c.routeToHospital);
        }
    }

    private static void showQueue(DispatchSystem ds) {
        List<EmergencyCall> q = ds.waitingInOrder();
        if (q.isEmpty()) { System.out.println("Queue is empty."); return; }
        int pos = 1;
        for (EmergencyCall c : q) {
            System.out.printf("  %d. %s %-8s %-12s arrived t=%.1f%n", pos++, c.id, c.severityName(), c.patientName, c.arrivalTime);
        }
    }

    private static void lookup(DispatchSystem ds) {
        System.out.print("Enter an ID (e.g. A1, H2, C01): ");
        String id = in.nextLine().trim().toUpperCase();
        // Three O(1) hash-table lookups - no searching through lists
        if (ds.ambulances.containsKey(id)) {
            Ambulance a = ds.ambulances.get(id);
            System.out.printf("Ambulance %s at node %d, %s (%s)%n", a.id, a.node, a.status, ds.phaseOf(a));
        } else if (ds.hospitals.containsKey(id)) {
            Hospital h = ds.hospitals.get(id);
            System.out.printf("Hospital %s %s at node %d, free beds %d/%d%n", h.id, h.name, h.node, h.beds, h.capacity);
        } else if (ds.calls.containsKey(id)) {
            printCall(ds.calls.get(id));
        } else {
            System.out.println("No record with ID " + id);
        }
    }

    private static void showCalls(DispatchSystem ds) {
        if (ds.calls.isEmpty()) { System.out.println("No calls yet."); return; }
        for (EmergencyCall c : ds.calls.values()) printCall(c);
    }

    private static void printCall(EmergencyCall c) {
        if (c.status.equals("WAITING")) {
            System.out.printf("  %s %-8s %-12s node %-3d WAITING since t=%.1f%n",
                    c.id, c.severityName(), c.patientName, c.node, c.arrivalTime);
        } else {
            System.out.printf("  %s %-8s %-12s node %-3d %-10s %s -> %s  wait %.1f  ETA %.1f  to hospital %.1f min%n",
                    c.id, c.severityName(), c.patientName, c.node, c.status, c.ambulanceId, c.hospitalId,
                    c.waitMinutes(), c.travelToPatient, c.travelToHospital);
        }
    }

    /** Reads a whole line and keeps asking until it is a number in [lo, hi]. */
    private static int readInt(String prompt, int lo, int hi) {
        while (true) {
            System.out.print(prompt);
            if (!in.hasNextLine()) System.exit(0);
            String s = in.nextLine().replace("﻿", "").trim();   // ignore an invisible BOM some terminals add
            try {
                int v = Integer.parseInt(s);
                if (v >= lo && v <= hi) return v;
            } catch (NumberFormatException ignored) { }
            System.out.println("Please enter a number from " + lo + " to " + hi + ".");
        }
    }
}

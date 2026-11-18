import java.util.ArrayList;
import java.util.List;

/**
 * The three kinds of RECORDS the system keeps. Each is stored in a HASH TABLE
 * (HashMap) inside DispatchSystem, keyed by its ID, for O(1) average lookup.
 * (Grouped in one file to keep the project small; each class is package-private.)
 */
class Ambulance {
    String id;
    int node;                      // current intersection
    String status = "AVAILABLE";   // AVAILABLE or EN_ROUTE
    String callId;                 // call it is serving (null when AVAILABLE)
    double freeAt;                 // simulated time when it becomes AVAILABLE again

    // Timeline of the current trip, used to draw/animate it: node i is reached at time times[i]
    List<Integer> tripNodes = new ArrayList<>();
    List<Double> tripTimes = new ArrayList<>();

    Ambulance(String id, int node) { this.id = id; this.node = node; }
}

class Hospital {
    String id, name, phone, source;
    double lat, lng;               // only used by the website (real map position)
    int node;                      // intersection on the road graph
    int capacity, beds;            // total beds and free beds (SIMULATED numbers)

    Hospital(String id, String name, int node, int capacity) {
        this.id = id;
        this.name = name;
        this.node = node;
        this.capacity = capacity;
        this.beds = capacity;
    }
}

class EmergencyCall {
    static final String[] SEVERITY_NAME = {"", "CRITICAL", "SERIOUS", "MINOR"};

    String id, patientName;
    int severity;                  // 1 = critical, 2 = serious, 3 = minor
    int node;                      // incident location on the graph
    double arrivalTime;            // when the call was received (simulated minutes)
    long seq;                      // tie-breaker so the heap order is always well defined
    double incidentX, incidentY;   // exact incident position in km (website shows the real pin)

    // Filled in when dispatched
    String status = "WAITING";     // WAITING -> DISPATCHED -> COMPLETED
    String ambulanceId, hospitalId;
    double dispatchTime = -1, travelToPatient, travelToHospital;
    List<Integer> routeToPatient = new ArrayList<>();
    List<Integer> routeToHospital = new ArrayList<>();
    List<String> candidates = new ArrayList<>();   // the k nearest ambulances the KD-Tree returned

    EmergencyCall(String id, String patientName, int severity, int node, double arrivalTime, long seq) {
        this.id = id;
        this.patientName = patientName;
        this.severity = severity;
        this.node = node;
        this.arrivalTime = arrivalTime;
        this.seq = seq;
    }

    String severityName() { return SEVERITY_NAME[severity]; }
    double waitMinutes()  { return dispatchTime - arrivalTime; }
    double responseMinutes() { return waitMinutes() + travelToPatient; }   // wait + travel to patient
}

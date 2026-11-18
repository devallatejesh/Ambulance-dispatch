import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * WEB SERVER for the website, using only the JDK (com.sun.net.httpserver) - nothing to install.
 *
 * Run:  javac -d out src/*.java      then      java -cp out WebServer      then open http://localhost:8080
 *
 * It REUSES the same DispatchSystem (heap + KD-Tree + A* + Dijkstra + hash tables) as the console program.
 *
 * HONESTY NOTES (also shown on the website):
 *   - Hospitals are REAL places from OpenStreetMap (fetched by the browser), or an offline sample list.
 *   - Ambulances, bed counts and roads are SIMULATED. The road network is a grid laid over the map.
 *   - Nothing is ever sent to a hospital. This is a student demo, not an emergency service.
 *
 * API (all answers are JSON):
 *   GET  /api/state                  everything the page needs to draw
 *   GET  /api/fallback-hospitals     offline sample hospital list
 *   POST /api/setup    lat,lng,source,hname*,hlat*,hlng*,hphone*   build a new simulated city here
 *   POST /api/request  lat,lng,severity,name                       new emergency call
 *   POST /api/advance  minutes                                     move the simulated clock
 */
public class WebServer {

    // Simulated city laid over the map: 26 x 26 intersections, 0.4 km apart = 10 km x 10 km
    static final int GRID = 26;
    static final double BLOCK_KM = 0.4;
    static final int AMBULANCES = 8;
    static final double KM_PER_DEG_LAT = 110.574;

    static final Object lock = new Object();    // one request at a time touches the dispatcher
    static DispatchSystem ds;                   // null until /api/setup is called
    static double minLat, minLng, kmPerDegLng, centerLat, centerLng;
    static String source = "";

    // Offline fallback: well-known hospitals in central Bengaluru (APPROXIMATE positions).
    static final String[][] FALLBACK = {
            {"Bowring and Lady Curzon Hospital", "12.9826", "77.6045"},
            {"Victoria Hospital",                "12.9636", "77.5733"},
            {"Vani Vilas Hospital",              "12.9617", "77.5741"},
            {"St. Martha's Hospital",            "12.9707", "77.5888"},
            {"Mallya Hospital",                  "12.9675", "77.5958"},
            {"HOSMAT Hospital",                  "12.9654", "77.6107"},
            {"NIMHANS",                          "12.9406", "77.5963"},
    };
    static final double DEMO_LAT = 12.9716, DEMO_LNG = 77.5946;

    static Path webRoot;

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) port = Integer.parseInt(args[0]);

        // Find the 'web' folder whether we are started from the project folder or from src/
        webRoot = Paths.get("web").toAbsolutePath().normalize();
        if (!Files.isDirectory(webRoot)) webRoot = Paths.get("..", "web").toAbsolutePath().normalize();
        if (!Files.isDirectory(webRoot)) webRoot = Paths.get("AmbulanceDispatch", "web").toAbsolutePath().normalize();
        if (!Files.isDirectory(webRoot)) {
            System.out.println("Cannot find the 'web' folder. Run this from the AmbulanceDispatch folder.");
            return;
        }

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (java.net.BindException e) {
            System.out.println("Port " + port + " is already in use - the website is probably ALREADY running.");
            System.out.println("Open http://localhost:" + port + "  or close the other window and try again.");
            return;
        }
        server.createContext("/api/", WebServer::handleApi);
        server.createContext("/", WebServer::handleStatic);
        // Several threads, so a slow internet look-up never blocks the map, clock or buttons
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Ambulance Dispatch demo running at  http://localhost:" + port);
        System.out.println("Serving files from " + webRoot);
        System.out.println("Press Ctrl+C to stop.");

        CACHE_FILE = webRoot.getParent().resolve("osm-cache.txt");
        loadDiskCache();
        // Warm-up: fetch the demo area's hospitals in the background so the first page load is instant
        new Thread(() -> {
            try { osmHospitals(DEMO_LAT, DEMO_LNG); System.out.println("Demo-area hospitals ready."); }
            catch (Exception e) { System.out.println("Warm-up failed (offline?): " + e.getMessage()); }
        }).start();
    }

    // =====================================================================================
    // API
    // =====================================================================================

    static void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, List<String>> p = parseForm(ex);

            // OpenStreetMap look-ups go over the internet, so they run OUTSIDE the dispatcher lock
            if (path.equals("/api/osm/hospitals")) {
                try { send(ex, 200, osmHospitals(num(p, "lat"), num(p, "lng"))); }
                catch (Exception e) { send(ex, 502, "{\"error\":" + q("OpenStreetMap not reachable: " + e) + "}"); }
                return;
            }
            if (path.equals("/api/osm/reverse")) {
                try { send(ex, 200, osmReverse(num(p, "lat"), num(p, "lng"))); }
                catch (Exception e) { send(ex, 502, "{\"error\":" + q("Address lookup not reachable: " + e) + "}"); }
                return;
            }
            if (path.equals("/api/osm/search")) {
                try {
                    boolean near = !first(p, "lat").isEmpty() && !first(p, "lng").isEmpty();
                    send(ex, 200, osmSearch(first(p, "q"), near ? num(p, "lat") : Double.NaN, near ? num(p, "lng") : Double.NaN));
                }
                catch (Exception e) { send(ex, 502, "{\"error\":" + q("Place search not reachable: " + e) + "}"); }
                return;
            }

            synchronized (lock) {
                if (path.equals("/api/roads") && method.equals("GET")) {
                    send(ex, 200, roadsJson());
                } else if (path.equals("/api/traffic") && method.equals("POST")) {
                    String level = first(p, "level").toUpperCase();
                    if (ds != null && java.util.Arrays.asList(RoadNetwork.TRAFFIC_LEVELS).contains(level)) {
                        ds.g.applyTraffic(level, 7 + (long) ds.now);
                        ds.log.add(String.format(java.util.Locale.ROOT, "[t=%6.1f min] TRAFFIC set to %s - new routes use the new road speeds", ds.now, level));
                    }
                    send(ex, 200, stateJson());
                } else if (path.equals("/api/state") && method.equals("GET")) {
                    send(ex, 200, stateJson());
                } else if (path.equals("/api/fallback-hospitals") && method.equals("GET")) {
                    send(ex, 200, fallbackJson());
                } else if (path.equals("/api/setup") && method.equals("POST")) {
                    setup(p);
                    send(ex, 200, stateJson());
                } else if (path.equals("/api/add-hospitals") && method.equals("POST")) {
                    // Hospitals that arrived after the city was built (only for the SAME city)
                    if (ds != null && (int) num(p, "cityId") == cityId && ds.hospitals.isEmpty()) {
                        source = first(p, "source");
                        addHospitals(p);
                    }
                    send(ex, 200, stateJson());
                } else if (path.equals("/api/request") && method.equals("POST")) {
                    if (ds == null) { send(ex, 400, "{\"error\":\"Set a location first\"}"); return; }
                    if (ds.hospitals.isEmpty()) { send(ex, 409, "{\"error\":\"Hospitals are still loading, try again in a moment\"}"); return; }
                    double lat = num(p, "lat"), lng = num(p, "lng");
                    int sev = (int) num(p, "severity");
                    if (sev < 1 || sev > 3) sev = 3;
                    String name = first(p, "name").trim();
                    if (name.isEmpty()) name = "Unknown";
                    if (name.length() > 40) name = name.substring(0, 40);
                    double xKm = (lng - minLng) * kmPerDegLng, yKm = (lat - minLat) * KM_PER_DEG_LAT;
                    EmergencyCall c = ds.receiveCall(name, sev, ds.g.nearestNode(xKm, yKm));
                    c.incidentX = xKm;     // remember the exact pin, not just the snapped node
                    c.incidentY = yKm;
                    send(ex, 200, "{\"callId\":" + q(c.id) + ",\"state\":" + stateJson() + "}");
                } else if (path.equals("/api/advance") && method.equals("POST")) {
                    if (ds != null) {
                        double m = Math.max(0, Math.min(120, num(p, "minutes")));
                        ds.advanceTo(ds.now + m);
                    }
                    send(ex, 200, stateJson());
                } else {
                    send(ex, 404, "{\"error\":\"not found\"}");
                }
            }
        } catch (Exception e) {
            send(ex, 400, "{\"error\":" + q(String.valueOf(e.getMessage())) + "}");
        }
    }

    static int cityId = 0;          // changes every time a new city is built

    /**
     * Build a fresh simulated city centred on (lat, lng) with the given real hospitals.
     * With pending=1 the city is built WITHOUT hospitals; they come later via /api/add-hospitals.
     */
    static void setup(Map<String, List<String>> p) {
        cityId++;
        centerLat = num(p, "lat");
        centerLng = num(p, "lng");
        source = first(p, "source");
        double half = (GRID - 1) * BLOCK_KM / 2;
        kmPerDegLng = 111.320 * Math.cos(Math.toRadians(centerLat));
        minLat = centerLat - half / KM_PER_DEG_LAT;
        minLng = centerLng - half / kmPerDegLng;

        RoadNetwork g = new RoadNetwork(GRID, GRID, BLOCK_KM, 42);
        ds = new DispatchSystem(g, true);           // PROPOSED policy: heap + KD-Tree + A*
        ds.printLog = true;

        Random rnd = new Random(7);
        for (int i = 1; i <= AMBULANCES; i++) ds.addAmbulance("A" + i, rnd.nextInt(g.size()));
        if (!first(p, "pending").equals("1")) addHospitals(p);
        System.out.printf("New city at %.4f, %.4f with %d hospitals (%s)%n", centerLat, centerLng, ds.hospitals.size(), source);
    }

    /** Snap each real hospital to the nearest road-grid intersection and store it (hash table). */
    static void addHospitals(Map<String, List<String>> p) {
        RoadNetwork g = ds.g;
        List<String> names = p.getOrDefault("hname", new ArrayList<>());
        List<String> lats = p.getOrDefault("hlat", new ArrayList<>());
        List<String> lngs = p.getOrDefault("hlng", new ArrayList<>());
        List<String> phones = p.getOrDefault("hphone", new ArrayList<>());
        Random rnd = new Random(11);
        int count = Math.min(40, Math.min(names.size(), Math.min(lats.size(), lngs.size())));
        for (int i = 0; i < count; i++) {
            double hl = Double.parseDouble(lats.get(i)), hg = Double.parseDouble(lngs.get(i));
            int node = g.nearestNode((hg - minLng) * kmPerDegLng, (hl - minLat) * KM_PER_DEG_LAT);
            String name = names.get(i).length() > 80 ? names.get(i).substring(0, 80) : names.get(i);
            Hospital h = ds.addHospital("H" + (i + 1), name, node, 5 + rnd.nextInt(16));   // SIMULATED beds
            h.lat = hl;
            h.lng = hg;
            h.phone = i < phones.size() ? phones.get(i) : "";
            h.source = source;
        }
        if (ds.hospitals.isEmpty()) {
            // No hospital data at all: one placeholder so the algorithms still have a target
            Hospital h = ds.addHospital("H1", "Placeholder hospital (no data found)", g.node(GRID / 2, GRID / 2), 10);
            h.lat = centerLat; h.lng = centerLng; h.phone = ""; h.source = "placeholder";
        }
    }

    // =====================================================================================
    // Real hospital data from OpenStreetMap (free, no API key)
    //   1st try: Nominatim search for "hospital" inside a 10 km box
    //   2nd try: Overpass API
    // Results are kept in a HASH TABLE cache so the same area is not downloaded twice.
    // The raw JSON is passed to the browser, which reads it (so Java needs no JSON parser).
    // =====================================================================================

    static final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)          // measured: faster than HTTP/2 for these servers
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    static final String USER_AGENT = "AmbulanceDispatchStudentDemo/1.0 (educational DSA project)";
    static final Map<String, String> osmCache = new HashMap<>();     // HASH TABLE cache: area -> answer
    static Path CACHE_FILE;                                          // AmbulanceDispatch/osm-cache.txt, survives a restart
    static long lastNominatimCall = 0;

    /** Read earlier answers from disk: one "key<TAB>json" per line. */
    static void loadDiskCache() {
        try {
            if (!Files.exists(CACHE_FILE)) return;
            for (String line : Files.readAllLines(CACHE_FILE, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) osmCache.put(line.substring(0, tab), line.substring(tab + 1));
            }
            System.out.println("Loaded " + osmCache.size() + " cached OpenStreetMap answers.");
        } catch (IOException e) {
            System.out.println("Could not read the cache: " + e.getMessage());
        }
    }

    static void remember(String key, String json) {
        json = json.replace('\n', ' ').replace('\r', ' ');      // keep it on one line
        synchronized (osmCache) {
            osmCache.put(key, json);
            try {
                Files.writeString(CACHE_FILE, key + "\t" + json + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }

    static String osmHospitals(double lat, double lng) throws Exception {
        // Round to 0.01 degree (about 1 km), so "Reset" and nearby points reuse the same answer
        String key = String.format(Locale.ROOT, "h:%.2f,%.2f", lat, lng);
        synchronized (osmCache) { if (osmCache.containsKey(key)) return osmCache.get(key); }

        double dLat = 5.0 / KM_PER_DEG_LAT, dLng = 5.0 / (111.320 * Math.cos(Math.toRadians(lat)));

        // Ask THREE free OpenStreetMap services at the same time; the first good answer wins.
        CompletableFuture<String> nom = nominatimAsync(String.format(Locale.ROOT,
                "/search?q=hospital&format=jsonv2&limit=50&bounded=1&extratags=1&viewbox=%.5f,%.5f,%.5f,%.5f",
                lng - dLng, lat + dLat, lng + dLng, lat - dLat))
                .thenApply(b -> wrap(b.trim().length() > 2, "OpenStreetMap (Nominatim)", "nominatim", b));
        CompletableFuture<String> photon = getAsync(String.format(Locale.ROOT,
                "https://photon.komoot.io/api/?q=hospital&osm_tag=amenity:hospital&limit=50&bbox=%.5f,%.5f,%.5f,%.5f",
                lng - dLng, lat - dLat, lng + dLng, lat + dLat), 10)
                .thenApply(b -> wrap(b.contains("\"geometry\""), "OpenStreetMap (Photon)", "photon", b));
        String query = String.format(Locale.ROOT,
                "[out:json][timeout:12];nwr[\"amenity\"=\"hospital\"](around:5000,%.5f,%.5f);out center tags;", lat, lng);
        CompletableFuture<String> overpass = http.sendAsync(HttpRequest.newBuilder(
                        URI.create("https://maps.mail.ru/osm/tools/overpass/api/interpreter"))
                        .timeout(Duration.ofSeconds(12)).header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> wrap(r.statusCode() == 200 && r.body().contains("\"type\""), "OpenStreetMap (Overpass)", "overpass", r.body()));

        String result = firstSuccess(List.of(nom, photon, overpass), 13000);
        remember(key, result);
        return result;
    }

    /** Street address of a map point, so the user can see where the pin is (Nominatim and Photon race). */
    static String osmReverse(double lat, double lng) throws Exception {
        String key = String.format(Locale.ROOT, "r:%.4f,%.4f", lat, lng);      // about 10 m
        synchronized (osmCache) { if (osmCache.containsKey(key)) return osmCache.get(key); }
        CompletableFuture<String> nom = nominatimAsync(String.format(Locale.ROOT,
                "/reverse?format=jsonv2&zoom=18&lat=%.6f&lon=%.6f", lat, lng))
                .thenApply(b -> wrap(b.contains("display_name"), "Nominatim", "nominatim", b));
        CompletableFuture<String> photon = getAsync(String.format(Locale.ROOT,
                "https://photon.komoot.io/reverse?lat=%.6f&lon=%.6f", lat, lng), 8)
                .thenApply(b -> wrap(b.contains("\"geometry\""), "Photon", "photon", b));
        String result = firstSuccess(List.of(nom, photon), 9000);
        remember(key, result);
        return result;
    }

    /** Find a place by name, e.g. "Mysuru" or "Amrita Bangalore" (Nominatim and Photon race). */
    static String osmSearch(String text, double nearLat, double nearLng) throws Exception {
        text = text.trim();
        if (text.isEmpty() || text.length() > 100) throw new IllegalArgumentException("Type a place name");
        // Prefer results near the map's current position (an address in YOUR area first)
        boolean near = !Double.isNaN(nearLat);
        String nomBias = near ? String.format(Locale.ROOT, "&viewbox=%.3f,%.3f,%.3f,%.3f",
                nearLng - 0.15, nearLat + 0.15, nearLng + 0.15, nearLat - 0.15) : "";
        String photonBias = near ? String.format(Locale.ROOT, "&lat=%.3f&lon=%.3f", nearLat, nearLng) : "";
        String key = "s:" + text.toLowerCase() + (near ? String.format(Locale.ROOT, "@%.1f,%.1f", nearLat, nearLng) : "");
        synchronized (osmCache) { if (osmCache.containsKey(key)) return osmCache.get(key); }
        String enc = URLEncoder.encode(text, StandardCharsets.UTF_8);
        CompletableFuture<String> nom = nominatimAsync("/search?format=jsonv2&limit=5&q=" + enc + nomBias)
                .thenApply(b -> wrap(b.trim().length() > 2, "Nominatim", "nominatim", b));
        CompletableFuture<String> photon = getAsync("https://photon.komoot.io/api/?limit=5&q=" + enc + photonBias, 10)
                .thenApply(b -> wrap(b.contains("\"geometry\""), "Photon", "photon", b));
        String result = firstSuccess(List.of(nom, photon), 11000);
        remember(key, result);
        return result;
    }

    /** {"source":..,"format":..,"data":<raw json>}, or an error if the answer is empty/bad. */
    static String wrap(boolean ok, String source, String format, String body) {
        if (!ok) throw new java.util.concurrent.CompletionException(new IOException(source + ": no results"));
        return "{\"source\":" + q(source) + ",\"format\":" + q(format) + ",\"data\":" + body + "}";
    }

    /** Waits for the FIRST future that succeeds. Fails only if all of them fail (or time runs out). */
    static String firstSuccess(List<CompletableFuture<String>> futures, long timeoutMs) throws Exception {
        CompletableFuture<String> winner = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
        for (CompletableFuture<String> f : futures) {
            f.whenComplete((value, error) -> {
                if (error == null) winner.complete(value);
                else if (failures.incrementAndGet() == futures.size()) winner.completeExceptionally(error);
            });
        }
        try {
            return winner.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("all OpenStreetMap services failed: " + e.getCause().getMessage());
        }
    }

    static CompletableFuture<String> getAsync(String url, int timeoutSec) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec)).header("User-Agent", USER_AGENT).GET().build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new java.util.concurrent.CompletionException(new IOException(url + " answered " + r.statusCode()));
            return r.body();
        });
    }

    /** Nominatim's rules: at most 1 request per second, and say who we are in User-Agent. */
    static CompletableFuture<String> nominatimAsync(String pathAndQuery) {
        synchronized (WebServer.class) {
            long wait = 1100 - (System.currentTimeMillis() - lastNominatimCall);
            if (wait > 0) { try { Thread.sleep(wait); } catch (InterruptedException ignored) { } }
            lastNominatimCall = System.currentTimeMillis();
        }
        return getAsync("https://nominatim.openstreetmap.org" + pathAndQuery, 8);
    }

    // =====================================================================================
    // JSON (built by hand so no library is needed)
    // =====================================================================================

    /** Every road of the simulated grid with its current (traffic) speed, for drawing. */
    static String roadsJson() {
        if (ds == null) return "{\"roads\":[]}";
        RoadNetwork g = ds.g;
        StringBuilder sb = new StringBuilder("{\"traffic\":").append(q(g.traffic)).append(",\"roads\":[");
        for (int i = 0; i < g.roads.size(); i++) {
            RoadNetwork.Road r = g.roads.get(i);
            sb.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.5f,%.5f,%.5f,%.5f,%d]",
                    lat(g.y[r.u]), lng(g.x[r.u]), lat(g.y[r.v]), lng(g.x[r.v]), r.speed));
        }
        return sb.append("]}").toString();
    }

    static String stateJson() {
        StringBuilder sb = new StringBuilder("{");
        if (ds == null) return "{\"ready\":false}";
        RoadNetwork g = ds.g;
        sb.append("\"ready\":true,\"source\":").append(q(source))
          .append(",\"traffic\":").append(q(g.traffic))
          .append(",\"cityId\":").append(cityId)
          .append(",\"clock\":").append(r1(ds.now))
          .append(",\"center\":[").append(centerLat).append(',').append(centerLng).append(']')
          .append(",\"bounds\":[[").append(minLat).append(',').append(minLng).append("],[")
          .append(lat(g.y[g.size() - 1])).append(',').append(lng(g.x[g.size() - 1])).append("]]")
          .append(",\"grid\":").append(GRID);

        sb.append(",\"ambulances\":[");
        boolean firstItem = true;
        for (Ambulance a : ds.ambulances.values()) {
            double[] pos = ds.positionOf(a);
            sb.append(firstItem ? "" : ",").append("{\"id\":").append(q(a.id))
              .append(",\"status\":").append(q(a.status))
              .append(",\"phase\":").append(q(ds.phaseOf(a)))
              .append(",\"callId\":").append(q(a.callId))
              .append(",\"freeAt\":").append(r1(a.freeAt))
              .append(",\"lat\":").append(lat(pos[1])).append(",\"lng\":").append(lng(pos[0])).append('}');
            firstItem = false;
        }
        sb.append("],\"hospitals\":[");
        firstItem = true;
        for (Hospital h : ds.hospitals.values()) {
            sb.append(firstItem ? "" : ",").append("{\"id\":").append(q(h.id))
              .append(",\"name\":").append(q(h.name))
              .append(",\"beds\":").append(h.beds).append(",\"capacity\":").append(h.capacity)
              .append(",\"phone\":").append(q(h.phone))
              .append(",\"lat\":").append(h.lat).append(",\"lng\":").append(h.lng).append('}');
            firstItem = false;
        }
        sb.append("],\"calls\":[");
        List<EmergencyCall> all = new ArrayList<>(ds.calls.values());
        for (int i = all.size() - 1; i >= 0; i--) {             // newest first
            EmergencyCall c = all.get(i);
            sb.append(i == all.size() - 1 ? "" : ",").append(callJson(c));
        }
        sb.append("],\"queue\":[");
        firstItem = true;
        for (EmergencyCall c : ds.waitingInOrder()) {
            sb.append(firstItem ? "" : ",").append("{\"id\":").append(q(c.id))
              .append(",\"name\":").append(q(c.patientName))
              .append(",\"severity\":").append(c.severity)
              .append(",\"arrival\":").append(r1(c.arrivalTime)).append('}');
            firstItem = false;
        }
        sb.append("],\"log\":[");
        int from = Math.max(0, ds.log.size() - 30);
        for (int i = ds.log.size() - 1; i >= from; i--) {
            sb.append(i == ds.log.size() - 1 ? "" : ",").append(q(ds.log.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    static String callJson(EmergencyCall c) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(q(c.id))
          .append(",\"name\":").append(q(c.patientName))
          .append(",\"severity\":").append(c.severity)
          .append(",\"status\":").append(q(c.status))
          .append(",\"arrival\":").append(r1(c.arrivalTime))
          .append(",\"lat\":").append(lat(c.incidentY)).append(",\"lng\":").append(lng(c.incidentX));
        if (!c.status.equals("WAITING")) {
            Hospital h = ds.hospitals.get(c.hospitalId);
            sb.append(",\"ambulance\":").append(q(c.ambulanceId))
              .append(",\"hospital\":").append(q(c.hospitalId))
              .append(",\"dispatchTime\":").append(r1(c.dispatchTime))
              .append(",\"wait\":").append(r1(c.waitMinutes()))
              .append(",\"eta\":").append(r1(c.travelToPatient))
              .append(",\"toHospital\":").append(r1(c.travelToHospital))
              .append(",\"arriveAt\":").append(r1(c.dispatchTime + c.travelToPatient))
              .append(",\"candidates\":[");
            for (int i = 0; i < c.candidates.size(); i++) sb.append(i == 0 ? "" : ",").append(q(c.candidates.get(i)));
            sb.append("],\"route1\":").append(routeJson(c.routeToPatient, lat(c.incidentY), lng(c.incidentX)))
              .append(",\"route2\":").append(routeJson(c.routeToHospital, h.lat, h.lng));
        }
        return sb.append('}').toString();
    }

    /** Route nodes as [[lat,lng],...], plus the real end point (pin / hospital) at the end. */
    static String routeJson(List<Integer> nodes, double endLat, double endLng) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nodes.size(); i++) {
            int v = nodes.get(i);
            sb.append(i == 0 ? "" : ",").append('[').append(lat(ds.g.y[v])).append(',').append(lng(ds.g.x[v])).append(']');
        }
        sb.append(nodes.isEmpty() ? "" : ",").append('[').append(endLat).append(',').append(endLng).append("]]");
        return sb.toString();
    }

    static String fallbackJson() {
        StringBuilder sb = new StringBuilder("{\"center\":[" + DEMO_LAT + "," + DEMO_LNG + "],\"hospitals\":[");
        for (int i = 0; i < FALLBACK.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"name\":").append(q(FALLBACK[i][0]))
              .append(",\"lat\":").append(FALLBACK[i][1]).append(",\"lng\":").append(FALLBACK[i][2])
              .append(",\"phone\":\"\"}");
        }
        return sb.append("]}").toString();
    }

    static double lat(double yKm) { return minLat + yKm / KM_PER_DEG_LAT; }
    static double lng(double xKm) { return minLng + xKm / kmPerDegLng; }
    static String r1(double v)    { return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.1f", v) : "null"; }

    /** JSON string with escaping (so a patient name cannot break the JSON). */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (ch < 0x20 || ch == '<' || ch == '>') sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    // =====================================================================================
    // HTTP helpers
    // =====================================================================================

    /** Reads the query string and (for POST) an application/x-www-form-urlencoded body. */
    static Map<String, List<String>> parseForm(HttpExchange ex) throws IOException {
        Map<String, List<String>> map = new HashMap<>();
        String query = ex.getRequestURI().getRawQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String part : ((query == null ? "" : query) + "&" + body).split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            map.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return map;
    }

    static String first(Map<String, List<String>> p, String k) {
        List<String> v = p.get(k);
        return v == null || v.isEmpty() ? "" : v.get(0);
    }

    static double num(Map<String, List<String>> p, String k) {
        double v = Double.parseDouble(first(p, k));
        if (!Double.isFinite(v)) throw new IllegalArgumentException("bad number for " + k);
        return v;
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {   // block ../ tricks
            send(ex, 404, "Not found", "text/plain");
            return;
        }
        String type = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                : path.endsWith(".js") ? "application/javascript" : "application/octet-stream";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    static void send(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, json, "application/json");
    }

    static void send(HttpExchange ex, int code, String text, String type) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}

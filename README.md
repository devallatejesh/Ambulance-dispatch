# Ambulance Dispatch System (DSA-2 project)

Java code for the report *"Ambulance Dispatch System using Advanced Data Structures"*.
Plain Java 11+ (tested on Java 21). Nothing to install: no Maven or Gradle, no libraries.

> **Student demo, not a real emergency service.** In a real emergency call **108** (ambulance) or **112**.

## What is in the folder

| File | DSA concept (report section) |
|---|---|
| `src/MinHeap.java` | Binary **min-heap** priority queue: push / pop O(log n) (Table 3, 2.4) |
| `src/RoadNetwork.java` | Weighted **graph** as an adjacency list. Edge weight = travel time. Simulated traffic changes the weights (Table 1, 3.3) |
| `src/PathFinder.java` | **Dijkstra** (nearest hospital with a free bed) and **A\*** (ambulance to patient) (2.3, 4) |
| `src/KDTree.java` | 2-D **KD-Tree**, k-nearest with pruning (2.5, 4) |
| `src/Models.java` | Records: Ambulance, Hospital, EmergencyCall |
| `src/DispatchSystem.java` | The dispatcher: **hash tables** + heap + KD-Tree + A\* + Dijkstra + **FIFO queue** baseline + event queue (Figure 1) |
| `src/Experiments.java` | The report's 4 runs: trace, heap vs FIFO, KD-Tree vs linear scan, Dijkstra vs A\* |
| `src/Main.java` | **Console menu program** |
| `src/WebServer.java` | **Website backend** (JDK built-in `com.sun.net.httpserver`), reuses `DispatchSystem` |
| `web/` | Website frontend: `index.html`, `style.css`, `app.js` (Leaflet map) |
| `run-website.bat` / `run-console.bat` | **One-click run**: compiles and starts, from any folder |
| `.vscode/` | VS Code run buttons (Run and Debug) and tasks |
| `vercel-site/` | **Online version** for Vercel: the same algorithms ported to JavaScript (`engine.js`), runs fully in the browser |
| `vercel.json` | Tells Vercel to publish the `vercel-site` folder |
| `Ambulance_Dispatch_Presentation.pptx` | 13-slide presentation, with speaker notes under each slide |
| `Project_Explanation.docx` / `.pdf` | Talk for ma'am, code explanation, what's new, real-life use, results, viva Q&A |

---

## Easiest way: double-click

- **Website:** double-click `run-website.bat`. Your browser opens **http://localhost:8080** after about 3 seconds. Keep the black window open while you use the site, and close it to stop the server.
- **Console program:** double-click `run-console.bat`.

From a terminal in `New folder`, this works too:
```bash
.\AmbulanceDispatch\run-website.bat
```

> **Common mistake:** `javac -d out src/*.java` only works **inside** the `AmbulanceDispatch` folder. If you are in `New folder`, run `cd AmbulanceDispatch` first. Otherwise you get *"Invalid filename: src/*.java"* or *"Could not find or load main class WebServer"*.

## A. Run in VS Code

**Option 1: Run button** (needs the *Extension Pack for Java* from Microsoft)
1. **File > Open Folder…** and pick the **`AmbulanceDispatch`** folder itself, not `New folder`.
2. Press **Ctrl+Shift+D** (Run and Debug). At the top, choose **Website (WebServer)** or **Console program (Main)**, then click the green **▶**.
3. For the website, open **http://localhost:8080**.

**Option 2: Task, no extension needed**
- Press **Ctrl+Shift+B** to start the website, or use **Terminal > Run Task… > Run console program**.

**Option 3: Terminal commands**
1. Open the **`AmbulanceDispatch`** folder, then **Terminal > New Terminal**.
2. Compile everything:
   ```bash
   javac -d out src/*.java
   ```
3. **Console program:**
   ```bash
   java -cp out Main
   ```
   Type a menu number and press Enter. Try **2** (new call), **3** (queue), **4** (advance clock), **7** (trace), **8** (experiments), **10** (change traffic).
4. **Website:**
   ```bash
   java -cp out WebServer
   ```
   Open **http://localhost:8080** in Chrome or Edge. Press **Ctrl+C** in the terminal to stop the server.

If it says *"Port 8080 is already in use"*, the website is already running in another window. Just open http://localhost:8080, or close that window first.

## Online version (Vercel)

Vercel can't run a Java server, so `vercel-site/` holds a browser-only copy of the website. `engine.js` is a line-by-line JavaScript port of `MinHeap`, `RoadNetwork`, `PathFinder`, `KDTree` and `DispatchSystem`, and it answers the same `/api/...` requests that `WebServer.java` answers. Hospital data comes straight from OpenStreetMap in the browser. The Java console program and the Java website are unchanged; use those in the viva.

To publish: on vercel.com choose **Add New → Project**, import this GitHub repository, and click **Deploy**. `vercel.json` already points Vercel at `vercel-site`. Every later `git push` redeploys automatically.

## B. Run in Eclipse

1. **File > New > Java Project**.
2. Project name: `AmbulanceDispatch`.
3. **Untick** "Use default location", then **Browse…** and pick this `AmbulanceDispatch` folder.
4. **Untick** "Create module-info.java file". Click **Finish**. Eclipse finds the `src` folder by itself.
5. **Console program:** in Package Explorer open `src > (default package)`, right-click **Main.java > Run As > Java Application**. Type in the **Console** view at the bottom.
6. **Website:** right-click **WebServer.java > Run As > Java Application**, then open **http://localhost:8080** in your browser.
   Stop it with the red **Terminate** square in the Console view before running it again.

If Eclipse shows *"Access restriction … com.sun.net.httpserver"*: right-click the project, then **Properties > Java Build Path > Libraries**. Remove the old JRE, then **Add Library > JRE System Library > Workspace default JRE** (Java 11 or newer).

---

## Using the website

1. **Choose the area.** The page starts in Bengaluru. To use another area, type a city or area in the **search box** (for example `Mysuru`) and click a result, or press **My location** and allow it. The site then loads the real hospitals of that area, and their **names appear on the map**. Zoom in to see every name.
2. **Set the patient's location.** Use any of these:
   - **Click anywhere** on the map, or **drag** the red **!** pin.
   - **Pick on map**: the cursor becomes a crosshair, then click the spot. Press Esc to cancel.
   - **My location**: uses your device's location, if you allow it.
   - **Map centre**: drops the pin in the middle of what you're looking at.
   - **Type an address** (for example `Cubbon Park`) or **coordinates** (for example `12.97, 77.59`) in the search box. Places inside the current area just move the pin, and places elsewhere build a new area there.

   The panel shows the pin's **street address**. The pin's popup has a one-click **Request ambulance here** button. If you click outside the dashed 10 km box, a popup offers **Move the area here**.
3. **Request.** Enter a name, pick **Critical / Serious / Minor**, and press **Request ambulance**.
4. **Dispatch status** shows the progress steps (Queued, En route, On scene, To hospital, Handover, Done), a countdown, the ambulance chosen (the KD-Tree's 3 nearest, compared by A\* road time) and the hospital (Dijkstra, nearest free bed). **Show on map** zooms to the route.
5. **Tabs:**
   - **Calls:** click a call to track it.
   - **Queue:** the waiting calls in min-heap order.
   - **Ambulances:** click one to find it on the map.
   - **Hospitals:** sorted by distance from the pin. Click one to open its popup, which has a **real directions** link.
6. **Traffic:** **Light / Normal / Rush hour** changes the road speeds, which are the graph's edge weights. The coloured road grid updates (green = fast, red = jam), and new routes and ETAs change. Untick *Show road speeds* to hide the colours.
7. **Clock:**
   - **Pause / Play** stops and starts the clock.
   - The speed box sets how fast it runs.
   - **+5 min** jumps ahead.
   - **Reset** rebuilds the area and clears all calls.
8. When all 8 ambulances are busy, new calls wait in the **min-heap queue**, and critical calls go first.

**What is real and what is simulated**

- **Real:** the map, and the hospital names and positions for whatever area you choose. They come from OpenStreetMap.
  - The Java server asks **Nominatim, Photon and Overpass at the same time** and uses whichever answers first. All three are free and need no API key.
  - The map and ambulances appear at once, and hospitals are added when they arrive (usually 1–3 s).
  - Search results start loading their hospitals while you read the list.
  - Answers are kept in a `HashMap` cache and in `osm-cache.txt`, so an area you have used before loads instantly. You can delete that file at any time. If OpenStreetMap can't be reached, an offline Bengaluru sample list is used. The Hospitals tab shows which source is in use.
- **Simulated:** ambulances, bed counts, **traffic** and roads. Routes follow a grid laid over the map, not real streets. Free real-time traffic data needs paid API keys, so the traffic here is simulated and labelled as simulated.
- **The site never contacts any hospital or service.**
- **My location** needs browser permission. If it is blocked, use the search box.

## Notes for the viva

- **Two-stage ambulance choice:** the KD-Tree gives the 3 nearest by straight line. A\* then checks the real road time, and the fastest one wins.
- **Heap key** = (severity, arrival time), so a critical call overtakes older minor calls.
- **A\* heuristic** = straight-line km at 60 km/h. No road is faster than that, so it never over-estimates (admissible) and A\* still finds the optimal route. Experiment 4 shows 0 different routes.
- **Hash tables** (`HashMap`): ambulance, hospital and call lookup by ID in O(1). Menu option 5 demonstrates this.
- The report describes a Python prototype. This is the Java version of the same design, so the experiment numbers differ from the report's tables, but the trends are the same: critical calls are much faster with the heap, the KD-Tree is hundreds of times faster at 100,000 vehicles, and A\* expands about half as many nodes as Dijkstra.

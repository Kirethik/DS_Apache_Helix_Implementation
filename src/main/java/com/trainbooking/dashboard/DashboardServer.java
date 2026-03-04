package com.trainbooking.dashboard;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.trainbooking.batching.BatchedUpdateManager;
import com.trainbooking.db.DatabaseManager;
import com.trainbooking.model.BookingResult;
import com.trainbooking.model.Seat;
import com.trainbooking.service.BookingService;
import com.trainbooking.setup.ClusterSetup;
import org.apache.helix.HelixAdmin;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.model.ExternalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DashboardServer — HTTP server with full database persistence.
 *
 * All bookings, seat states, metrics, and cluster events are persisted
 * to SQLite (trainbooking.db) and restored automatically on restart.
 *
 * REST API:
 *   GET  /              → HTML dashboard
 *   GET  /api/cluster   → live topology (ZK or simulation)
 *   GET  /api/seats     → seat state for trainId
 *   POST /api/reserve   → reserve seat (persisted)
 *   POST /api/confirm   → confirm seat (persisted)
 *   POST /api/expire    → expire reservation (persisted)
 *   POST /api/cancel    → cancel booking (persisted)
 *   GET  /api/metrics   → ZK write counts
 *   GET  /api/events    → recent event log
 *   POST /api/failover  → simulate node failure (persisted)
 *   POST /api/reset     → reset seats (persisted)
 *   GET  /api/db        → full DB contents (trains, seats, events, metrics)
 */
public class DashboardServer {

    private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);
    private static final int PORT = 8080;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Train metadata ─────────────────────────────────────────────
    // trainId → [trainName, route, departure, arrival]
    private static final Map<String, String[]> TRAIN_META = new LinkedHashMap<>();
    static {
        TRAIN_META.put("Train_101", new String[]{"Shatabdi Express", "Chennai → Mumbai",     "06:00", "22:00"});
        TRAIN_META.put("Train_202", new String[]{"Rajdhani Express", "Delhi → Kolkata",       "07:30", "23:30"});
        TRAIN_META.put("Train_303", new String[]{"Duronto Express",  "Bengaluru → Hyderabad", "09:00", "15:00"});
        TRAIN_META.put("Train_404", new String[]{"Vande Bharat",     "Mumbai → Pune",         "08:00", "10:30"});
    }

    // ── In-memory booking services (loaded from DB on startup) ─────
    private static final Map<String, BookingService> services = new LinkedHashMap<>();
    private static final Deque<String> eventLog = new ArrayDeque<>();
    private static final int MAX_EVENTS = 100;

    // ── Batching metrics ───────────────────────────────────────────
    private static final AtomicLong zkWritesNoBatch    = new AtomicLong(0);
    private static final AtomicLong zkWritesBatch      = new AtomicLong(0);
    private static final AtomicLong totalBookingEvents = new AtomicLong(0);
    private static final BatchedUpdateManager batchMgr =
            new BatchedUpdateManager(true, 10, 2000);

    // ── Helix / ZK ─────────────────────────────────────────────────
    private static HelixAdmin helixAdmin = null;
    private static String zkAddr = "localhost:2181";

    // ── Simulated topology ─────────────────────────────────────────
    private static final Map<String, Map<String, String>> simulatedTopology = new LinkedHashMap<>();
    private static boolean node1Active = true;
    private static boolean node2Active = true;
    private static boolean node3Active = true;

    // ── DB ─────────────────────────────────────────────────────────
    private static DatabaseManager db;

    // ─────────────────────────────────────────────────────────────────
    //  Startup
    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        zkAddr = (args.length > 0) ? args[0] : "localhost:2181";

        // 1. Init database
        db = DatabaseManager.getInstance();
        db.startMetricsSession();

        // 2. Seed train metadata & seats; restore persisted seat states
        initTrainsAndSeats();

        // 3. Reset simulated topology
        resetTopology();

        // 4. Try ZooKeeper
        try {
            helixAdmin = new ZKHelixAdmin(zkAddr);
            helixAdmin.getClusters();
            log.info("Connected to ZooKeeper at {}", zkAddr);
            addEvent("🔌 Connected to ZooKeeper at " + zkAddr, "info");
        } catch (Exception e) {
            log.warn("ZooKeeper not reachable — simulation mode.");
            addEvent("⚠️ ZooKeeper offline — simulation mode active", "warn");
            helixAdmin = null;
        }

        // 5. Start HTTP server
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/",            DashboardServer::handleRoot);
        server.createContext("/api/cluster", DashboardServer::handleCluster);
        server.createContext("/api/seats",   DashboardServer::handleSeats);
        server.createContext("/api/reserve", DashboardServer::handleReserve);
        server.createContext("/api/confirm", DashboardServer::handleConfirm);
        server.createContext("/api/expire",  DashboardServer::handleExpire);
        server.createContext("/api/cancel",  DashboardServer::handleCancel);
        server.createContext("/api/metrics", DashboardServer::handleMetrics);
        server.createContext("/api/events",  DashboardServer::handleEvents);
        server.createContext("/api/failover",DashboardServer::handleFailover);
        server.createContext("/api/reset",   DashboardServer::handleReset);
        server.createContext("/api/db",      DashboardServer::handleDb);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   Train Booking Dashboard — Server Started               ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║   Open browser at: http://localhost:8080                 ║");
        System.out.println("║   Database file  : trainbooking.db                      ║");
        System.out.println("║   ZooKeeper      : " + zkAddr);
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        addEvent("🚀 Dashboard server started on port " + PORT, "success");

        // Periodic metrics flush to DB every 5s
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r); t.setDaemon(true); return t;
        });
        scheduler.scheduleAtFixedRate(() ->
                db.updateMetrics(totalBookingEvents.get(),
                        zkWritesNoBatch.get(), zkWritesBatch.get()),
                5, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            batchMgr.shutdown();
            db.updateMetrics(totalBookingEvents.get(), zkWritesNoBatch.get(), zkWritesBatch.get());
            db.close();
            if (helixAdmin != null) helixAdmin.close();
            server.stop(0);
            System.out.println("[DB] Database saved. Goodbye.");
        }));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Init: seed DB with train/seat data, restore state from DB
    // ─────────────────────────────────────────────────────────────────

    private static void initTrainsAndSeats() {
        for (Map.Entry<String, String[]> e : TRAIN_META.entrySet()) {
            String trainId = e.getKey();
            String[] meta  = e.getValue();

            // Write train record to DB (once)
            db.upsertTrain(trainId, meta[0], meta[1], meta[2], meta[3], 30);

            // Init seat rows in DB (once — INSERT OR IGNORE)
            db.initSeatsForTrain(trainId, 30);

            // Create in-memory BookingService
            BookingService bs = new BookingService(trainId, 30);

            // Restore persisted seat states
            List<Map<String, Object>> persistedSeats = db.getSeatsForTrain(trainId);
            for (Map<String, Object> row : persistedSeats) {
                int seatNum  = (int) row.get("seatNumber");
                String status = (String) row.get("status");
                String name   = (String) row.get("passengerName");

                Seat seat = bs.getSeat(seatNum);
                if (seat != null && !"AVAILABLE".equals(status)) {
                    // Re-apply the stored state
                    if ("RESERVED".equals(status)) {
                        seat.transition(com.trainbooking.model.SeatStatus.AVAILABLE,
                                        com.trainbooking.model.SeatStatus.RESERVED);
                        if (name != null) seat.setPassengerName(name);
                    } else if ("CONFIRMED".equals(status)) {
                        seat.transition(com.trainbooking.model.SeatStatus.AVAILABLE,
                                        com.trainbooking.model.SeatStatus.RESERVED);
                        seat.transition(com.trainbooking.model.SeatStatus.RESERVED,
                                        com.trainbooking.model.SeatStatus.CONFIRMED);
                        if (name != null) seat.setPassengerName(name);
                    } else if ("CANCELLED".equals(status)) {
                        seat.transition(com.trainbooking.model.SeatStatus.AVAILABLE,
                                        com.trainbooking.model.SeatStatus.RESERVED);
                        seat.transition(com.trainbooking.model.SeatStatus.RESERVED,
                                        com.trainbooking.model.SeatStatus.CONFIRMED);
                        seat.transition(com.trainbooking.model.SeatStatus.CONFIRMED,
                                        com.trainbooking.model.SeatStatus.CANCELLED);
                        if (name != null) seat.setPassengerName(name);
                    }
                }
            }

            services.put(trainId, bs);
        }

        Map<String, Object> stats = db.getStats();
        System.out.printf("[DB] Restored: %s trains, %s seats (%s confirmed, %s reserved)%n",
                stats.get("totalTrains"), stats.get("totalSeats"),
                stats.get("confirmedSeats"), stats.get("reservedSeats"));
        addEvent("📂 Database loaded — " + stats.get("confirmedSeats") + " confirmed, "
                + stats.get("reservedSeats") + " reserved seats restored", "info");
    }

    // ─────────────────────────────────────────────────────────────────
    //  HTTP Handlers
    // ─────────────────────────────────────────────────────────────────

    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        InputStream is = DashboardServer.class.getResourceAsStream("/dashboard.html");
        byte[] body = is != null ? is.readAllBytes()
                : "<h1>dashboard.html missing</h1>".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body); ex.getResponseBody().close();
    }

    private static void handleCluster(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        sendJson(ex, topologyToJson(getLiveTopology()));
    }

    private static void handleSeats(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        String trainId = parseParam(ex.getRequestURI().getQuery(), "trainId");
        if (trainId == null || !services.containsKey(trainId)) {
            sendJson(ex, "{\"error\":\"Invalid trainId\"}"); return;
        }
        BookingService bs = services.get(trainId);
        // Get class info from DB
        List<Map<String, Object>> dbSeats = db.getSeatsForTrain(trainId);
        Map<Integer, String> classMap = new HashMap<>();
        for (Map<String, Object> row : dbSeats)
            classMap.put((int) row.get("seatNumber"), (String) row.getOrDefault("seatClass", "ECONOMY"));

        StringBuilder sb = new StringBuilder("{\"trainId\":\"").append(trainId).append("\",\"seats\":[");
        List<Map.Entry<Integer, Seat>> sorted = new ArrayList<>(bs.getAllSeats().entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (int i = 0; i < sorted.size(); i++) {
            Seat s = sorted.get(i).getValue();
            String cls = classMap.getOrDefault(s.getSeatNumber(), "ECONOMY");
            sb.append("{\"num\":").append(s.getSeatNumber())
              .append(",\"status\":\"").append(s.getStatus()).append("\"")
              .append(",\"class\":\"").append(cls).append("\"")
              .append(",\"passenger\":\"").append(s.getPassengerName() == null ? "" : s.getPassengerName()).append("\"}")
              .append(i < sorted.size() - 1 ? "," : "");
        }
        sb.append("]}");
        sendJson(ex, sb.toString());
    }

    private static void handleReserve(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        Map<String, String> p = parseBody(ex);
        String trainId = p.get("trainId");
        String passenger = p.getOrDefault("passenger", "Passenger");
        int seatNum = parseInt(p.get("seatNum"), 1);
        if (!services.containsKey(trainId)) { sendJson(ex, err("Invalid trainId")); return; }

        BookingResult r = services.get(trainId).reserveSeat(seatNum, passenger);
        persistSeatAndEvent(trainId, seatNum, "RESERVE", passenger, r, "MASTER");
        if (r.isSuccess()) addEvent("🎫 Seat " + seatNum + " RESERVED on " + trainId + " for " + passenger, "success");
        else               addEvent("❌ Reserve failed: seat " + seatNum + " — " + r.getMessage(), "error");
        sendJson(ex, resultToJson(r));
    }

    private static void handleConfirm(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        Map<String, String> p = parseBody(ex);
        String trainId = p.get("trainId");
        int seatNum = parseInt(p.get("seatNum"), 1);
        if (!services.containsKey(trainId)) { sendJson(ex, err("Invalid trainId")); return; }

        BookingResult r = services.get(trainId).confirmSeat(seatNum);
        persistSeatAndEvent(trainId, seatNum, "CONFIRM", null, r, "MASTER");
        if (r.isSuccess()) addEvent("✅ Seat " + seatNum + " CONFIRMED on " + trainId, "success");
        else               addEvent("❌ Confirm failed: " + r.getMessage(), "error");
        sendJson(ex, resultToJson(r));
    }

    private static void handleExpire(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        Map<String, String> p = parseBody(ex);
        String trainId = p.get("trainId");
        int seatNum = parseInt(p.get("seatNum"), 1);
        if (!services.containsKey(trainId)) { sendJson(ex, err("Invalid trainId")); return; }

        BookingResult r = services.get(trainId).expireSeat(seatNum);
        persistSeatAndEvent(trainId, seatNum, "EXPIRE", null, r, "MASTER");
        if (r.isSuccess()) addEvent("⏰ Seat " + seatNum + " EXPIRED on " + trainId + " — released", "warn");
        else               addEvent("❌ Expire failed: " + r.getMessage(), "error");
        sendJson(ex, resultToJson(r));
    }

    private static void handleCancel(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        Map<String, String> p = parseBody(ex);
        String trainId = p.get("trainId");
        int seatNum = parseInt(p.get("seatNum"), 1);
        if (!services.containsKey(trainId)) { sendJson(ex, err("Invalid trainId")); return; }

        BookingResult r = services.get(trainId).cancelSeat(seatNum);
        persistSeatAndEvent(trainId, seatNum, "CANCEL", null, r, "MASTER");
        if (r.isSuccess()) addEvent("🚫 Seat " + seatNum + " CANCELLED on " + trainId, "warn");
        else               addEvent("❌ Cancel failed: " + r.getMessage(), "error");
        sendJson(ex, resultToJson(r));
    }

    private static void handleMetrics(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        long events  = totalBookingEvents.get();
        long noBatch = zkWritesNoBatch.get();
        long batch   = zkWritesBatch.get();
        double red   = noBatch > 0 ? (100.0*(noBatch-batch)/noBatch) : 90.0;

        Map<String, Object> stats = db.getStats();
        String json = String.format(
            "{\"totalEvents\":%d,\"zkNoBatch\":%d,\"zkBatch\":%d,\"reduction\":%.1f," +
            "\"available\":%s,\"reserved\":%s,\"confirmed\":%s,\"cancelled\":%s,\"zkMode\":\"%s\"}",
            events, noBatch, batch, red,
            stats.get("availableSeats"), stats.get("reservedSeats"),
            stats.get("confirmedSeats"), stats.get("cancelledSeats"),
            helixAdmin != null ? "LIVE" : "SIMULATION");
        sendJson(ex, json);
    }

    private static void handleEvents(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        synchronized (eventLog) {
            StringBuilder sb = new StringBuilder("[");
            List<String> list = new ArrayList<>(eventLog);
            for (int i = 0; i < list.size(); i++) {
                sb.append("\"").append(list.get(i).replace("\"","'")).append("\"");
                if (i < list.size()-1) sb.append(",");
            }
            sb.append("]");
            sendJson(ex, sb.toString());
        }
    }

    private static void handleFailover(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        Map<String, String> p = parseBody(ex);
        String node = p.getOrDefault("node", "Node1");
        addEvent("☠️ Simulating failure of " + node + "...", "warn");

        if ("Node1".equals(node)) node1Active = false;
        else if ("Node2".equals(node)) node2Active = false;
        else if ("Node3".equals(node)) node3Active = false;

        for (Map.Entry<String, Map<String, String>> entry : simulatedTopology.entrySet()) {
            Map<String, String> stateMap = entry.getValue();
            if ("MASTER".equals(stateMap.get(node))) {
                stateMap.put(node, "OFFLINE");
                for (Map.Entry<String, String> e : stateMap.entrySet()) {
                    if ("SLAVE".equals(e.getValue()) && isNodeActive(e.getKey())) {
                        String newMaster = e.getKey();
                        stateMap.put(newMaster, "MASTER");
                        String desc = node + " failed → " + newMaster + " elected MASTER for " + entry.getKey();
                        db.insertClusterEvent(newMaster, entry.getKey(), "SLAVE", "MASTER", desc);
                        db.insertClusterEvent(node, entry.getKey(), "MASTER", "OFFLINE", node + " crashed");
                        addEvent("⚡ " + newMaster + " promoted to MASTER for " + entry.getKey() + " (Leader Election)", "success");
                        break;
                    }
                }
            }
        }
        addEvent("✅ Failover complete — cluster without " + node, "success");
        sendJson(ex, "{\"success\":true,\"message\":\"Node " + node + " failed — failover executed\"}");
    }

    private static void handleReset(HttpExchange ex) throws IOException {
        node1Active = true; node2Active = true; node3Active = true;
        resetTopology();
        db.resetSeatsOnly();
        for (String trainId : TRAIN_META.keySet())
            services.put(trainId, new BookingService(trainId, 30));
        zkWritesNoBatch.set(0); zkWritesBatch.set(0); totalBookingEvents.set(0);
        synchronized (eventLog) { eventLog.clear(); }
        db.startMetricsSession();
        addEvent("🔄 Cluster reset — all nodes online, seats cleared", "info");
        sendJson(ex, "{\"success\":true}");
    }

    /** NEW endpoint: returns full database contents as JSON */
    private static void handleDb(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendError(ex, 405); return; }
        String section = parseParam(ex.getRequestURI().getQuery(), "section");

        StringBuilder sb = new StringBuilder("{");
        if (section == null || "trains".equals(section)) {
            sb.append("\"trains\":").append(listToJson(db.getAllTrains())).append(",");
        }
        if (section == null || "seats".equals(section)) {
            sb.append("\"seats\":").append(listToJson(db.getAllSeats())).append(",");
        }
        if (section == null || "events".equals(section)) {
            sb.append("\"bookingEvents\":").append(listToJson(db.getRecentBookingEvents(50))).append(",");
        }
        if (section == null || "metrics".equals(section)) {
            sb.append("\"zkMetrics\":").append(listToJson(db.getAllMetricsSessions())).append(",");
        }
        if (section == null || "cluster".equals(section)) {
            sb.append("\"clusterEvents\":").append(listToJson(db.getAllClusterEvents())).append(",");
        }
        sb.append("\"stats\":").append(mapToJson(db.getStats())).append("}");
        sendJson(ex, sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────
    //  Persistence helper — every booking action saves to DB
    // ─────────────────────────────────────────────────────────────────

    private static void persistSeatAndEvent(String trainId, int seatNum, String eventType,
                                             String passenger, BookingResult r, String handledBy) {
        // Update seat in DB
        Seat s = services.get(trainId).getSeat(seatNum);
        if (s != null) {
            db.updateSeat(trainId, seatNum,
                    s.getStatus().name(),
                    s.getPassengerName(),
                    s.getReservedAt()  != null ? s.getReservedAt().toString()  : null,
                    s.getConfirmedAt() != null ? s.getConfirmedAt().toString() : null);
        }
        // Insert event log
        db.insertBookingEvent(trainId, seatNum, eventType,
                passenger != null ? passenger : (s != null ? s.getPassengerName() : null),
                null, r.getNewStatus() != null ? r.getNewStatus().name() : null,
                r.isSuccess(), r.getMessage(), handledBy);

        // Track ZK writes
        recordBookingEvent(trainId, seatNum, eventType, r.isSuccess());
    }

    private static void recordBookingEvent(String trainId, int seat, String type, boolean success) {
        totalBookingEvents.incrementAndGet();
        zkWritesNoBatch.incrementAndGet();
        batchMgr.recordBookingEvent(type + "_" + trainId + "_seat" + seat);
        zkWritesBatch.set(batchMgr.getZkWritesWithBatching());
    }

    // ─────────────────────────────────────────────────────────────────
    //  Topology helpers
    // ─────────────────────────────────────────────────────────────────

    private static void resetTopology() {
        simulatedTopology.clear();
        simulatedTopology.put("Train_101", createStateMap("Node1","Node2","Node3"));
        simulatedTopology.put("Train_202", createStateMap("Node2","Node1","Node3"));
        simulatedTopology.put("Train_303", createStateMap("Node3","Node1","Node2"));
        simulatedTopology.put("Train_404", createStateMap("Node1","Node2","Node3"));
    }
    private static Map<String,String> createStateMap(String m, String s1, String s2) {
        Map<String,String> map = new LinkedHashMap<>();
        map.put(m,"MASTER"); map.put(s1,"SLAVE"); map.put(s2,"SLAVE"); return map;
    }
    private static Map<String, Map<String, String>> getLiveTopology() {
        if (helixAdmin != null) {
            try {
                ExternalView ev = helixAdmin.getResourceExternalView(
                        ClusterSetup.CLUSTER_NAME, ClusterSetup.RESOURCE_NAME);
                if (ev != null) {
                    Map<String, Map<String, String>> result = new LinkedHashMap<>();
                    for (String p : ev.getPartitionSet()) result.put(p, ev.getStateMap(p));
                    return result;
                }
            } catch (Exception ignored) {}
        }
        return simulatedTopology;
    }
    private static boolean isNodeActive(String node) {
        return "Node1".equals(node) ? node1Active : "Node2".equals(node) ? node2Active : node3Active;
    }
    private static String topologyToJson(Map<String, Map<String, String>> topo) {
        StringBuilder sb = new StringBuilder("{\"partitions\":{");
        List<Map.Entry<String, Map<String, String>>> entries = new ArrayList<>(topo.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Map<String, String>> e = entries.get(i);
            sb.append("\"").append(e.getKey()).append("\":{");
            List<Map.Entry<String, String>> ne = new ArrayList<>(e.getValue().entrySet());
            for (int j = 0; j < ne.size(); j++) {
                sb.append("\"").append(ne.get(j).getKey()).append("\":\"").append(ne.get(j).getValue()).append("\"");
                if (j < ne.size()-1) sb.append(",");
            }
            sb.append("}"); if (i < entries.size()-1) sb.append(",");
        }
        sb.append("},\"nodes\":{\"Node1\":\"").append(node1Active?"ONLINE":"OFFLINE")
          .append("\",\"Node2\":\"").append(node2Active?"ONLINE":"OFFLINE")
          .append("\",\"Node3\":\"").append(node3Active?"ONLINE":"OFFLINE").append("\"}}");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────
    //  JSON serialization helpers
    // ─────────────────────────────────────────────────────────────────

    private static String resultToJson(BookingResult r) {
        return String.format("{\"success\":%b,\"message\":\"%s\",\"trainId\":\"%s\",\"seatNumber\":%d,\"status\":\"%s\"}",
                r.isSuccess(), r.getMessage().replace("\"","'"),
                r.getTrainId(), r.getSeatNumber(),
                r.getNewStatus() != null ? r.getNewStatus() : "N/A");
    }

    private static String listToJson(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(mapToJson(list.get(i)));
            if (i < list.size()-1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        List<Map.Entry<String,Object>> entries = new ArrayList<>(map.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Object v = entries.get(i).getValue();
            sb.append("\"").append(entries.get(i).getKey()).append("\":");
            if (v == null)              sb.append("null");
            else if (v instanceof Boolean) sb.append(v);
            else if (v instanceof Number)  sb.append(v);
            else sb.append("\"").append(v.toString().replace("\"","'")).append("\"");
            if (i < entries.size()-1) sb.append(",");
        }
        return sb.append("}").toString();
    }

    private static String err(String msg) {
        return "{\"success\":false,\"message\":\"" + msg + "\"}";
    }

    // ─────────────────────────────────────────────────────────────────
    //  HTTP helpers
    // ─────────────────────────────────────────────────────────────────

    private static void addEvent(String msg, String level) {
        String entry = "[" + LocalTime.now().format(TIME_FMT) + "] " + msg;
        synchronized (eventLog) {
            if (eventLog.size() >= MAX_EVENTS) eventLog.pollFirst();
            eventLog.addLast(entry);
        }
        System.out.println(entry);
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body); ex.getResponseBody().close();
    }

    private static void sendError(HttpExchange ex, int code) throws IOException {
        ex.sendResponseHeaders(code, 0); ex.getResponseBody().close();
    }

    private static Map<String, String> parseBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                map.put(kv[0].trim(), java.net.URLDecoder.decode(kv[1].trim(), StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String parseParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        try { return s != null ? Integer.parseInt(s) : def; } catch (NumberFormatException e) { return def; }
    }
}

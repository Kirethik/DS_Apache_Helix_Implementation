package com.trainbooking.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════
 * DatabaseManager — SQLite Persistence Layer
 * ═══════════════════════════════════════════════════════════════════
 *
 * Stores ALL system data in a single SQLite file: trainbooking.db
 *
 * Tables:
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ trains — train metadata (id, name, total seats, route) │
 * │ seats — live seat state per train (status, passenger) │
 * │ booking_events — full audit trail of every booking action │
 * │ zk_metrics — ZooKeeper write count per session │
 * │ cluster_events — leader election & failover log │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Data survives restarts — loaded back into memory on startup.
 *
 * DB file location: <working directory>/trainbooking.db
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private static final String DB_URL = "jdbc:sqlite:trainbooking.db";

    private static DatabaseManager instance;
    private Connection connection;

    // ── Singleton ─────────────────────────────────────────────────
    private DatabaseManager() {
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
            instance.init();
        }
        return instance;
    }

    // ── Initialisation ────────────────────────────────────────────

    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(DB_URL);
            connection.setAutoCommit(true);
            createTables();
            log.info("SQLite database initialised at: trainbooking.db");
            System.out.println("[DB] Connected to SQLite → trainbooking.db");
        } catch (Exception e) {
            log.error("Failed to initialise database: {}", e.getMessage());
            System.err.println("[DB] ERROR: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        Statement st = connection.createStatement();

        // ── trains table ──────────────────────────────────────────
        st.execute("""
                    CREATE TABLE IF NOT EXISTS trains (
                        train_id      TEXT PRIMARY KEY,
                        train_name    TEXT NOT NULL,
                        route         TEXT NOT NULL,
                        departure     TEXT,
                        arrival       TEXT,
                        total_seats   INTEGER NOT NULL,
                        created_at    TEXT DEFAULT (datetime('now','localtime'))
                    )
                """);

        // ── seats table ───────────────────────────────────────────
        st.execute("""
                    CREATE TABLE IF NOT EXISTS seats (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        train_id       TEXT NOT NULL,
                        seat_number    INTEGER NOT NULL,
                        seat_class     TEXT DEFAULT 'ECONOMY',
                        status         TEXT DEFAULT 'AVAILABLE',
                        passenger_name TEXT,
                        passenger_age  INTEGER,
                        passenger_id   TEXT,
                        reserved_at    TEXT,
                        confirmed_at   TEXT,
                        last_updated   TEXT DEFAULT (datetime('now','localtime')),
                        FOREIGN KEY (train_id) REFERENCES trains(train_id),
                        UNIQUE (train_id, seat_number)
                    )
                """);

        // ── booking_events table ──────────────────────────────────
        st.execute("""
                    CREATE TABLE IF NOT EXISTS booking_events (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        train_id       TEXT NOT NULL,
                        seat_number    INTEGER NOT NULL,
                        event_type     TEXT NOT NULL,
                        passenger_name TEXT,
                        old_status     TEXT,
                        new_status     TEXT,
                        success        INTEGER NOT NULL,
                        message        TEXT,
                        handled_by     TEXT,
                        event_time     TEXT DEFAULT (datetime('now','localtime'))
                    )
                """);

        // ── zk_metrics table ──────────────────────────────────────
        st.execute("""
                    CREATE TABLE IF NOT EXISTS zk_metrics (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        session_start    TEXT DEFAULT (datetime('now','localtime')),
                        total_events     INTEGER DEFAULT 0,
                        zk_no_batch      INTEGER DEFAULT 0,
                        zk_with_batch    INTEGER DEFAULT 0,
                        batch_size       INTEGER DEFAULT 10,
                        reduction_pct    REAL DEFAULT 0.0,
                        last_updated     TEXT DEFAULT (datetime('now','localtime'))
                    )
                """);

        // ── cluster_events table ──────────────────────────────────
        st.execute("""
                    CREATE TABLE IF NOT EXISTS cluster_events (
                        id           INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_name    TEXT NOT NULL,
                        partition    TEXT NOT NULL,
                        from_state   TEXT NOT NULL,
                        to_state     TEXT NOT NULL,
                        event_time   TEXT DEFAULT (datetime('now','localtime')),
                        description  TEXT
                    )
                """);

        st.close();
        System.out.println("[DB] All tables created / verified.");
    }

    // ═════════════════════════════════════════════════════════════
    // TRAIN operations
    // ═════════════════════════════════════════════════════════════

    /**
     * Insert or ignore a train record.
     */
    public void upsertTrain(String trainId, String trainName, String route,
            String departure, String arrival, int totalSeats) {
        String sql = """
                    INSERT OR IGNORE INTO trains
                        (train_id, train_name, route, departure, arrival, total_seats)
                    VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trainId);
            ps.setString(2, trainName);
            ps.setString(3, route);
            ps.setString(4, departure);
            ps.setString(5, arrival);
            ps.setInt(6, totalSeats);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("upsertTrain: {}", e.getMessage());
        }
    }

    /** Returns all trains as list of maps (for REST API). */
    public List<Map<String, Object>> getAllTrains() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM trains ORDER BY train_id")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trainId", rs.getString("train_id"));
                row.put("trainName", rs.getString("train_name"));
                row.put("route", rs.getString("route"));
                row.put("departure", rs.getString("departure"));
                row.put("arrival", rs.getString("arrival"));
                row.put("totalSeats", rs.getInt("total_seats"));
                row.put("createdAt", rs.getString("created_at"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getAllTrains: {}", e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // SEAT operations
    // ═════════════════════════════════════════════════════════════

    /**
     * Called once per train on startup — creates all seat rows.
     */
    public void initSeatsForTrain(String trainId, int totalSeats) {
        String sql = """
                    INSERT OR IGNORE INTO seats (train_id, seat_number, seat_class)
                    VALUES (?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 1; i <= totalSeats; i++) {
                ps.setString(1, trainId);
                ps.setInt(2, i);
                // First 10 = FIRST CLASS, next 10 = BUSINESS, rest = ECONOMY
                ps.setString(3, i <= 10 ? "FIRST" : i <= 20 ? "BUSINESS" : "ECONOMY");
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("initSeatsForTrain: {}", e.getMessage());
        }
    }

    /**
     * Update a seat's status and passenger info.
     */
    public void updateSeat(String trainId, int seatNumber, String status,
            String passengerName, String reservedAt, String confirmedAt) {
        String sql = """
                    UPDATE seats
                       SET status         = ?,
                           passenger_name = ?,
                           reserved_at    = ?,
                           confirmed_at   = ?,
                           last_updated   = datetime('now','localtime')
                     WHERE train_id = ? AND seat_number = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, passengerName);
            ps.setString(3, reservedAt);
            ps.setString(4, confirmedAt);
            ps.setString(5, trainId);
            ps.setInt(6, seatNumber);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("updateSeat: {}", e.getMessage());
        }
    }

    /**
     * Get all seats for a train (loaded on startup to restore state).
     */
    public List<Map<String, Object>> getSeatsForTrain(String trainId) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM seats WHERE train_id = ? ORDER BY seat_number";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trainId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seatNumber", rs.getInt("seat_number"));
                row.put("seatClass", rs.getString("seat_class"));
                row.put("status", rs.getString("status"));
                row.put("passengerName", rs.getString("passenger_name"));
                row.put("reservedAt", rs.getString("reserved_at"));
                row.put("confirmedAt", rs.getString("confirmed_at"));
                row.put("lastUpdated", rs.getString("last_updated"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getSeatsForTrain: {}", e.getMessage());
        }
        return result;
    }

    /** Get all seats across all trains (for the DB viewer). */
    public List<Map<String, Object>> getAllSeats() {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = """
                    SELECT s.*, t.train_name, t.route
                      FROM seats s JOIN trains t ON s.train_id = t.train_id
                     ORDER BY s.train_id, s.seat_number
                """;
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("trainId", rs.getString("train_id"));
                row.put("trainName", rs.getString("train_name"));
                row.put("route", rs.getString("route"));
                row.put("seatNumber", rs.getInt("seat_number"));
                row.put("seatClass", rs.getString("seat_class"));
                row.put("status", rs.getString("status"));
                row.put("passengerName", rs.getString("passenger_name"));
                row.put("reservedAt", rs.getString("reserved_at"));
                row.put("confirmedAt", rs.getString("confirmed_at"));
                row.put("lastUpdated", rs.getString("last_updated"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getAllSeats: {}", e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // BOOKING EVENTS
    // ═════════════════════════════════════════════════════════════

    public void insertBookingEvent(String trainId, int seatNumber, String eventType,
            String passengerName, String oldStatus, String newStatus,
            boolean success, String message, String handledBy) {
        String sql = """
                    INSERT INTO booking_events
                        (train_id, seat_number, event_type, passenger_name,
                         old_status, new_status, success, message, handled_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, trainId);
            ps.setInt(2, seatNumber);
            ps.setString(3, eventType);
            ps.setString(4, passengerName);
            ps.setString(5, oldStatus);
            ps.setString(6, newStatus);
            ps.setInt(7, success ? 1 : 0);
            ps.setString(8, message);
            ps.setString(9, handledBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("insertBookingEvent: {}", e.getMessage());
        }
    }

    /** Get recent booking events (last N). */
    public List<Map<String, Object>> getRecentBookingEvents(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT * FROM booking_events ORDER BY id DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("trainId", rs.getString("train_id"));
                row.put("seatNumber", rs.getInt("seat_number"));
                row.put("eventType", rs.getString("event_type"));
                row.put("passengerName", rs.getString("passenger_name"));
                row.put("oldStatus", rs.getString("old_status"));
                row.put("newStatus", rs.getString("new_status"));
                row.put("success", rs.getInt("success") == 1);
                row.put("message", rs.getString("message"));
                row.put("handledBy", rs.getString("handled_by"));
                row.put("eventTime", rs.getString("event_time"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getRecentBookingEvents: {}", e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // ZK METRICS
    // ═════════════════════════════════════════════════════════════

    private int currentMetricId = -1;

    public void startMetricsSession() {
        String sql = "INSERT INTO zk_metrics (session_start) VALUES (datetime('now','localtime'))";
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
            ResultSet rs = st.executeQuery("SELECT last_insert_rowid()");
            if (rs.next())
                currentMetricId = rs.getInt(1);
            System.out.println("[DB] New metrics session started, id=" + currentMetricId);
        } catch (SQLException e) {
            log.error("startMetricsSession: {}", e.getMessage());
        }
    }

    public void updateMetrics(long totalEvents, long zkNoBatch, long zkBatch) {
        if (currentMetricId < 0)
            return;
        double reduction = zkNoBatch > 0 ? (100.0 * (zkNoBatch - zkBatch) / zkNoBatch) : 90.0;
        String sql = """
                    UPDATE zk_metrics
                       SET total_events  = ?,
                           zk_no_batch   = ?,
                           zk_with_batch = ?,
                           reduction_pct = ?,
                           last_updated  = datetime('now','localtime')
                     WHERE id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, totalEvents);
            ps.setLong(2, zkNoBatch);
            ps.setLong(3, zkBatch);
            ps.setDouble(4, reduction);
            ps.setInt(5, currentMetricId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("updateMetrics: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getAllMetricsSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM zk_metrics ORDER BY id DESC")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("sessionStart", rs.getString("session_start"));
                row.put("totalEvents", rs.getLong("total_events"));
                row.put("zkNoBatch", rs.getLong("zk_no_batch"));
                row.put("zkWithBatch", rs.getLong("zk_with_batch"));
                row.put("reductionPct", rs.getDouble("reduction_pct"));
                row.put("lastUpdated", rs.getString("last_updated"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getAllMetricsSessions: {}", e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // CLUSTER EVENTS
    // ═════════════════════════════════════════════════════════════

    public void insertClusterEvent(String node, String partition,
            String fromState, String toState, String description) {
        String sql = """
                    INSERT INTO cluster_events (node_name, partition, from_state, to_state, description)
                    VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, node);
            ps.setString(2, partition);
            ps.setString(3, fromState);
            ps.setString(4, toState);
            ps.setString(5, description);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("insertClusterEvent: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getAllClusterEvents() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM cluster_events ORDER BY id DESC LIMIT 50")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("nodeName", rs.getString("node_name"));
                row.put("partition", rs.getString("partition"));
                row.put("fromState", rs.getString("from_state"));
                row.put("toState", rs.getString("to_state"));
                row.put("eventTime", rs.getString("event_time"));
                row.put("description", rs.getString("description"));
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("getAllClusterEvents: {}", e.getMessage());
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════
    // STATS / SUMMARY
    // ═════════════════════════════════════════════════════════════

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (Statement st = connection.createStatement()) {
            ResultSet rs;

            rs = st.executeQuery("SELECT COUNT(*) FROM trains");
            stats.put("totalTrains", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM seats");
            stats.put("totalSeats", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE status='CONFIRMED'");
            stats.put("confirmedSeats", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE status='RESERVED'");
            stats.put("reservedSeats", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE status='AVAILABLE'");
            stats.put("availableSeats", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE status='CANCELLED'");
            stats.put("cancelledSeats", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM booking_events");
            stats.put("totalBookingEvents", rs.next() ? rs.getInt(1) : 0);

            rs = st.executeQuery("SELECT COUNT(*) FROM cluster_events");
            stats.put("totalClusterEvents", rs.next() ? rs.getInt(1) : 0);

        } catch (SQLException e) {
            log.error("getStats: {}", e.getMessage());
        }
        return stats;
    }

    /** Reset all seat data (but keep train info and history). */
    public void resetSeatsOnly() {
        try (Statement st = connection.createStatement()) {
            st.execute("UPDATE seats SET status='AVAILABLE', passenger_name=NULL, reserved_at=NULL, confirmed_at=NULL");
            log.info("All seats reset to AVAILABLE in database.");
        } catch (SQLException e) {
            log.error("resetSeatsOnly: {}", e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null)
                connection.close();
        } catch (SQLException e) {
            log.error("close: {}", e.getMessage());
        }
    }
}

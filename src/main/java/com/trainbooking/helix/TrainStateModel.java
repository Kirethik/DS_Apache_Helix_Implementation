package com.trainbooking.helix;

import com.trainbooking.service.BookingService;
import com.trainbooking.batching.BatchedUpdateManager;
import org.apache.helix.NotificationContext;
import org.apache.helix.model.Message;
import org.apache.helix.participant.statemachine.StateModel;
import org.apache.helix.participant.statemachine.StateModelInfo;
import org.apache.helix.participant.statemachine.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TrainStateModel — Helix state model for a single train partition.
 *
 * Helix drives partition lifecycle through state transitions.
 * Each train partition goes through:
 *
 * OFFLINE → SLAVE : Node joins the cluster as a read-only replica
 * SLAVE → MASTER : Algorithm 1 — Leader Election: node promoted to handle
 * bookings
 * MASTER → SLAVE : Node demoted (another node takes over)
 * SLAVE → OFFLINE : Node leaves gracefully
 * OFFLINE → DROPPED: Partition removed from this node
 *
 * The @StateModelInfo annotation registers this state machine with Helix.
 */
@StateModelInfo(states = { "OFFLINE", "SLAVE", "MASTER" }, initialState = "OFFLINE")
public class TrainStateModel extends StateModel {

    private static final Logger log = LoggerFactory.getLogger(TrainStateModel.class);

    private final String partitionName; // e.g. "Train_101"
    private final String nodeName; // e.g. "Node1"
    private BookingService bookingService;
    private BatchedUpdateManager batchManager;

    // Number of seats per partition
    private static final int SEATS_PER_TRAIN = 30;

    public TrainStateModel(String partitionName, String nodeName) {
        this.partitionName = partitionName;
        this.nodeName = nodeName;
    }

    // ──────────────────────────────────────────────────────────────
    // ALGORITHM 1 — Leader Election
    // Helix controller monitors ZooKeeper ephemeral nodes to detect
    // failures and then transitions a SLAVE to MASTER automatically.
    // ──────────────────────────────────────────────────────────────

    /**
     * OFFLINE → SLAVE
     * Node starts replicating seat data from the current MASTER.
     */
    @Transition(from = "OFFLINE", to = "SLAVE")
    public void onBecomeSlaveFromOffline(Message msg, NotificationContext ctx) {
        log.info("[{}][{}] ── OFFLINE → SLAVE  (Replica mode ON)", nodeName, partitionName);
        System.out.printf("[%s][%s] transitioned to SLAVE — now replicating seat data.%n",
                nodeName, partitionName);
    }

    /**
     * SLAVE → MASTER (Leader Election: this node wins)
     * Helix has elected this node as the partition leader.
     * Start the BookingService and BatchedUpdateManager.
     */
    @Transition(from = "SLAVE", to = "MASTER")
    public void onBecomeMasterFromSlave(Message msg, NotificationContext ctx) {
        log.info("[{}][{}] ══ SLAVE → MASTER  (Leader Election Won! Booking ACTIVE)", nodeName, partitionName);
        System.out.println();
        System.out.printf("  ╔══════════════════════════════════════════════╗%n");
        System.out.printf("  ║  [Leader Election] %s is now MASTER for %s%n", nodeName, partitionName);
        System.out.printf("  ║  Booking requests will be handled here.      %n");
        System.out.printf("  ╚══════════════════════════════════════════════╝%n");
        System.out.println();

        // Initialize the booking engine for this partition
        bookingService = new BookingService(partitionName, SEATS_PER_TRAIN);

        // Batch manager: enabled by default (can be toggled in demo)
        batchManager = new BatchedUpdateManager(true, 10, 2000);
    }

    /**
     * MASTER → SLAVE (Leadership lost or voluntary step-down)
     * Stop processing bookings; another node will become MASTER.
     */
    @Transition(from = "MASTER", to = "SLAVE")
    public void onBecomeSlaveFromMaster(Message msg, NotificationContext ctx) {
        log.warn("[{}][{}] ── MASTER → SLAVE  (Leadership transferred)", nodeName, partitionName);
        System.out.printf("[%s][%s] stepped down from MASTER — replication mode.%n",
                nodeName, partitionName);
        if (batchManager != null) {
            batchManager.shutdown();
            batchManager = null;
        }
        bookingService = null;
    }

    /**
     * SLAVE → OFFLINE
     * Node is leaving the cluster.
     */
    @Transition(from = "SLAVE", to = "OFFLINE")
    public void onBecomeOfflineFromSlave(Message msg, NotificationContext ctx) {
        log.info("[{}][{}] ── SLAVE → OFFLINE  (Node going offline)", nodeName, partitionName);
        System.out.printf("[%s][%s] offline.%n", nodeName, partitionName);
    }

    /**
     * OFFLINE → DROPPED
     * Partition removed from this node entirely.
     */
    @Transition(from = "OFFLINE", to = "DROPPED")
    public void onBecomeDroppedFromOffline(Message msg, NotificationContext ctx) {
        log.info("[{}][{}] ── OFFLINE → DROPPED  (Partition removed)", nodeName, partitionName);
    }

    // ──────────────────────────────────────────────────────────────
    // Accessors
    // ──────────────────────────────────────────────────────────────

    public boolean isMaster() {
        return bookingService != null;
    }

    public BookingService getBookingService() {
        return bookingService;
    }

    public BatchedUpdateManager getBatchManager() {
        return batchManager;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public String getNodeName() {
        return nodeName;
    }
}

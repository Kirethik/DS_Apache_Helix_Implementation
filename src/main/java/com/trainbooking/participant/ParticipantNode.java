package com.trainbooking.participant;

import com.trainbooking.helix.TrainStateModel;
import com.trainbooking.helix.TrainStateModelFactory;
import com.trainbooking.service.BookingService;
import com.trainbooking.setup.ClusterSetup;
import com.trainbooking.batching.BatchedUpdateManager;
import com.trainbooking.model.BookingResult;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * ParticipantNode — Represents a single node in the distributed cluster.
 *
 * Each node:
 * 1. Connects to ZooKeeper via Apache Helix
 * 2. Registers the TrainStateModelFactory
 * 3. Receives partition assignments from the Controller
 * 4. Runs booking logic when it holds MASTER role for a partition
 *
 * Run 3 instances (in separate terminals):
 * java -jar ParticipantNode.jar localhost:2181 Node1
 * java -jar ParticipantNode.jar localhost:2181 Node2
 * java -jar ParticipantNode.jar localhost:2181 Node3
 */
public class ParticipantNode {

    private static final Logger log = LoggerFactory.getLogger(ParticipantNode.class);

    private final String nodeName;
    private final String zkAddr;
    private HelixManager helixManager;
    private TrainStateModelFactory stateModelFactory;

    public ParticipantNode(String zkAddr, String nodeName) {
        this.zkAddr = zkAddr;
        this.nodeName = nodeName;
    }

    public void start() throws Exception {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  Participant Node Starting: " + nodeName);
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  ZooKeeper : " + zkAddr);
        System.out.println("║  Cluster   : " + ClusterSetup.CLUSTER_NAME);
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        stateModelFactory = new TrainStateModelFactory(nodeName);

        helixManager = HelixManagerFactory.getZKHelixManager(
                ClusterSetup.CLUSTER_NAME,
                nodeName,
                InstanceType.PARTICIPANT,
                zkAddr);

        // Register our state model factory so Helix knows how to handle
        // partition state transitions for TrainBookingService
        helixManager.getStateMachineEngine().registerStateModelFactory(
                ClusterSetup.STATE_MODEL, stateModelFactory);

        helixManager.connect();
        log.info("[{}] Connected to ZooKeeper cluster.", nodeName);
        System.out.println("[" + nodeName + "] Connected to cluster. Waiting for partition assignments...");
        System.out.println("[" + nodeName + "] Press Ctrl+C to simulate node failure (failover demo).");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[" + nodeName + "] Node shutting down...");
            if (helixManager != null)
                helixManager.disconnect();
        }));
    }

    /**
     * Returns the state model for a given partition (used by BookingClient for
     * demo).
     */
    public TrainStateModel getStateModelForPartition(String partitionName) {
        return stateModelFactory.getStateModel(ClusterSetup.RESOURCE_NAME, partitionName);
    }

    public TrainStateModelFactory getStateModelFactory() {
        return stateModelFactory;
    }

    public String getNodeName() {
        return nodeName;
    }

    // ─────────────────────────────────────────────────────────────────
    // main() — runs this node as a standalone process
    // ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java -jar ParticipantNode.jar <zkAddress> <nodeName>");
            System.out.println("  e.g: java -jar ParticipantNode.jar localhost:2181 Node1");
            System.exit(1);
        }

        String zkAddr = args[0];
        String nodeName = args[1];

        ParticipantNode node = new ParticipantNode(zkAddr, nodeName);
        node.start();

        // Keep the node alive; block until Ctrl+C
        Thread.currentThread().join();
    }
}

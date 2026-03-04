package com.trainbooking.controller;

import com.trainbooking.setup.ClusterSetup;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TrainControllerApp — Starts the Helix Cluster Controller.
 *
 * The controller monitors ZooKeeper for node health, computes
 * optimal partition-to-node assignments (ideal state), and issues
 * state transition messages to participants when the cluster topology
 * changes (e.g., a node crashes).
 *
 * Algorithm 1 — Leader Election is orchestrated here.
 *
 * Run: java -jar HelixController.jar [zkAddress]
 * e.g. java -jar HelixController.jar localhost:2181
 */
public class TrainControllerApp {

    private static final Logger log = LoggerFactory.getLogger(TrainControllerApp.class);
    private static final String CONTROLLER_NAME = "TrainController";

    public static void main(String[] args) throws Exception {
        String zkAddr = (args.length > 0) ? args[0] : "localhost:2181";

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           Helix Cluster Controller Starting              ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Cluster    : " + ClusterSetup.CLUSTER_NAME);
        System.out.println("║  Controller : " + CONTROLLER_NAME);
        System.out.println("║  ZooKeeper  : " + zkAddr);
        System.out.println("║");
        System.out.println("║  Responsibilities:");
        System.out.println("║   • Monitor participant health via ZooKeeper watches");
        System.out.println("║   • Compute ideal partition → node mapping");
        System.out.println("║   • Elect MASTER per partition (Leader Election)");
        System.out.println("║   • Drive failover state transitions on node failure");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        HelixManager manager = HelixManagerFactory.getZKHelixManager(
                ClusterSetup.CLUSTER_NAME,
                CONTROLLER_NAME,
                InstanceType.CONTROLLER,
                zkAddr);

        manager.connect();
        log.info("Helix Controller connected and running for cluster '{}'.", ClusterSetup.CLUSTER_NAME);
        System.out.println("[Controller] Connected to ZooKeeper at " + zkAddr);
        System.out.println("[Controller] Watching cluster '" + ClusterSetup.CLUSTER_NAME + "'...");
        System.out.println("[Controller] Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Controller] Shutting down...");
            manager.disconnect();
            log.info("Helix Controller disconnected.");
        }));

        // Keep the controller alive
        Thread.currentThread().join();
    }
}

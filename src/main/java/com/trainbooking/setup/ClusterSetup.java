package com.trainbooking.setup;

import org.apache.helix.HelixAdmin;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.tools.StateModelConfigGenerator;
import org.apache.helix.model.IdealState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClusterSetup — One-time initialization of the Helix cluster.
 *
 * Executed ONCE before starting participant nodes.
 * Creates:
 * - Cluster "TrainCluster"
 * - Nodes: Node1, Node2, Node3
 * - Resource: TrainBookingService
 * - Partitions: Train_101, Train_202, Train_303, Train_404
 * - Replication: 1 MASTER + 2 SLAVES per partition
 *
 * Run: java -jar ClusterSetup.jar [zookeeperAddress]
 * e.g. java -jar ClusterSetup.jar localhost:2181
 */

public class ClusterSetup {

    public static final String CLUSTER_NAME = "TrainCluster";
    public static final String RESOURCE_NAME = "TrainBookingService";
    public static final String STATE_MODEL = "MasterSlave";
    public static final int NUM_PARTITIONS = 4;
    public static final int REPLICATION = 3; // 1 MASTER + 2 SLAVES

    public static final List<String> NODES = Arrays.asList("Node1", "Node2", "Node3");
    public static final List<String> TRAINS = Arrays.asList(
            "Train_101", "Train_202", "Train_303", "Train_404");

    // Default ports (one per node — real Helix needs unique ports per instance)
    private static final Map<String, Integer> NODE_PORTS = new HashMap<>();
    static {
        NODE_PORTS.put("Node1", 12000);
        NODE_PORTS.put("Node2", 12001);
        NODE_PORTS.put("Node3", 12002);
    }

    public static void main(String[] args) throws Exception {
        String zkAddr = (args.length > 0) ? args[0] : "localhost:2181";

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   Distributed Train Booking — Helix Cluster Setup        ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  ZooKeeper : " + padLine(zkAddr, 46) + "║");
        System.out.println("║  Cluster   : " + padLine(CLUSTER_NAME, 46) + "║");
        System.out.println("║  Resource  : " + padLine(RESOURCE_NAME, 46) + "║");
        System.out.println("║  Partitions: " + padLine(NUM_PARTITIONS + " trains", 46) + "║");
        System.out.println("║  Replicas  : " + padLine(REPLICATION + " (1 MASTER + 2 SLAVES)", 46) + "║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        HelixAdmin admin = new ZKHelixAdmin(zkAddr);

        try {
            // ── Step 1: Create cluster ──────────────────────────────────
            System.out.print("[1/5] Creating cluster '" + CLUSTER_NAME + "'...");
            if (admin.getClusters().contains(CLUSTER_NAME)) {
                System.out.println(" already exists (skipping).");
            } else {
                admin.addCluster(CLUSTER_NAME, true);
                System.out.println(" DONE.");
            }

            // ── Step 2: Register state model definition ─────────────────
            System.out.print("[2/5] Registering MasterSlave state model...");
            StateModelDefinition smd = new StateModelDefinition(
                    StateModelConfigGenerator.generateConfigForMasterSlave());
            try {
                admin.addStateModelDef(CLUSTER_NAME, STATE_MODEL, smd);
                System.out.println(" DONE.");
            } catch (Exception e) {
                System.out.println(" already registered (skipping).");
            }

            // ── Step 3: Add participant nodes ───────────────────────────
            System.out.println("[3/5] Registering participant nodes:");
            List<String> existingInstances = admin.getInstancesInCluster(CLUSTER_NAME);
            for (String node : NODES) {
                int port = NODE_PORTS.getOrDefault(node, 12000);
                if (existingInstances.contains(node)) {
                    System.out.println("      • " + node + ":" + port + " — already exists (skipping).");
                } else {
                    InstanceConfig cfg = new InstanceConfig(node);
                    cfg.setHostName("localhost");
                    cfg.setPort(String.valueOf(port));
                    cfg.setInstanceEnabled(true);
                    admin.addInstance(CLUSTER_NAME, cfg);
                    System.out.println("      • " + node + ":" + port + " — registered.");
                }
            }

            // ── Step 4: Add resource (train partitions) ─────────────────
            System.out.print("[4/5] Creating resource '" + RESOURCE_NAME + "'...");
            List<String> existingResources = admin.getResourcesInCluster(CLUSTER_NAME);
            if (existingResources.contains(RESOURCE_NAME)) {
                System.out.println(" already exists (skipping).");
            } else {
                admin.addResource(CLUSTER_NAME, RESOURCE_NAME, NUM_PARTITIONS,
                        STATE_MODEL, IdealState.RebalanceMode.FULL_AUTO.toString());
                System.out.println(" DONE.");
            }

            // ── Step 5: Rebalance ───────────────────────────────────────
            System.out.print("[5/5] Rebalancing partitions across nodes...");
            admin.rebalance(CLUSTER_NAME, RESOURCE_NAME, REPLICATION);
            System.out.println(" DONE.");

            System.out.println();
            System.out.println("────────────────────────────────────────────────────────");
            System.out.println("  Cluster setup complete! Expected partition mapping:");
            System.out.println("────────────────────────────────────────────────────────");

            // Print ideal state
            IdealState is = admin.getResourceIdealState(CLUSTER_NAME, RESOURCE_NAME);
            if (is != null) {
                for (String partition : is.getPartitionSet()) {
                    Map<String, String> stateMap = is.getInstanceStateMap(partition);
                    System.out.printf("  %-12s → %s%n", partition, stateMap);
                }
            }
            System.out.println("────────────────────────────────────────────────────────");
            System.out.println();
            System.out.println("  Next steps:");
            System.out.println("  1. Start HelixController : java -jar HelixController.jar localhost:2181");
            System.out.println("  2. Start Node1           : java -jar ParticipantNode.jar localhost:2181 Node1");
            System.out.println("  3. Start Node2           : java -jar ParticipantNode.jar localhost:2181 Node2");
            System.out.println("  4. Start Node3           : java -jar ParticipantNode.jar localhost:2181 Node3");
            System.out.println("  5. Run booking demo      : java -jar BookingClient.jar localhost:2181");
            System.out.println();

        } finally {
            admin.close();
        }
    }

    private static String padLine(String s, int width) {
        if (s.length() >= width)
            return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}

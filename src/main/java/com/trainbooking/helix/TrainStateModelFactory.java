package com.trainbooking.helix;

import org.apache.helix.participant.statemachine.StateModelFactory;

/**
 * Factory that creates a TrainStateModel instance for each partition
 * assigned to this participant node.
 *
 * Helix calls createNewStateModel() whenever it assigns a new partition
 * (e.g., Train_101, Train_202) to this node.
 */
public class TrainStateModelFactory extends StateModelFactory<TrainStateModel> {

    private final String nodeName;

    public TrainStateModelFactory(String nodeName) {
        this.nodeName = nodeName;
    }

    @Override
    public TrainStateModel createNewStateModel(String resourceName, String partitionName) {
        return new TrainStateModel(partitionName, nodeName);
    }
}

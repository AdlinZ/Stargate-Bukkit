package org.sgrewritten.stargate.util;

import org.sgrewritten.stargate.thread.task.StargateQueuedAsyncTask;
import org.sgrewritten.stargate.thread.task.StargateRegionTask;
import org.sgrewritten.stargate.thread.task.StargateTask;

public class StargateTestHelper {
    public static void runAllTasks() {
        // Wait for active database work, not just an empty pending queue.
        StargateQueuedAsyncTask.waitForEmptyQueue();
        StargateTask.forceRunAllTasks();
        StargateRegionTask.clearPopulator();
    }
}

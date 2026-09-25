package org.sgrewritten.stargate.util;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.sgrewritten.stargate.Stargate;
import org.sgrewritten.stargate.thread.task.StargateQueuedAsyncTask;
import org.sgrewritten.stargate.thread.task.StargateRegionTask;
import org.sgrewritten.stargate.thread.task.StargateTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class StargateTestHelper {
    public static void runAllTasks() {
        // An empty queue can still have a task executing. Wait for a marker to
        // run on the same worker before inspecting the effects of earlier tasks.
        CompletableFuture<Void> drained = new CompletableFuture<>();
        StargateQueuedAsyncTask.asyncQueue.add(() -> drained.complete(null));
        try {
            drained.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for queued test tasks", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Queued test tasks did not finish", e);
        }
        try {
            StargateTask.forceRunAllTasks();
        } catch (Exception e) {
            Stargate.log(e);
        }
        StargateRegionTask.clearPopulator();
    }
}

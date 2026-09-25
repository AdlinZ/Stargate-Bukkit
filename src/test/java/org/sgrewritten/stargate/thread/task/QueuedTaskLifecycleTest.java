package org.sgrewritten.stargate.thread.task;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QueuedTaskLifecycleTest {
    private ServerMock server;
    private static final long ID = 123456;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        StargateQueuedAsyncTask.enableAsyncQueue(ID);
    }

    @AfterEach
    void tearDown() {
        StargateTask.cancelScheduledTasks();
        StargateQueuedAsyncTask.disableAsyncQueue(ID);
        MockBukkit.unmock();
    }

    private static StargateQueuedAsyncTask task(Runnable action) {
        return new StargateQueuedAsyncTask() {
            @Override public void run() { action.run(); }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void shutdownWaitsForActiveAndPendingWritesOnTheSameWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> order = new ArrayList<>();
        AtomicReference<Thread> databaseThread = new AtomicReference<>();
        task(() -> {
            databaseThread.set(Thread.currentThread());
            entered.countDown();
            await(release);
            order.add(1);
        }).runNow();
        await(entered);
        task(() -> {
            assertSame(databaseThread.get(), Thread.currentThread());
            order.add(2);
        }).runNow();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> StargateQueuedAsyncTask.disableAsyncQueue(ID));
        try {
            assertThrows(TimeoutException.class, () -> stopping.get(50, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        stopping.get(2, TimeUnit.SECONDS);
        assertEquals(List.of(1, 2), order);
        assertNotSame(Thread.currentThread(), databaseThread.get());
    }

    @Test
    void emptyQueueBarrierStillWaitsForTheInFlightWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        task(() -> { entered.countDown(); await(release); }).runNow();
        await(entered);
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(StargateQueuedAsyncTask::waitForEmptyQueue);
        try {
            assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
        }
        waiting.get(2, TimeUnit.SECONDS);
    }

    @Test
    void cancelledQueuedWriteDoesNotRun() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        task(() -> { entered.countDown(); await(release); }).runNow();
        await(entered);
        try {
            StargateQueuedAsyncTask cancelled = task(runs::incrementAndGet);
            cancelled.runNow();
            cancelled.cancel();
        } finally {
            release.countDown();
        }
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(0, runs.get());
    }

    @Test
    void queuedDelayStaysSerializedBehindActiveWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> databaseThread = new AtomicReference<>();
        CompletableFuture<Thread> delayedThread = new CompletableFuture<>();
        task(() -> {
            databaseThread.set(Thread.currentThread());
            entered.countDown();
            await(release);
        }).runNow();
        await(entered);
        try {
            task(() -> delayedThread.complete(Thread.currentThread())).runDelayed(2);
            server.getScheduler().performTicks(2);
            server.getScheduler().waitAsyncTasksFinished();
            assertFalse(delayedThread.isDone());
        } finally {
            release.countDown();
        }
        assertSame(databaseThread.get(), delayedThread.get(2, TimeUnit.SECONDS));
    }

    @Test
    void cancelledDelayedWriteNeverEntersQueue() {
        AtomicInteger runs = new AtomicInteger();
        StargateQueuedAsyncTask delayed = task(runs::incrementAndGet);
        delayed.runDelayed(2);
        delayed.cancel();
        server.getScheduler().performTicks(3);
        server.getScheduler().waitAsyncTasksFinished();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(0, runs.get());
    }

    @Test
    void repeatedQueuedWorkStopsWhenParentIsCancelled() {
        AtomicInteger runs = new AtomicInteger();
        StargateQueuedAsyncTask repeating = task(runs::incrementAndGet);
        repeating.runTaskTimer(1, 0);
        server.getScheduler().performOneTick();
        server.getScheduler().waitAsyncTasksFinished();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(1, runs.get());
        repeating.cancel();
        server.getScheduler().performTicks(3);
        server.getScheduler().waitAsyncTasksFinished();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(1, runs.get());
    }

    @Test
    void staleShutdownIdCannotStopCurrentWorker() {
        StargateQueuedAsyncTask.disableAsyncQueue(ID - 1);
        AtomicInteger runs = new AtomicInteger();
        task(runs::incrementAndGet).runNow();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(1, runs.get());
    }

    @Test
    void stoppedQueueRejectsNewWorkAndCanRestartWithoutOldSentinels() {
        StargateQueuedAsyncTask.disableAsyncQueue(ID);
        AtomicInteger runs = new AtomicInteger();
        task(runs::incrementAndGet).runNow();
        assertEquals(0, runs.get());
        StargateQueuedAsyncTask.enableAsyncQueue(ID);
        task(runs::incrementAndGet).runNow();
        StargateQueuedAsyncTask.waitForEmptyQueue();
        assertEquals(1, runs.get());
    }

    @Test
    void shutdownTimeoutKeepsAcceptedWritesOnWorkerAndPreventsOverlappingRestart() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        task(() -> { entered.countDown(); await(release); runs.incrementAndGet(); }).runNow();
        await(entered);
        try {
            StargateQueuedAsyncTask.disableAsyncQueue(ID, 20);
            assertEquals(0, runs.get());
            assertThrows(IllegalStateException.class, () -> StargateQueuedAsyncTask.enableAsyncQueue(ID));
        } finally {
            release.countDown();
            StargateQueuedAsyncTask.disableAsyncQueue(ID);
        }
        assertEquals(1, runs.get());
    }

    @Test
    void shutdownCancellationDoesNotExecuteWorldWorkInline() {
        AtomicInteger worldRuns = new AtomicInteger();
        AtomicInteger databaseRuns = new AtomicInteger();
        new StargateGlobalTask() {
            @Override public void run() { worldRuns.incrementAndGet(); }
        }.runDelayed(100);
        task(databaseRuns::incrementAndGet).runNow();
        StargateTask.cancelScheduledTasks();
        StargateQueuedAsyncTask.disableAsyncQueue(ID);
        server.getScheduler().performTicks(101);
        assertEquals(0, worldRuns.get());
        assertEquals(1, databaseRuns.get());
    }
}

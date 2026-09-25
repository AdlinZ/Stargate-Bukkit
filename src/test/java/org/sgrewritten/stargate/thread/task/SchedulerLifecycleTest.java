package org.sgrewritten.stargate.thread.task;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sgrewritten.stargate.Stargate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerLifecycleTest {
    private final List<Call> calls = new ArrayList<>();
    private ServerMock server;
    private boolean retire;
    private boolean runBeforeReturn;

    private static class Call {
        final Object[] args;
        boolean cancelled;
        final ScheduledTask handle = proxy(ScheduledTask.class, (method, args) -> {
            if (method.equals("cancel")) {
                cancelled = true;
                return ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
            }
            throw new AssertionError(method);
        });

        Call(Object[] args) {
            this.args = args;
        }

        @SuppressWarnings("unchecked")
        void fire() {
            ((Consumer<ScheduledTask>) args[1]).accept(handle);
        }
    }

    private interface Invocation {
        Object call(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.call(method.getName(), args));
    }

    private <T> T scheduler(Class<T> type) {
        return proxy(type, (method, args) -> {
            Call call = new Call(args);
            calls.add(call);
            if (runBeforeReturn) {
                call.fire();
            }
            return retire ? null : call.handle;
        });
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new ServerMock() {
            @Override
            public AsyncScheduler getAsyncScheduler() {
                return scheduler(AsyncScheduler.class);
            }
            @Override
            public GlobalRegionScheduler getGlobalRegionScheduler() {
                return scheduler(GlobalRegionScheduler.class);
            }
        });
        Stargate.setKnowsServerName(false);
    }

    @AfterEach
    void tearDown() {
        StargateTask.cancelScheduledTasks();
        MockBukkit.unmock();
    }

    private StargateEntityTask entityTask(AtomicInteger runs) {
        Entity entity = proxy(Entity.class, (method, args) -> {
            assertEquals("getScheduler", method);
            return scheduler(EntityScheduler.class);
        });
        return new StargateEntityTask(entity, true) {
            @Override public void run() { runs.incrementAndGet(); }
        };
    }

    private StargateAsyncTask asyncTask() {
        return new StargateAsyncTask(true) {
            @Override public void run() { }
        };
    }

    @Test
    void entityCancellationBeforeFirstTickCancelsReturnedHandleAndIgnoresLateCallback() {
        AtomicInteger runs = new AtomicInteger();
        StargateEntityTask task = entityTask(runs);
        task.runDelayed(10);
        task.cancel();
        assertTrue(calls.getFirst().cancelled);
        assertDoesNotThrow(calls.getFirst()::fire);
        assertEquals(0, runs.get());
    }

    @Test
    void cancellationBeforeSchedulingIsSafe() {
        StargateEntityTask task = entityTask(new AtomicInteger());
        assertDoesNotThrow(task::cancel);
        task.runNow();
        assertTrue(calls.isEmpty());
    }

    @Test
    void retiredEntityDoesNotLeaveARegisteredTask() {
        retire = true;
        AtomicInteger runs = new AtomicInteger();
        StargateEntityTask task = entityTask(runs);
        task.runNow();
        retire = false;
        task.runNow();
        assertEquals(1, calls.size());
        calls.getFirst().fire();
        assertEquals(0, runs.get());
    }

    @Test
    void entityRetirementCancelsRepeatingHandle() {
        AtomicInteger runs = new AtomicInteger();
        StargateEntityTask task = entityTask(runs);
        task.runTaskTimer(20, 0);
        assertEquals(1L, calls.getFirst().args[3]);
        calls.getFirst().fire();
        ((Runnable) calls.getFirst().args[2]).run();
        assertTrue(calls.getFirst().cancelled);
        calls.getFirst().fire();
        assertEquals(1, runs.get());
    }

    @Test
    void lateHandleRegistrationAfterCompletionStillCancelsHandle() {
        runBeforeReturn = true;
        AtomicInteger runs = new AtomicInteger();
        entityTask(runs).runNow();
        assertTrue(calls.getFirst().cancelled);
        calls.getFirst().fire();
        assertEquals(1, runs.get());
    }

    @Test
    void repeatingTasksRemainTrackedAfterTheirFirstCallback() {
        AtomicInteger runs = new AtomicInteger();
        entityTask(runs).runTaskTimer(20, 1);
        calls.getFirst().fire();
        StargateTask.cancelScheduledTasks();
        assertTrue(calls.getFirst().cancelled);
        calls.getFirst().fire();
        assertEquals(1, runs.get());
    }

    @Test
    void duplicateSubmissionsAreBothCancelledAndOnlyExecuteOnce() {
        AtomicInteger runs = new AtomicInteger();
        StargateEntityTask task = entityTask(runs);
        task.runNow();
        task.runNow();
        calls.forEach(Call::fire);
        assertEquals(1, runs.get());
        assertTrue(calls.stream().allMatch(call -> call.cancelled));
    }

    @Test
    void foliaAsyncDelayUsesTicksConvertedToMilliseconds() {
        asyncTask().runDelayed(20);
        assertEquals(1000L, calls.getFirst().args[2]);
        assertEquals(TimeUnit.MILLISECONDS, calls.getFirst().args[3]);
    }

    @Test
    void foliaAsyncPeriodAndDelayBothUseTicks() {
        asyncTask().runTaskTimer(20, 2);
        assertEquals(100L, calls.getFirst().args[2]);
        assertEquals(1000L, calls.getFirst().args[3]);
        assertEquals(TimeUnit.MILLISECONDS, calls.getFirst().args[4]);
    }

    @Test
    void paperAsyncTimerDoesNotRunOnTheMainThread() throws Exception {
        CompletableFuture<Thread> callback = new CompletableFuture<>();
        Thread main = Thread.currentThread();
        StargateAsyncTask task = new StargateAsyncTask(false) {
            @Override public void run() { callback.complete(Thread.currentThread()); }
        };
        task.runTaskTimer(20, 0);
        server.getScheduler().performOneTick();
        assertNotSame(main, callback.get(2, TimeUnit.SECONDS));
        task.cancel();
    }

    @Test
    void bungeePollingStopsAfterDelivery() {
        AtomicInteger runs = new AtomicInteger();
        new StargateGlobalTask(true, true) {
            @Override public void run() { runs.incrementAndGet(); }
        }.runNow();
        calls.getFirst().fire();
        assertEquals(0, runs.get());
        server.addPlayer();
        Stargate.setKnowsServerName(true);
        calls.getFirst().fire();
        assertEquals(1, runs.get());
        assertTrue(calls.getFirst().cancelled);
        calls.getFirst().fire();
        assertEquals(1, calls.size(), "Polling must not submit additional timers");
        assertEquals(1, runs.get());
    }

    @Test
    void cancelledCustomTimerDoesNotExecuteLateCallback() {
        AtomicInteger runs = new AtomicInteger();
        StargateGlobalTask task = new StargateGlobalTask(false, true) {
            @Override public void run() { fail("Custom callback expected"); }
        };
        task.runTaskTimer(20, 0, runs::incrementAndGet);
        assertEquals(1L, calls.getFirst().args[2]);
        task.cancel();
        calls.getFirst().fire();
        assertEquals(0, runs.get());
    }

    @Test
    void concurrentCallbacksDoNotExecuteOneShotBodyTwice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        StargateAsyncTask task = new StargateAsyncTask(true) {
            @Override public void run() {
                runs.incrementAndGet();
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        task.runNow();
        CompletableFuture<Void> first = CompletableFuture.runAsync(calls.getFirst()::fire);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            calls.getFirst().fire();
        } finally {
            release.countDown();
        }
        first.get(2, TimeUnit.SECONDS);
        assertEquals(1, runs.get());
    }
}

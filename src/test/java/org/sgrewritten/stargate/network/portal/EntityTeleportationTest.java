package org.sgrewritten.stargate.network.portal;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sgrewritten.stargate.StargateExtension;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(StargateExtension.class)
class EntityTeleportationTest {
    private final ControlledTeleport controlled = new ControlledTeleport();
    private final List<Boolean> outcomes = new ArrayList<>();
    private final AtomicInteger cleanups = new AtomicInteger();
    private boolean synchronousResult;

    private Entity entity() {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "teleportAsync" -> controlled.start((Location) args[0]);
                    case "teleport" -> synchronousResult;
                    case "getScheduler" -> controlled;
                    default -> throw new AssertionError("Unexpected entity access: " + method.getName());
                });
    }

    private void start() {
        new EntityTeleportation(true).teleport(entity(), new Location(null, 100, 64, 100), success -> {
            assertTrue(controlled.executing, "Entity work must run through its scheduler");
            outcomes.add(success);
        }, cleanups::incrementAndGet);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waitsForResultAndOwningThread(boolean success) {
        start();
        assertNull(controlled.completion);
        assertEquals(0, cleanups.get());

        CompletableFuture.runAsync(() -> controlled.result.complete(success)).join();
        assertTrue(outcomes.isEmpty());
        assertEquals(0, cleanups.get());

        controlled.runCompletion();
        assertEquals(List.of(success), outcomes);
        assertEquals(1, cleanups.get());
    }

    @Test
    void alreadyCompletedFutureStillUsesEntityScheduler() {
        controlled.result.complete(true);
        start();
        assertTrue(outcomes.isEmpty());
        controlled.runCompletion();
        assertEquals(List.of(true), outcomes);
    }

    @Test
    void snapshotsTheDestination() {
        Location destination = new Location(null, 100, 64, 100);
        new EntityTeleportation(true).teleport(entity(), destination, outcomes::add, cleanups::incrementAndGet);
        destination.setX(200);
        assertEquals(100, controlled.destination.getX());
        controlled.result.complete(true);
        controlled.runCompletion();
    }

    @Test
    void exceptionalFutureReportsFailureOnOwningThread() {
        start();
        controlled.result.completeExceptionally(new IllegalStateException("chunk loading failed"));
        assertTrue(outcomes.isEmpty());
        controlled.runCompletion();
        assertEquals(List.of(false), outcomes);
        assertEquals(1, cleanups.get());
    }

    @Test
    void exceptionStartingTeleportReportsFailure() {
        controlled.startFailure = new IllegalStateException("teleport rejected");
        start();
        controlled.runCompletion();
        assertEquals(List.of(false), outcomes);
        assertEquals(1, cleanups.get());
    }

    @Test
    void retirementOnlyCleansUpBookkeeping() {
        start();
        controlled.result.complete(true);
        controlled.retired.run();
        assertTrue(outcomes.isEmpty());
        assertEquals(1, cleanups.get());
    }

    @Test
    void alreadyRetiredSchedulerOnlyCleansUpBookkeeping() {
        controlled.reject = true;
        start();
        controlled.result.complete(true);
        assertTrue(outcomes.isEmpty());
        assertEquals(1, cleanups.get());
    }

    @Test
    void schedulerExceptionStillCleansUp() {
        controlled.scheduleFailure = new IllegalStateException("plugin disabled");
        start();
        controlled.result.complete(true);
        assertTrue(outcomes.isEmpty());
        assertEquals(1, cleanups.get());
    }

    @Test
    void completionExceptionStillCleansUp() {
        new EntityTeleportation(true).teleport(entity(), new Location(null, 0, 64, 0), success -> {
            throw new IllegalStateException("completion failed");
        }, cleanups::incrementAndGet);
        controlled.result.complete(true);
        controlled.runCompletion();
        assertEquals(1, cleanups.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void synchronousServerKeepsImmediateCompletion(boolean success) {
        synchronousResult = success;
        new EntityTeleportation(false).teleport(entity(), new Location(null, 0, 64, 0),
                outcomes::add, cleanups::incrementAndGet);
        assertEquals(List.of(success), outcomes);
        assertNull(controlled.completion);
        assertEquals(1, cleanups.get());
    }
}

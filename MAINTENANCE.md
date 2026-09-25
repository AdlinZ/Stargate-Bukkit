# Maintenance fork

This fork starts from upstream `nightly` and preserves its license and attribution.
The first maintenance target is the unloaded remote portal failure reported in
[upstream #390](https://github.com/stargate-rewritten/Stargate-Bukkit/issues/390).
This is an experimental development build; no new stable release or complete
Folia compatibility claim is made.

## First fix

The [reported stack trace](https://mclo.gs/cGmPiER) reaches
`Teleporter.betterTeleport()` through `Location.getChunk()` and
`CraftWorld.getChunkAt()`. Merely obtaining the chunk can synchronously load it
from the source region, before the plugin reaches its Folia `teleportAsync()` call.
The redundant chunk preload is removed. The teleport API is responsible for
loading the destination: asynchronously on Folia and synchronously on the existing
Paper path.

The regression test rejects synchronous remote chunk retrieval while exercising
the public teleporter entry point. MockBukkit cannot establish Folia region thread
safety; a passing test does not replace validation on a real server.

## Second maintenance batch: teleport completion

Ordinary entity exit velocity and furnace-minecart fuel restoration now wait for
the teleport result. Folia completion work is dispatched through the entity
scheduler, so an already-completed future or an off-thread completion cannot run
entity mutations on the wrong region. Paper retains synchronous completion.

Failed teleports do not apply exit velocity or send the arrival message. Furnace
minecarts regain their saved fuel and original velocity when the teleport fails.
Entity retirement and scheduler rejection only clear plugin bookkeeping; they do
not attempt to modify a removed entity. Each teleport snapshots its destination
and velocity before using them in later work.

The in-flight boat registry uses UUIDs in a concurrent set. Boats remain marked
until completion or retirement, and duplicate attempts are rejected before
permission events or economy charges. World-border rejection also releases boat
markers. This does not address all pre-teleport failures or refund paths.

Deterministic tests advance the future and a simulated entity scheduler separately
to check successful, failed, exceptional and already-completed teleports, rejected
or retired scheduling, velocity/fuel restoration, messages and boat retries. These
are unit tests, not a real Folia server or region-ownership simulation.

Passenger and leash reattachment, cross-world safe-spawn reads, and full failure
refund handling remain out of scope for this batch. Real-server verification is
still required before either maintenance batch becomes a stable release.

## Build and test

Use JDK 21 and Maven 3.9+. The current compile target remains Paper API 1.20.6;
the server version and Java runtime used in #390 were Folia 26.1.2 and Java 25.

```sh
# Unit tests and SQLite integration tests, without a MySQL server
mvn -B '-Dtest=*,!MySQLDatabaseTest,!StargateTest' verify

# Focused teleport regression tests
mvn -B -Dtest=TeleporterTest test
```

For the complete suite, use a disposable MySQL database with TLS enabled (MySQL
8.4 generates test certificates automatically). Both `MySQLDatabaseTest` and the
plugin lifecycle tests in `StargateTest` use it. The integration test
**drops and recreates the database named `Stargate`**. Never point it at a server
containing production data. Create `src/test/resources/mysql_credentials.secret`
(ignored by Git) with these keys, then run `mvn -B verify`:

```properties
MYSQL_DB_ADDRESS=127.0.0.1
MYSQL_DB_PORT=3306
MYSQL_DB_NAME=Stargate
MYSQL_DB_USER=root
MYSQL_DB_PASSWORD=your-disposable-test-password
```

GitHub Actions provisions a disposable MySQL service, runs the complete suite,
and uploads the built JAR and test reports. It needs no upstream service tokens.

## Folia validation still required

Use a separate test server with copied portal data. Record the exact server build,
Java version, plugin commit and logs for each check:

1. Create two portals thousands of blocks apart in the same world. Leave the
   destination so its chunks unload, then teleport from the source. Check arrival
   and the absence of `Async chunk retrieval` errors; repeat in both directions.
2. Repeat with a loaded destination, negative chunk coordinates, and a portal near
   a chunk boundary. Confirm player direction and exit velocity.
3. Exercise denied permissions, insufficient funds and world-border rejection.
4. Test cross-world travel, horses/boats with passengers, leashed mobs and furnace
   minecarts separately. These involve additional paths outside the initial fix.
5. Repeat the ordinary player and vehicle cases on Paper to check compatibility.

## Follow-up work

- Await all involved teleports before reattaching passengers and leashes; validate
  that both entities are still in the same owning region before attachment.
- Perform cross-world safe-spawn block reads on the correct destination regions.
- Audit refunds, cancelled/failed teleports and cleanup of in-flight boat state.
- Audit shared mutable state and scheduler registration during plugin shutdown.
  The #390 log also contains an exception while disabling the plugin.
- Establish a tested Paper/Folia version matrix before publishing a stable build.

Open changes against this fork's `nightly` branch. Include a regression test and
state which real-server scenarios were actually exercised. Long-term maintenance
priorities and release decisions remain with the fork owner.

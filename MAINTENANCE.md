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
still required before any maintenance batch becomes a stable release.

## Third maintenance batch: task lifecycle

All task wrappers expose delays and periods in ticks. Folia asynchronous calls
convert them to milliseconds, while Paper repeating asynchronous work now uses
the asynchronous scheduler. Entity, region and global Folia timers normalize an
immediate start to the next tick. The server-name request uses a 20-tick period;
the Bungee readiness poll cancels its timer after delivering its one-shot action.

Cancellation retains every returned scheduler handle, including entity tasks
before their first callback. Completed or cancelled tasks reject late callbacks
and late handles, one-shot callbacks cannot race into duplicate execution, and
repeating tasks remain tracked until cancellation or failure. Queued delayed and
repeating tasks enter the same serial database queue and honour cancellation.

Shutdown cancels scheduled work and clears the Paper populator instead of forcing
world work to run on the shutdown thread. A dedicated database worker stops
accepting submissions and drains accepted writes in order, with a 10-second wait.
A timeout or interruption is logged explicitly: pending writes are **not**
guaranteed saved if the process exits before that worker finishes. A replacement
worker cannot start while the previous one is alive. Hikari pool disposal is a
separate follow-up.

Portal iris blocks are no longer changed during disable. Stored portals reconcile
their iris to the closed material when loaded, before normal network updates
reopen valid always-on destinations. Disabling without restarting can therefore
leave visible portal blocks until Stargate loads again. Iris block lookup now
happens inside the task for the block's region.

The legacy `forceRunAllTasks()` helper is deprecated, excluded from production
shutdown, rejects Folia/off-main-thread use, and never executes database work.
Direct access to `StargateQueuedAsyncTask.asyncQueue` is deprecated; callers should
submit through the task wrapper and use `waitForEmptyQueue()` for a bounded barrier.
Add-ons overriding task internals must rebuild for the BukkitTask-based handle
registration method.

Regression tests cover simulated Folia handles, async tick conversion, Paper
async execution, cancellation/retirement/late callbacks, Bungee poll completion,
serial queued timers, barriers, timeout/restart handling, actual MockBukkit plugin
disable, and persisted iris reconciliation. These are not real Folia region tests.

## Storage compatibility revision

The storage extensions shipped in `v1.0.0.19-maintenance.1` have been withdrawn
at the fork owner's request. SQL resources, SQL generation, storage interfaces,
legacy conversion, portal setters, creation and persistence now match upstream
`nightly` at `d76cdd9c4dd059f83c750797d50523dac5a95f08`.

This removes owner/network setter persistence, synchronous shared-portal
reservation and its creation-refund handling, automatic missing-control recovery
and its stored positions, and the reserved legacy network remapping. Consequently,
#377, #379, the persistence portion of #244 and the shared-storage conflict portion
of #367 are no longer claimed fixed. Existing storage functionality remains: normal
portal creation/deletion, flags, metadata and the upstream migration/validity rules
can still write data. This is not a read-only plugin.

The serial queue, cancellation and bounded shutdown/reload barriers remain so
already-accepted normal saves are not dropped or raced by reload. These change
execution timing, not SQL, schemas or the data requested for storage. The sole
remaining `SQLDatabase` change closes portal iris blocks on their owning region
after loading; it does not write database records.

No database rollback, cleanup or reverse migration is performed on installation.
Data already written by an earlier maintenance build remains as stored. Restore
an appropriate backup separately if such earlier changes need to be undone.

Validation of this revision: JDK 21 full Maven verify with disposable MySQL 8.4
and SQLite passed **691 tests: 689 passed, 2 existing skips**. Five compatibility
cases check that setters leave stored schema/rows unchanged, both storage types
still queue ordinary creation, and reload does not add missing controls.
The lower count removes tests for the withdrawn features; it does not skip them.

On Folia 26.1.2-8 / Java 25.0.4.1, the revised JAR passed all six cold-destination
routes, five vehicle/leash cases and three cross-world/arrival cases with a
leashed cow. All checks use non-player entities on a localhost server copy.

## Retained proxy and entity fixes

These fixes cover upstream #305/#313 and #375, and harden U-gate arrival handling
relevant to #364. The original Velocity configuration and real proxy/server
combination have not been reproduced.

- Proxy forwards use one online carrier instead of broadcasting through every
  player. Replayed remote announcements are idempotent, metadata can refresh, and
  a message from another server cannot remove or replace a local/different-server
  gate. Rename replay is harmless and cannot overwrite an occupied target name.
- New JSON teleport requests carry a UUID. Receivers remember request IDs for
  60 seconds; old JSON/U packets without IDs receive a 1-second duplicate window.
  Both the deduplication cache and pending arrival queue are capped at 4096 entries;
  arrivals expire after 60 seconds. These are bounded compatibility protections,
  not durable exactly-once delivery. A new request ID permits another immediate trip.
- Pending arrivals only retain real, non-destroyed portals. Online and join-time
  arrivals execute on the player's scheduler. Wire-envelope and plugin reload
  tests cover U messages arriving before and after player join. The raw modified
  UTF payload remains compatible with BungeeCord/BungeeBark Forward framing.
- Newer non-living entities with the public leash API (including Paper 1.21 boats)
  participate in relation traversal. Reflection retains the existing 1.20.6 compile
  baseline; old boats remain supported without leash methods. Relationship cycles
  visit/charge an entity once. Disabled leash handling excludes those entities.
- Passenger/leash attachment waits for both results and checks entity validity,
  world, distance and Folia ownership. Partial success does not join entities across
  regions. Both failures may restore the original relationship when still safely
  co-located. Source-task cancellation, changed leashes and retirement release boat
  bookkeeping. This is covered with simulated futures/schedulers, not real Folia.
- No safe arrival location now produces a localized message, clears boat markers
  and refunds an accepted local charge instead of dereferencing a null destination.
Reload now waits for accepted database writes before replacing storage or clearing
registries. The first push CI exposed a real race in which a newly created U gate
could disappear from the reloaded registry while its queued INSERT finished later.
A slow-write regression fails with the original reload order and passes with the
barrier. Timeout/interruption leaves the current registry intact and logs the failure.

For #364, BungeeBark's upstream instructions require disabling Velocity's native
`bungee-plugin-message-channel` when BungeeBark handles that channel. Its Forward
handler queues messages for empty servers; the plugin cannot infer that BungeeBark is
installed merely from a GetServer response. Validate the actual proxy configuration,
empty target-server queue and return trip before declaring the original report fixed.
No proxy plugin was modified; the real-server checks below cover only the listed local scenarios.

Sources: [Paper messaging format](https://docs.papermc.io/paper/dev/plugin-messaging/),
[Paper 1.21.1 Boat API](https://jd.papermc.io/paper/1.21.1/org/bukkit/entity/Boat.html),
[BungeeBark Forward](https://github.com/RoinujNosde/BungeeBark/blob/main/src/main/java/me/roinujnosde/bungeebark/methods/Forward.java),
[upstream #364 configuration discussion](https://github.com/stargate-rewritten/Stargate-Bukkit/issues/364).

## Folia 26.1.2 integration check (2026-09-26)

A supplied server archive was tested in localhost-only copies with Folia
26.1.2 build 8 (`62dc0f2`), Temurin 25.0.4.1, its existing SQLite database,
three same-world portals, and ViaVersion 5.11.0. No uploaded world, account,
configuration or database files are included in this repository.

A temporary plugin called the actual portal API from owning region threads using
armor stands. Plugin chunk tickets kept the three areas ticking without players.
The original 1.0.0.18-ALPHA reproduced `Async chunk retrieval` on four of six
directed routes. The maintenance build completed all six routes. A cow passenger
also arrived and reattached to its carrier.

The first live leash check exposed a second ordering bug: the holder leaves its
source region before the companion's next scheduled tick, so Folia breaks the
leash and the old validation abandons the companion. Detach the accepted leash
on its owning source region before starting the holder's teleport. Keep the
companion teleport on its entity scheduler and restore the relationship only
after both results settle; preserve any newly attached leash while waiting.
The regression fails without this change. The live cow leash check now passes,
along with the six routes and passenger check. Full Maven verification: **725
tests, 723 passed, 2 existing skips**, including disposable MySQL and SQLite.

These are real-server API tests using non-player entities, not a human client
walk-through. Destination regions were loaded for deterministic checks. Cold
chunks, player interaction, cross-world safe-spawn, boat/furnace behavior, and
Velocity/BungeeBark end-to-end behavior still require separate validation.
Startup and ordinary shutdown passed; shutdown under active writes is not covered
by this server probe.

## Cold chunks and cross-world follow-up (2026-09-26)

A second real-server probe explicitly checked that all nine chunks around each
same-world destination were unloaded before invoking teleportation. All six
routes passed. A one-route negative control with 1.0.0.18-ALPHA reproduced
`Async chunk retrieval` and failed to arrive. The loaded-region restriction in
the earlier probe therefore no longer describes the full verification scope.

Further real-server checks passed for horse and boat passengers, a leashed boat,
three leashed cows, and furnace-minecart arrival with retained fuel. The minecart
had 977 fuel from an initial 1000 and continued moving after arrival. These tests
used non-player passengers, not human clients. Database rows were unchanged
across an ordinary stop/restart and the vehicle tests; SQLite integrity and
foreign-key checks passed.

A temporary in-memory destination portal and prepared landing area in the Nether
then reproduced `Cannot read world asynchronously` in the safe-spawn search.
Folia now loads the search-area chunks asynchronously, captures each chunk on
its owning region, and evaluates immutable snapshots. Entity dimensions and
candidate geometry are captured on the source thread, and completion returns
to the entity scheduler before teleporting or reporting a blocked destination.
The same path handles cross-world leash companions and source-less arrivals.
A new leash attached during the wait is preserved. Snapshots describe the world
at read time; they do not make later terrain changes atomic with teleportation.

Real-server checks now pass for Overworld-to-Nether, Nether-to-Overworld, and
source-less arrival, each including a leashed cow restored at the destination.
The first trip explicitly used unloaded destination chunks. The temporary
portal and test arena were never saved to the Stargate database. The probe lets
newly spawned companions tick before invoking teleportation; an earlier fixture
that invoked it immediately did not consistently include the new companion.

Five snapshot-search regressions cover completion order, negative chunk borders
and entity width, absent floor, load failure, and build-height boundaries. Full
JDK 21 Maven verification with SQLite and MySQL: **730 tests, 728 passed, 2 existing
skips**. Human sign/button interaction, concurrent-player load, a broad version
matrix, proxy end-to-end delivery, and failure/refund/shutdown races remain
outside this real-server validation. These results describe the previous
`be649c10` build, published as `v1.0.0.19-maintenance.1`; they are not a new
verification of the storage compatibility revision. No upstream comment was made.

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
6. Stop with open portals and pending saves, then restart and inspect iris blocks,
   always-on destinations and ordinary saved portals. Test Bungee discovery with no
   players initially online, then join and verify the polling task terminates.

## Follow-up work

- Validate the new completion/ownership checks for passengers and leashes on real
  Paper/Folia servers, including partial failure and cross-region boundaries.
- Extend cross-world snapshot-search validation to blocked exits, cancellation,
  concurrent terrain changes and more entity/world combinations.
- Audit refunds, cancelled/failed teleports and cleanup of in-flight boat state.
- Validate startup/shutdown on real Folia, including portals spanning regions,
  pending database writes and queue timeout logs; add explicit Hikari pool closure.
- Establish a tested Paper/Folia version matrix before publishing a stable build.

Open changes against this fork's `nightly` branch. Include a regression test and
state which real-server scenarios were actually exercised. Long-term maintenance
priorities and release decisions remain with the fork owner.

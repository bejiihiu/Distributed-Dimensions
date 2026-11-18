# Paper / Folia — Backend Foundation

How the backend plugin boots, what each platform object guarantees, and the rules
later waves (portals, entities, API) must obey. This document describes the whole
plugin, not just a subsystem; it is the contract for everything under the `paper`
module.

## Architecture

```text
DistributedDimensionsPaperPlugin (onEnable)
  │
  ├─ 1. ConfigurationService  ──► Environment.resolve(getDataFolder()), EnvLookup.system()
  │     load() once → LoadReport kept for boot logging
  │     broken file → last-good / bundled-defaults fallback → health is unhealthy
  │
  ├─ 2. DimensionResolver ─────► network.dimensions (validated routing table)
  │
  ├─ 3. PersistenceFactory.open(config.database().toPersistenceSettings())
  │     SQL: HikariCP pool → SchemaMigrationRunner.migrate() (blocking, on boot)
  │     MongoDB: indexes ensured eagerly (no DDL exists)
  │     ANY failure → SEVERE log → fail-closed (plugin stays enabled)
  │
  └─ 4. PaperServices (registered in the Bukkit ServicesManager)
        config() · persistence()/journal()/portalIndex() · schedulers()
        chunkTickets() · dimensions() · health() · isHealthy()
```

One instance of `PaperServices` exists per boot. Downstream code obtains it through
`PaperServices.get()` (ServicesManager lookup) — never by static reach into the
plugin class. `PaperServices.isAvailable()` lets optional integrators probe without
exceptions.

### Fail-closed semantics

The plugin never half-opens. If any stage fails:

- `HEALTHY → DEGRADED`: `PaperServices.health()` returns `DEGRADED` with the
  accumulated `reasons` list (configuration fallback, persistence failure,
  mis-wired dimension slots).
- `persistence()`, `journal()`, `portalIndex()` throw `IllegalStateException`
  instead of handing out unmigrated or half-initialised state.
- Later waves **must** check `PaperServices.isHealthy()` (or catch the exception)
  and refuse transfers while degraded. A broken configuration also degrades —
  even if persistence is fine — because routing decisions cannot be trusted on a
  fallback snapshot either.
- `isHealthy() == false` never implies the process is shut down: the plugin stays
  enabled so the administrator sees a live, loud failure in the log instead of a
  vanished plugin.

### Configuration health

`config.database().toPersistenceSettings()` maps the loaded snapshot onto the
persistence seam. Secrets (`database.password`, anything referenced as `${VAR}`)
are expanded at load time, never printed; `DatabaseSettings.toString()` masks the
password and any credentials embedded in URLs, and the plugin additionally redacts
URL credentials from exception messages before logging them.

## Scheduler facade

All scheduling goes through
`kz.bejiihiu.dimensions.paper.scheduler.Schedulers`. Raw bukkit scheduling
(`Bukkit.getScheduler()`, `Bukkit.getGlobalRegionScheduler()`, ...) must not be
used anywhere in the plugin outside `PlatformSchedulers`.

Both Paper and Folia implement the same
`io.papermc.paper.threadedregions.scheduler` interfaces, and the Paper
implementation executes them internally as regular tasks — the PaperMC
documentation states that if you use these schedulers on Paper they provide the
same functionality as on Folia. Consequently there is exactly **one**
implementation (`PlatformSchedulers`) and zero platform detection in the codebase.
`isFolia()` does not exist anywhere in the plugin.

| Task touches…                              | Method                                       | Runs on (Folia)          | Runs on (Paper) |
|--------------------------------------------|----------------------------------------------|--------------------------|-----------------|
| A specific entity                          | `runOn(Entity, Runnable [, Runnable retired])` | the region owning the entity (follows it) | main thread |
| Blocks / world state of one chunk          | `runOnRegion(World, chunkX, chunkZ, Runnable [, long delayTicks])` | the region owning the chunk | main thread |
| Same, addressed by block coordinates       | `runOnRegionBlock(World, int blockX, int blockZ, Runnable)` | the region owning the chunk | main thread |
| Same, via `Location`                       | `runOnRegion(Location, Runnable [, long delayTicks])` | the region owning the location | main thread |
| Server-global, no region affinity          | `runGlobal(Runnable)` / `runGlobalDelayed(Runnable, long delayTicks)` | global region | main thread |
| Network / database / heavy computation     | `runAsync(Runnable)` / `runAsyncDelayedFor(long, TimeUnit, Runnable)` | async threads | async threads |

Rules for the matrix:

- **Ticks vs time.** Entity/region/global delays are in ticks. The platform async
  API is time-based; `runAsyncDelayedFor` therefore takes a `TimeUnit`, and callers
  must not pass ticks there.
- **Entity retire.** `runOn(Entity, ...)` returns `null` when the entity is
  already retired (removed); such tasks are never scheduled. Retire callbacks run
  in critical code — no world access, no chunk loads, no ticket changes.
- **Cancellation.** Handles are `TaskHandle` (`cancel()` / `isCancelled()`).
  `Schedulers.cancel(@Nullable TaskHandle)` ignores null, which makes it safe on
  the entity paths. A task that already started cannot be stopped.
- **Region routing math** lives in `RegionMath` (block → chunk with floor
  division, correct for negative coordinates). Folia assigns region boundaries at
  runtime and exposes no region-id API; the facade therefore routes by chunk —
  the platform maps chunks onto regions.
- **No cross-region calls.** A task scheduled for chunk A must not touch state in
  chunk B. If work spans chunks, schedule one task per chunk.
- **No world access from async.** Async tasks exist for I/O and computation only;
  they must never read or mutate world state.

### Test fixtures

`paper` ships its fake scheduler in Gradle `testFixtures` so later waves can test
against it without a running server:

```kotlin
dependencies {
    testImplementation(testFixtures(project(":paper")))
}
```

`FakeSchedulers` is manual-clock and deterministic: `advanceTicks(n)` /
`advanceMillis(n)` execute everything due in order (50 ms per tick), `calls()`
records the routing decision (`entity`, `region:<x>,<z>`, `global`, `async`, …),
and `tickSupplier()` adapts the fake clock to a `LongSupplier` tick source.
`FakeChunkTicketGateway` records ticket operations. `TestWorlds.create(name)`
builds identity-only `World` proxies (stable UIDs via name hashing); calling
anything else on them throws, which keeps tests honest about not touching world
state.

## Chunk tickets (portal-arrival chunks)

`ChunkTicketService` reproduces the vanilla "destination chunk stays loaded for
15 s, refreshed on every transfer" for portal arrivals:

- `addTicket(World, chunkX, chunkZ)` and `addTicketForBlock(World, int, int)`
  ensure a plugin ticket for the chunk and push the expiry 300 ticks
  (15 s) into the future. Plugin tickets have no platform TTL — this service
  reproduces it.
- Every touch also refreshes the 8 surrounding chunks (center + ring-of-8) when
  `entities.cross-server-transfer.chunk-tickets.ring` is true. The whole feature
  is gated by `entities.cross-server-transfer.chunk-tickets.enabled`.
- The ticket mutation happens inside the region that owns the chunk (through the
  facade) — never on the caller's thread and never via raw bukkit scheduling.
- Expiry is a delayed region task; every touch cancels the previously armed
  removal and re-arms it (refresh-on-touch). Removal re-checks the ledger, so a
  refresh that raced the cancel window does not drop a live ticket.
- `World#removePluginChunkTicket` is called on expiry and on plugin disable;
  failures are logged through the diagnostics sink, never swallowed.
- The service holds strong `World` references only for tracked chunks and drops
  them on expiry — no unbounded retention.

## Boot logging

At enable, the plugin logs (never secrets, never URLs with credentials):

- configuration source (`FILE` / `DEFAULTS_FALLBACK` / `LAST_GOOD`), whether a
  file was created, the effective `config-version`, unknown-key and note counts,
  and any migration (from → to);
- the resolved dimension routing (`minecraft:overworld → server 'overworld'`, …)
  and any routing warnings (duplicate servers) or errors (mis-wired slots);
- the persistence engine and table prefix (not the JDBC URL, not the pool
  credentials);
- the chunk-ticket configuration (enabled/disabled, TTL, ring-of-8);
- a final one-line verdict: clean boot or `DEGRADED` with the reasons.

## Configuration wiring

Everything comes from the single bundled `config.yml` (migrated `config-version: 1`):

| Key | Consumed by |
|---|---|
| `network.dimensions.{overworld,nether,end}.{server,dimension}` | `DimensionResolver` |
| `database.*` | `DatabaseSettings.toPersistenceSettings()` → `PersistenceFactory.open` |
| `entities.cross-server-transfer.chunk-tickets.{enabled,ring}` | `ChunkTicketService` |

`DimensionResolver` validation: the server/dimension grammar is enforced by the
configuration records at load time; the resolver independently flags a slot whose
canonical key does not match its dimension (e.g. the overworld slot serving
`minecraft:the_end`) as an error — such a slot does not route, transfers are
refused. Duplicate servers across slots are logged as a warning (legal, but the
cross-server split is inert).

## On disable

`onDisable` unregisters the plugin from the ServicesManager, cancels pending
chunk-ticket removals, releases all tracked plugin tickets (best effort; failures
issued to the log), then closes the persistence layer. Unreleased plugin tickets
would leak chunk loads on standalone Paper; the order (tickets first, persistence
after) keeps the failure modes narrow.

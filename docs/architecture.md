# Architecture — the big picture

Distributed Dimensions is a Paper/Folia + Velocity plugin network that splits the three
Minecraft dimensions across three independent backend servers — one Overworld, one Nether,
one The End — behind a single Velocity proxy. To the player it behaves like one Minecraft
world: portals work vanilla-style, coordinates and world mechanics carry over, and the split
is invisible except where it cannot honestly be (see `docs/limitations.md`).

This document is the map. Read it once and you should understand the system in about
15 minutes. Deep dives live in their own documents and are linked throughout.

> Status: pre-release. The API module is versioned independently (`1.0.0`); the core plugin
> is `1.0.0` as of this writing. What is wired and what is not is spelled out in
> `docs/limitations.md` — read that before assuming anything works.

## Why split dimensions at all

Minecraft ties each dimension to one server process: world loading, chunk tickets, portal
mechanics, mob spawning, the Ender Dragon fight, entity ownership — all of it assumes one
JVM owns one world. A network that wants a heavier, more isolated, or independently
restartable Nether/End has no honest way to stretch a single server across that boundary.

The rationale, condensed:

- **Isolation and scale.** A corrupted Nether can be restarted without touching the
  Overworld. Heavy dimensions absorb their own load (entity-heavy Nether farms, End
  cities). Backends can be sized differently.
- **Vanilla parity, not approximation.** The End backend runs a *real, native End*: dragon,
  crystals, boss bar, exit portal, gateways, outer islands — all vanilla mechanics of one
  server, untouched. The plugin never re-implements Dragon Fight mechanics; it only owns
  the two cross-server transitions (Overworld → End, End → respawn point).
- **The proxy owns connectivity, nothing else.** Velocity switches connections between
  backends; it holds no world state and no durable state. The backends own everything
  game-facing; the proxy owns routing and request validation.
- **Player data is not this plugin's job.** Inventory, armour, ender chest, XP, effects are
  synchronized by a separate external sync plugin (HuskSync recommended). This plugin owns
  exactly one question — "has the player's data been applied on the target yet?" — and even
  that answer is delegated to the sync plugin's signals, never faked (see
  `docs/player-sync.md`).
- **One JVM per dimension means vanilla events keep their meaning.** Portal blocks, cooldowns,
  search, creation and arrival coordinates are vanilla algorithms ported, not new invented
  mechanics. The goal is Java Edition parity, verified against the real thing — never a
  parallel "custom portal system".

## Module map

```text
distributed-dimensions (root, Gradle multi-module)
│
├─ api      — public API contract (independent semver, `1.0.0`)
├─ common   — shared logic, platform-independent (both jars embed/relocate it)
├─ paper    — Paper/Folia backend plugin (the "game" side)
└─ velocity — Velocity proxy companion (the "switch" side)
```

Dependency direction: `paper` depends on `api` and `common`; `velocity` depends on `common` only;
`api` and `common` do **not** depend on each other (`api` stays dependency-free except the
Paper/Folia API, `compileOnly`). Nothing outside `common` is shared between the two platform
jars.

### `api` — `kz.bejiihiu.dimensions.api` (+ `api.event`)

The public contract third-party plugins compile against. Published separately
(`kz.bejiihiu:distributed-dimensions-api`). Contents: `DistributedDimensionsAPI` (entry
point), `TransferManager`, `DimensionRegistry`, `PortalRegistry`, `DimensionId`,
`DimensionInfo`, `OperationState`, `TransferCause`, `TransferError`, `TransferResult`, and
the event family (`DimensionTransfer*Event`, `EntityTransferEvent`, `PortalFound/Created
Event`). No implementation code, no platform dependencies beyond Paper/Folia API.
Contract: `docs/api.md`.

### `common` — `kz.bejiihiu.dimensions.common.*`

Platform-independent code, embedded (with relocations) into both platform jars:

| Package | Contents |
|---|---|
| `common.model` | Domain types: `TransferOperation` (journal row + state machine), `DimensionId`, `Position`, `TransferRequest`/`TransferResult`, respawn query/answer records, `OperationState` (`CREATED → PREPARING → TARGET_CREATED → SOURCE_REMOVED → COMMITTED`, `FAILED` from any non-terminal state) |
| `common.config` | YAML configuration: loader, heal-from-defaults, migrations (v0→v1→v2), `${VAR}` env expansion, `LoadReport`, env handling. Both platforms consume this |
| `common.persistence` | The shared-database layer: `TransferOperationJournal` (SQL via HikariCP, or MongoDB), `RespawnPointStore`, `PortalIndex`, schema migrations (v1, v2), `DimensionPersistence` facade. H2 for tests |
| `common.protocol` | The wire protocol (`v=1`): `DimensionTransferRequestMessage`, `DimensionTransferResultMessage`, `EntityTransferMessage`/`AckMessage`, `PlayerRespawnPointQuery/AnswerMessage`, `ProtocolCodec` (JSON, 32,766-byte cap, hard version check) |
| `common.nether` | Vanilla Nether portal math: coordinate conversion (8:1, Y untouched), portal search (grouping, largest-rectangle, spiral column generation), portal creation planning, world-border bounds/clamping |
| `common.util` | `Clock`, `IdGenerator`, `Result`, math helpers |

### `paper` — `kz.bejiihiu.dimensions.paper.*` (+ sibling roots)

The backend plugin (`distributed-dimensions-paper-<version>.jar`, `folia-supported: true`):

| Package | Contents |
|---|---|
| `…paper` | Boot (`DistributedDimensionsPaperPlugin`), `PaperServices` (the one service holder — `config()`, `persistence()`, `schedulers()`, `chunkTickets()`, `dimensions()`, `health()`), `DimensionResolver` (routing table + validation), `HealthReport`, `SecretsRedactor` |
| `…paper.scheduler` | Folia-safe scheduler facade (`Schedulers`, `PlatformSchedulers`) — zero raw bukkit scheduling elsewhere; region/entity/global/async routing, `RegionMath` |
| `…paper.chunktickets` | `ChunkTicketService` — reproduces vanilla's 15 s destination-chunk ticket (refresh-on-touch, ring-of-8) |
| `…paper.transfer` | The transfer orchestration itself (portal-family agnostic): `PortalTransferService` (source flow), `TransferArrivalService` (target flow), `TransferArrivalListener`, `TransferChannelSink`, `PluginMessageTransferChannel`, journal CAS logic, watchdog |
| `…paper.end` | The End wave: `EndPortalListener` (entry/exit), `RespawnRecorder`, `BedAnchorValidator`, `EndArrivalPlanResolver`, `EndModule` (wiring) |
| `…paper.sync` | External player-data sync integration (HuskSync API → reflection → blocking-login delay → degraded fallback). Bound at boot; **not yet called by the arrival flow** — see `docs/limitations.md` |
| `kz…compat` | Public API bridge (`ApiBridge`, `PaperDistributedDimensionsAPI`), compat A/B/D machinery (`ArrivalEffectsService`, `PaperTransferManager`, `PdcCooldownStore`, `PortalCooldownRules`, `AdvancementGranter`) |
| `kz…interop` | Cross-server transfer seam: `CrossServerTransfer` (interface), `ChannelCrossServerTransfer` (mirage guards / programmatic API path), `SourceServerName` (system property/env resolution), `TransferEvents` |
| `kz…mirage` | Mirage (void stub) worlds: `MirageService`, `MirageGuard` (teleport/block-edit/unload guards), `MirageWorld`, `VoidChunkGenerator`, `VoidBiomeProvider`, `MirageNames`, `ArrivalClamp` |

### `velocity` — `kz.bejiihiu.dimensions.velocity.*`

The proxy companion (`distributed-dimensions-velocity-<version>.jar`). Owns **connection
routing and request validation only**: `TransferCoordinator` (validation pipeline +
execution), `TransferValidator`, `TransferOperations` (in-flight/completed TTL caches,
idempotency by `request_id`), `TransferStats`, `PluginMessageListener`, `DisconnectListener`,
`/ddproxy` admin command (`status`/`reload`/`transfers [n]`), and a versioned
`BuildConstants`. In-memory only; durable state is deliberately none of its business.
Contract: `docs/velocity.md`.

## Data flow diagrams

### 1. Portal transfer: source → proxy → target (End entry/exit)

```text
  Overworld backend                     Velocity proxy                 End backend
 ──────────────────                    ──────────────                 ───────────
 Player enters End portal
   │ PlayerPortalEvent (region thread)
   ▼
 PortalTransferService.transfer()
   1 health gate? ──no──► refused, no journal row, no event
   2 route(source/target)? ──no──► refused
   3 journal CREATED (v4 UUID)
   4 DimensionTransferStartEvent (cancellable) ──cancelled──► FAILED(REJECTED), stop
   5 CAS CREATED→PREPARING
   6 encode dimension_transfer_request
   7 send plugin message on THE player's connection ──►
   8 watchdog armed (2 × network.transfer.timeout)            │
                                                        validate + execute
                                                        (gate: player on THAT
                                                         connection, source==
                                                         claimed, target configured
                                                         + registered, idempotency
                                                         by request_id)
                                                        createConnectionRequest(target)
                                                                   │
                                                          connect() → SUCCESS
                                                                   ▼
   ◄── dimension_transfer_result {ok:true}        Player joins End backend
   (piggybacked on any live source connection;        AsyncPlayerSpawnLocationEvent
    correlated by request_id only)                     claim CAS → spawn location
                                                       PlayerJoinEvent → place + 2 ticks
                                                       → journal SOURCE_REMOVED→COMMITTED
                                                       → DimensionTransferCompleteEvent
```

Every arrow fails closed. A refusal at any source step leaves the player exactly where they
are; a proxy refusal is answered with `dimension_transfer_result{ok:false, error}`; a
missing answer is caught by the watchdog at 2× timeout. The journal row is always driven to
a terminal state by exactly one party (CAS), and exactly one terminal event
(`DimensionTransferCompleteEvent` or `DimensionTransferFailedEvent`) is dispatched per
operation. Full failure matrix: `docs/architecture-transfer.md`.

### 2. Arrival flow (target side, two steps)

```text
 AsyncPlayerSpawnLocationEvent  (config phase; player NOT in world; async)
   read newest pending op (oldest-first list, pick last CREATED|PREPARING)
   CAS CREATED→PREPARING (if needed) → TARGET_CREATED     ── lost CAS ──► skip
   ArrivalPlanResolver decides placement
     • entry portal  → journal position (End: obsidian platform anchor)
     • END_PORTAL exit → shared respawn store lookup
          record dimension routable → Stored(bed/anchor coords)
          else → backend world spawn (vanilla "no respawn point" semantics)
   apply setSpawnLocation(...) to the event
   claim watchdog armed (2 × timeout) ──expires──► FAILED(TIMEOUT)
                          │
 PlayerJoinEvent          ▼
   entity scheduler: verify placement
     stored position vetoed (bed/anchor gone, anchor uncharged) → world spawn
     materially different placement → teleport (PLUGIN cause)
   two entity-scheduler ticks later:
     chunk ticket for arrival chunk (+ ring-of-8 optional)
     journal TARGET_CREATED→SOURCE_REMOVED→COMMITTED (two CASes)
     DimensionTransferCompleteEvent
```

The two-step shape is deliberate: the spawn-location event is the only honest async signal
before the player is in the world, and `PlayerJoinEvent` is the earliest point world state
is touchable — so validation and placement happen in two waves.

### 3. Entity transfer (planned — not shipped)

```text
 EntityPortalEvent (region thread) → cancel → TransferOperation (same journal shape)
   → EntityTransferMessage ──► proxy ──refused today──► entity_transfer_ack{failed,"rejected"}
                                                              │
   target: createEntity at arrival point ◄── (Phase 5, unbuilt)
   ack → source removes entity → COMMITTED, chunk tickets on target
```

Wire messages and the journal states exist in `common.protocol` / `common.model`; the proxy
**rejects** `entity_transfer` explicitly (fail-fast for the source); no backend entity
orchestrator exists yet (`entities.cross-server-transfer.enabled` defaults `false` and nothing
consumes it). Excluded forever by design: wither, ender dragon, heart-bound creaking.
The public surface that does exist is the wire format and `EntityTransferEvent` in the API;
see `docs/api.md` and `docs/limitations.md`.

### 4. Configuration & fail-closed semantics (both platforms)

```text
 boot
   │
   ▼
 config.yml missing? ──yes──► bundled defaults written (byte-for-byte, atomic)
   │ no
   ▼
 parse → config-version check → migrations (vN→vN+1) saved atomically
   │ broken YAML / wrong types / missing required / bad ${VAR}?
   ├──► last-good snapshot keeps serving; if none → bundled defaults serve
   │    → health = UNHEALTHY with the reason
   ▼
 heal missing keys IN MEMORY (disk file untouched unless a migration ran)
   warn unknown keys (never an error)
   │
   ├─ backend: PersistenceFactory.open() → HikariCP + migrations / Mongo indexes
   │    ANY failure → SEVERE log → DEGRADED (plugin stays enabled)
   ▼
 HEALTHY ──► everything routes        DEGRADED ──► every transfer path refuses
                                                   (isHealthy()==false, journal
                                                    accessors throw ISE)
```

A broken backend or proxy never half-opens: routing is refused with an explicit
`UNAVAILABLE` result naming the problem, and `/ddproxy reload` (proxy) re-reads the file —
a clean load re-enables routing with no restart; a broken reload keeps last-good.
`docs/configuration.md`.

### 5. The shared database — why there is no out-of-band proxy channel

All proxy↔backend communication in this system rides on **per-player connections**: backends
reach the proxy only by sending a plugin message on a player's connection (arriving at the
proxy as `PluginMessageEvent` with `source instanceof ServerConnection`); the proxy reaches
a backend either on that same connection or by piggybacking on *some* connection of that
backend (`RegisteredServer.sendPluginMessage`, which returns `false` when nobody is online
there). There is no player-independent plugin-message path in either direction — an empty
backend cannot even transmit. Plugin-channel REGISTER/UNREGISTER packets from clients are
fire-and-forget and unacknowledged.

Consequences, adopted deliberately:

- **Durable cross-server state lives only in the shared database** (`<prefix>transfer_operations`,
  `<prefix>respawn_points`, optional `<prefix>portal_index`). Exactly-once semantics come from
  compare-and-set on that journal, never from message ordering.
- **The proxy keeps nothing durable** — in-memory idempotency caches (~10 min TTL) that vanish
  on restart are a performance hint, not a source of truth.
- **The proxy never answers respawn-point queries.** A fabricated `respawn_point: null` would
  be indistinguishable from "no respawn point exists" and would corrupt the End exit-portal
  flow; backends resolve respawn points locally against the shared store and fall back to
  world spawn on their own.
- **Piggybacked results correlate by `request_id` alone** — never by which connection carried
  them, because any connection of the requesting backend may carry the answer.
- **Entity transfers onto an empty backend are fundamentally hard** for the same reason: the
  ack cannot reach a backend with zero players online. Any future entity wave necessarily
  leans on the shared journal (e.g. poll/timeout) rather than a pushed acknowledgement.

This is why the shared SQL/Mongo database is a **hard requirement**, not a preference:
without it the End exit portal routes every exit to world spawn, and the transfer journal —
the single source of truth for exactly-one terminal outcome per operation — does not exist.

## The public API surface

Third-party plugins never talk to the proxy and never see our wire protocol. They get:

- `DistributedDimensionsAPI.getInstance()` → `api.transfers()` / `api.dimensions()` / `api.portals()`;
- events: `DimensionTransferStartEvent` (source, cancellable veto point),
  `DimensionTransferCompleteEvent` / `DimensionTransferFailedEvent` (exactly one per operation),
  `EntityTransferEvent` (Phase 5, unused today);
- compat modes A (default, honest), B (opt-in synthetic `PlayerPortalEvent`/`PlayerTeleportEvent`
  with `from == to`, off by default), D (mirage worlds). **Mode C does not exist.**

Full contract: `docs/api.md`. Compat matrix and the never-list (`PlayerChangedWorldEvent`,
`EntityPortalEvent`, NMS/Mojang-mapped events, `ClientboundSetDimensionType` spoofing):
`docs/compat.md` and `docs/mirage-dimensions.md`.

## Compat levels, condensed

| Mode | What third-party plugins see | Default |
|---|---|---|
| **A** — honest | Our events + vanilla advancement criteria (`enter_the_nether`/`enter_the_end`, idempotent). No synthetic teleports. | **on** |
| **B** — A + synthetics | Plus one synthetic `PlayerPortalEvent` + `PlayerTeleportEvent` per transfer on the target (`from == to`, zero-distance). 26.3 `@Internal` constructors — opt-in only, can vanish in a future Paper. | off |
| **D** — mirages | A + void mirage worlds for foreign dimensions + teleport/block-edit/unload guards. Player `teleport()` into a mirage → cancelled + re-routed as a transfer. Controlled by `mirage.enabled` (default `true`), not by an `api.compat` key. | mirage default on |

`PlayerChangedWorldEvent` is **never** fired in any mode. `EntityPortalEvent` is **never**
synthesized. Details: `docs/compat.md`. Mode C (NMS-level dimension transition spoofing) does
not exist and never will.

## Build & release overview

- Java **25** toolchain (foojay resolver in `settings.gradle.kts`; Gradle downloads the JDK
  automatically if missing). No preview features.
- Build both jars: `./gradlew build` →
  - `paper/build/libs/distributed-dimensions-paper-<version>.jar`
  - `velocity/build/libs/distributed-dimensions-velocity-<version>.jar`
  - plus the api artifact: `:api:build` (sources + javadoc jars; published manually to GitHub
    Packages — nothing is published by CI).
- Versions: root `version=1.0.0` (jars), api `1.0.0` (independent semver via `-PapiVersion=`).
- Tests: JUnit 6; `common` against H2 (PostgreSQL mode); Mongo contract tests gated on
  `TEST_MONGODB_URI` (skip otherwise); `paper` uses deterministic fake schedulers
  (`FakeSchedulers`) and test fixtures — no live server required for unit tests.
- Java 25 to *build*; Paper 26.3+ / Folia and Velocity 4.2+ to *run*.
- Release workflow (`.github/workflows/release.yml`): push tag `v*` → JDK 25 → `./gradlew build` →
  GitHub Release with both jars. There is **no CI gate running tests on push/PR** — releases
  are cut by hand on tags.
- Artifacts embed and relocate dependencies (Configurate, gson, HikariCP, JDBC drivers, Mongo
  driver, snakeyaml) — server administrators need no downloads beyond the two jars.
- Dev servers for manual testing: `./gradlew :paper:runServer` / `:velocity:runVelocity`.

## Design constraints — non-negotiables

1. **Vanilla parity first.** Reproduce Java Edition behaviour exactly, including its
   quirks and worst-case edge behaviour, before inventing anything. If vanilla does it, we
   do it; we never replace vanilla mechanics with "simpler" ones for convenience.
2. **Fail-closed everywhere.** Any subsystem that cannot initialise (config, persistence,
   routing, journal) refuses transfers loudly; nothing half-opens, nothing silently degrades
   into "probably fine". The plugin stays enabled so an admin sees a live failure in the log.
3. **Anti-duplication.** The journal CAS is the only ownership arbiter; `request_id`
   idempotency on the proxy; exactly one terminal event per operation; cancelled Start events
   are not failures; complete-event dispatch is owned by the committing party.
4. **No player-data sync inside this plugin.** Inventory/XP/effects belong to the external
   sync plugin; we at most *wait* for its signal, bounded, with an honest degraded mode. We
   will never re-implement HuskSync.
5. **No NMS / mojang-mapped code** without a separately proven need; **no reflection hacks**
   around standard APIs without justification; no `ClientboundSetDimensionType` spoofing —
   the dimension type is native and correct because each backend serves its own dimension.
6. **Folia-safe from day one.** One scheduler facade; no cross-region access; no world access
   from async threads; retire callbacks never touch the world.
7. **Secrets never printed.** `${VAR}` expansion at load, masked toStrings, redacted URLs in
   exception messages. Configuration heal never rewrites the file on disk.
8. **Busy work is opted-in.** Mirage worlds exist to keep foreign-dimension probes
   non-crashing; they are void, inert, never-saved and unload-cancelled. They are honesty
   scaffolding, not content.

## Reading map

| Document | What it covers |
|---|---|
| `docs/installation.md` | zero-to-hero network setup: proxy, backends, database, HuskSync, verification |
| `docs/configuration.md` | every `config.yml` key, load semantics, env-var indirection |
| `docs/architecture-transfer.md` | the transfer/arrival contract in full, failure matrix, event ordering |
| `docs/velocity.md` | the proxy companion: validation pipeline, idempotency, `/ddproxy`, backend contract |
| `docs/player-sync.md` | external sync integration: strategies A–D, HuskSync setup, troubleshooting |
| `docs/end-mechanics.md` | End entry/exit, respawn-point recording & validation |
| `docs/mirage-dimensions.md` | mirage (void) worlds: creation, guards, risks, naming |
| `docs/api.md` | public API: artifacts, events, compat matrix |
| `docs/compat.md` | compat A/B/D in detail, ordering guarantees, never-list |
| `docs/folia.md` | backend foundation: boot, scheduler facade, chunk tickets |
| `docs/limitations.md` | the honest matrix: what works, what doesn't, what's untested |

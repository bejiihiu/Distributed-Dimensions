# Distributed Dimensions — Public API

The `distributed-dimensions-api` module is the contract between the Distributed Dimensions
backend plugin and third-party plugins. It is versioned with
[semantic versioning](https://semver.org/) **independently** from the core plugin, and it carries
no implementation dependencies: nothing but the Paper/Folia api (which every consumer already
runs on) plus the api jar itself.

This document covers:

- artifact coordinates and dependency setup
- entry point and services
- events and their ordering guarantees
- compat matrix (A / B / D / C) — what third-party plugins can and cannot see
- migration notes

## Artifact

```
<groupId>kz.bejiihiu</groupId>:<artifactId>distributed-dimensions-api</artifactId>:<version>1.0.0</version>
```

Starting with 1.0.0. Gradle (JitPack-style or GitHub Packages — see *Publishing* below):

```kotlin
repositories {
    mavenCentral() // not yet; reserved once the artifact is mirrored
    maven { url = uri("https://maven.pkg.github.com/bejiihiu/Distributed-Dimensions") }
}

dependencies {
    compileOnly("kz.bejiihiu:distributed-dimensions-api:1.0.0")
}
```

Always `compileOnly` — the backend plugin ships the api classes at runtime. Do **not** shade or
minimize it into your jar.

## Entry point

```java
import kz.bejiihiu.dimensions.api.DistributedDimensionsAPI;

DistributedDimensionsAPI api = DistributedDimensionsAPI.getInstance();
```

`getInstance()` throws `IllegalStateException` until the backend plugin has registered itself
during startup. If your plugin depends on these events and services, declare a `depend` /
`loadbefore`-style ordering relationship with the backend plugin so the api is available when
your `onEnable` runs.

Services, all obtained from the singleton:

| Service | Accessor | What it does |
|---|---|---|
| `TransferManager` | `api.transfers()` | programmatic transfers + operation status |
| `DimensionRegistry` | `api.dimensions()` | read-only snapshot of the configured dimension → backend mapping |
| `PortalRegistry` | `api.portals()` | per-entity portal cooldown view |

## TransferManager

```java
TransferResult result = DistributedDimensionsAPI.getInstance().transfers()
        .transfer(player, DimensionId.THE_END);
if (!result.ok()) {
    // rejected right now (validation, cooldown policy, cancelled start event)
    getLogger().warning("transfer refused: " + result.error() + " " + result.message());
    return;
}
UUID op = result.operationId();

// later:
Optional<OperationState> state = DistributedDimensionsAPI.getInstance().transfers().status(op);
```

- `transfer(Player, DimensionId)` — the same machinery and guarantees as a portal transfer.
- `transfer(Entity, DimensionId)` — **fails fast with `TransferError.REJECTED` in this
  build.** Entity transfer itself **is** shipped (DB-mediated, portal-driven, opt-in and
  disabled by default — `entities.cross-server-transfer.enabled`, see
  `docs/entity-transfer.md`), but it is not routed through this API method: there is no
  entity operation id to mint here and no snapshot transport to trigger. Enabling entity
  transfer means enabling the portal-driven pipeline, not calling this method.
- `status(UUID)` — `Optional<OperationState>`; empty if this backend has no record (unknown id,
  or the completed operation has aged out of the journal).

The **request** outcome (`TransferResult`) is synchronous. The **terminal** outcome is always an
event (see below). Arrival position is intentionally not in `TransferResult` — it is known only
once the operation commits on the target.

## DimensionRegistry

```java
Optional<DimensionInfo> info = api.dimensions().byServer("nether");
Optional<DimensionInfo> info = api.dimensions().byDimension(DimensionId.NETHER);
List<DimensionInfo> all  = api.dimensions().all();   // unmodifiable snapshot
```

Snapshots are immutable and capture the configuration at call time. Backend names match the
names registered on the proxy, case-sensitive.

## PortalRegistry

```java
if (api.portals().isPortalCoolingDown(entity)) { return; }
```

Mirrors the vanilla per-entity portal cooldown (300 ticks for ordinary entities, 10 ticks for
minecarts/boats, ~2 ticks for projectiles; entities with a player passenger never cool down).
Use it if you implement your own portal triggers.

## Events

All events are synchronous Bukkit events (`org.bukkit.event.Event` subclasses, static
`getHandlerList()`, per-concrete-type handler list). Listen with `@EventHandler` as usual.

Common payload (`DimensionTransferEvent` base type): `getOperationId()`, `getEntity()`,
`getPlayer()` (nullable — non-player transfers), `isPlayerTransfer()`, `getFrom()`, `getTo()`,
`getCause()` (`TransferCause`: `NETHER_PORTAL`, `END_PORTAL`, `RESPAWN`, `PROGRAMMATIC`,
`MIRAGE_TELEPORT`).

| Event | Backend | Cancellable | Fires when |
|---|---|---|---|
| `DimensionTransferStartEvent` | source | **yes** | immediately before the proxy transfer request; cancelling aborts the whole operation |
| `DimensionTransferCompleteEvent` | target | no | the player is fully ready (sync done, location recorded); carries `getDurationMillis()`. Player operations only — entity operations never dispatch it in this build (they dispatch no public events at all). |
| `DimensionTransferFailedEvent` | source **or** target — whichever terminally records the failure | no | the operation failed; carries `getError()` (`TransferError`), `getPhase()` (`OperationState` at failure detection), `isPlayerOnline()` |
| `EntityTransferEvent` | target | no | **Never dispatched in this build** — declared in the api, but the shipped entity pipeline (DB-mediated, `docs/entity-transfer.md`) does not surface completion through it; player transfers never produce it either. Do not listen expecting it to fire. |
| `PortalFoundEvent` / `PortalCreatedEvent` | target | no | **unstable, internal detail — do not listen.** Portal resolution internals; payload/timing may change in any minor release. |

### Ordering guarantees

1. **Start strictly before the proxy request.** `DimensionTransferStartEvent` is dispatched on
   the source backend after the triggering interaction is accepted and before any disconnect or
   proxy request. Cancelling it cancels everything; a cancelled start fires no further events
   and is not reported as a failure.
2. **Target ordering: `PlayerJoinEvent` → (external sync plugin finished) →
   `DimensionTransferCompleteEvent`.** Listeners that need the post-transfer state should listen
   to the complete event, not to `PlayerJoinEvent`. Entity transfers dispatch no public entity
   events in this build (`EntityTransferEvent` is declared but never fired).
3. **Exactly one terminal event per operation:** either `DimensionTransferCompleteEvent` or
   `DimensionTransferFailedEvent`, delivered on the backend that terminally records the outcome.
   Pre-dispatch and proxy-unreachable failures fire on the source; failures detected after the
   target accepted the request fire on the target.
4. **`DimensionTransferFailedEvent` is guaranteed** if the operation ends in FAILED/timeout —
   even if the player is already offline on the firing backend (`isPlayerOnline()` is then
   `false`; treat the entity reference with care).
5. **Idempotent dispatch.** A network retry will not produce a second complete event for the
   same operation; the target deduplicates by operation id (completed operations are remembered
   for a bounded time, ~5 minutes). Listeners may still key on `operationId` for their own
   bookkeeping.

Thread context (Folia): portal-driven flows dispatch on the region thread of the portal block;
entity-driven flows on the entity's owning thread; respawn-driven flows in the respawn handling
context; complete/failed events on the thread handling the terminalization on the relevant
backend. Listeners must not perform blocking I/O and must touch world state only through the
appropriate schedulers.

### Entity reference validity

For player transfers, `getEntity()` **is** the player. After a disconnect, methods on the
reference may throw or return stale data — on `DimensionTransferFailedEvent`, check
`isPlayerOnline()` first. For non-player transfers on failed operations, the source entity was
not removed.

## Compat matrix

Third-party plugins perceive cross-server dimension changes only through what we honestly
provide. Compat mode is a **config flag of the backend plugin**, never part of this api.

| You want | Mode A (default, honest) | Mode B (`compat.fire-synthetic-events: true`) | Mode D (mirage dimensions) | Mode C |
|---|---|---|---|---|
| Notification that a dimension change happened | `DimensionTransferCompleteEvent` (+ `StartEvent` on source) | same | same | N/A |
| Cancel a player portal travel | cancel `DimensionTransferStartEvent` (this api) or intercept `PlayerPortalEvent` earlier | same | same | N/A |
| "Teleport cause" signal (`NETHER_PORTAL`/`END_PORTAL`/`END_GATEWAY`) | not present | synthetic `PlayerPortalEvent` + `PlayerTeleportEvent`, with `from == to == arrival point` | included via B-like synthetics (optional) | N/A |
| `PlayerChangedWorldEvent` | never fired (honest source world object does not exist; Paper constructor is `@Internal`) | never fired | never fired | impossible — do not try |
| `PlayerTeleportEvent` at all | not dispatched for transfers | synthetic, `from == to` (documented limitation) | synthetic, `from == to` | N/A |
| Vanilla advancements (`enter_the_nether`, `enter_the_end`) | criteria awarded idempotently on the target backend | same | same | N/A |
| Client sees the correct sky/biome/dimension type | yes — native login-packet `dimension_type` | yes | yes | N/A |
| `Bukkit.getWorld("world_nether")` non-null on every backend | no | no | **yes** — void `mirage` worlds, `Environment.NETHER/THE_END` | N/A |
| `player.teleport(netherWorld)` actually travels | no (world doesn't exist) | no | **yes** — guard cancels + routes as a transfer | N/A |
| NMS-level `DimensionTransition` | no | no | no | impossible (out of scope) |

**Mode C does not exist.** NMS-level change-dimension cannot be emulated from the Bukkit api and
is deliberately not attempted. Plugins bound to NMS dimensions will not observe transfers in any
mode.

Notes and caveats:

- Compat B's synthetic events carry `from == to == arrival point`. Listeners reading
  `getFrom()` will see a zero-length teleport — that is the documented limitation; there is no
  honest `from` across JVMs.
- Nothing in any mode makes the source world object exist on the target backend.
- `EntityPortalEvent` is never fired synthetically by us (its constructors are `@Internal` and
  meaningless without a source world).
- Compat D mirages: `Bukkit.getWorld(...) != null`, correct `getEnvironment()`, but the worlds
  are void/never-ticked, never saved, and unloadable events are cancelled. Portal mechanics
  never route into a mirage world as a real destination.

## Listening examples

```java
@EventHandler
public void onDimensionChange(DimensionTransferCompleteEvent event) {
    if (event.getTo().equals(DimensionId.THE_END)) {
        // bookkeeping...
    }
}

@EventHandler
public void onTransferVeto(DimensionTransferStartEvent event) {
    if (!myPolicy.allows(event.getPlayer(), event.getTo())) {
        event.setCancelled(true); // player stays exactly where they are
    }
}

@EventHandler
public void onTransferFailed(DimensionTransferFailedEvent event) {
    if (!event.isPlayerOnline()) {
        // bookkeeping only — the entity reference is not usable for world access
    }
    metrics.count("dimension_transfer_failed", event.getError().wireName());
}
```

## Versioning and migration notes

- **1.0.0** — first public release. Contract types: `DistributedDimensionsAPI`,
  `TransferManager`, `DimensionRegistry`, `PortalRegistry`, `DimensionId`, `DimensionInfo`,
  `OperationState`, `TransferCause`, `TransferError`, `TransferResult`, all events in
  `event/`.
- `PortalFoundEvent`, `PortalCreatedEvent` are explicitly **unstable** and may change in any
  minor release; depending on them is at your own risk.
- The `OperationState` / `TransferError` / `TransferCause` enums mirror the implementation's
  journal tokens (`wireName()`); do not switch on ordinals, and expect new constants to appear
  in minor releases (handle unknown values defensively).
- Value types are `record`s — they may gain accessors but never lose them in a minor release.
- The api artifact shadows nothing: do not relocate or embed it.
- Migrating from pre-1.0 snapshot builds: type names are unchanged; `status()` now returns
  `Optional<OperationState>` instead of a nullable `OperationState` — adjust null checks.
- Sunsetting: if a future backend drops support for a cause (e.g. `MIRAGE_TELEPORT` when mirages
  are disabled), the constant remains for compatibility; it is simply never fired.

## Publishing (for maintainers)

The `api` module applies `maven-publish` and builds sources + javadoc jars. Version comes from
`-PapiVersion=X.Y.Z`, falling back to `1.0.0`. Nothing is published by CI; cutting a release is
a manual ritual:

```powershell
.\gradlew.bat :api:build
# sanity: inspect the artifacts
Get-ChildItem api\build\libs
.\gradlew.bat :api:publishMavenPublicationToGitHubPackagesRepository -PapiVersion=1.0.1
```

`GITHUB_ACTOR` / `GITHUB_TOKEN` (or `-Pgpr.user=` / `-Pgpr.key=`) must be set for the GitHub
Packages repository. The token needs `write:packages`. After publishing, tag the release
(`api-v1.0.1`) and bump the in-repo fallback version in a follow-up commit.

Consumers resolve the package from `https://maven.pkg.github.com/bejiihiu/Distributed-Dimensions`
with a `read:packages` token (GitHub Packages requires authentication even for public packages).

# Cross-server transfer orchestration

How a player gets from one backend to another: portal detection on the source, the proxy
switch, the arrival on the target, and every way it can fail. This document is the contract
the Nether wave and the entities wave build on. The code path is `kz.bejiihiu.dimensions.paper.transfer`
(implementation) plus the respawn bits in `kz.bejiihiu.dimensions.paper.end`.

Anything not covered here is not guaranteed.

## The one-paragraph model

A transfer is a row in a shared journal table (`<prefix>transfer_operations`) whose state
machine is `CREATED -> PREPARING -> TARGET_CREATED -> SOURCE_REMOVED -> COMMITTED`
(`FAILED` from any non-terminal state). The source backend records the row and pushes a
`dimension_transfer_request` at the proxy over the player's connection. The proxy validates,
moves the player with Velocity's API, and answers with `dimension_transfer_result`. The
target backend claims the row (compare-and-set) when the player connects, places them by
policy, and drives the row to `COMMITTED`. Because the journal is shared and every
transition is a compare-and-set, exactly one backend terminates the operation and exactly
one `DimensionTransferCompleteEvent` / `DimensionTransferFailedEvent` is dispatched per
operation — even under retries, piggybacked results and mid-flight disconnects.

## Wire messages and the channel

Channel: `distributeddimensions:transfer` (the same id both sides register).

- Backend -> proxy: `dimension_transfer_request` (`DimensionTransferRequestMessage`) carrying
  `request_id` (= the journal operation id), `player_uuid`, `source_server`,
  `target_server`, `source_dimension`, `target_dimension`, `target_position`, `cause`.
- Proxy -> backend: `dimension_transfer_result` (`DimensionTransferResultMessage`) carrying
  `request_id`, `ok`, optional `error` / `message`.
- Transport for both directions is **plugin messaging piggybacked on a player connection** —
  there is no out-of-band proxy channel on either platform. `player.sendPluginMessage(plugin,
  channel, bytes)` on the backend reaches the proxy's `PluginMessageEvent` with
  `source instanceof ServerConnection`. Results may be piggybacked on *any* connection of the
  source backend, so the receiving side must never attribute the carrier to the subject
  unless it also holds the pending operation.
- Payloads are bounded by the 32,766-byte plugin-message limit; the wire format is the
  protocol v1 from `common` (`ProtocolCodec`).

The proxy's result may arrive **after** the player has left — it is delivered on whatever
connection the proxy still owns. The source correlates by `request_id` alone; it never
trusts the carrier connection as identity proof.

## Source flow

`PortalTransferService.transfer(Player, from, to, cause, arrival)` — called from the
triggering portal's region thread (Folia) with the portal event **already cancelled**
(vanilla cannot cross servers; an event that stands is a silent no-op transfer). All
steps are fail-closed: any refusal leaves the player exactly where they are and the
journal row is either never created or terminally failed.

```text
portal event (region thread)
  1. isHealthy()?                     - no  -> Refused (no journal row, no event)
  2. route(target) / route(source)    - none -> Refused (misconfiguration)
  3. journal CREATED (id = v4 UUID)   - DuplicateOperationException / PersistenceException
                                        -> Refused
  4. DimensionTransferStartEvent      - synchronous (the triggering thread, per the api
     (cancellable)                      contract); cancelled -> journal row -> FAILED
                                        (REJECTED), NO failed event, no request sent
  5. CAS CREATED -> PREPARING          - lost -> journal row -> FAILED (UNKNOWN), Refused
  6. encode DimensionTransferRequestMessage
  7. channel.send(player, payload)     - throws -> row -> FAILED (UNAVAILABLE) +
                                        DimensionTransferFailedEvent (PREPARING), Refused
  8. track pending (TTL-capped map) + schedule the watchdog (2x network.transfer.timeout)
```

Outcome types: `Started(operationId)`, `Refused(reason)` (player untouched, never fired a
Start event), `Cancelled(operationId)` (a listener vetoed; no request was sent; **no**
failed event — cancellation is not a failure).

### Watchdog

`watchdogCheck(operationId)` runs async at `2x network.transfer.timeout` (default
`10000 ms` -> `20000 ms`): if the row is still non-terminal it is CAS'd to `FAILED` with
`TIMEOUT` and `DimensionTransferFailedEvent` is dispatched (on the global region thread)
with the phase the failure was detected in. If any path terminated the row first — the
proxy answered, the target claimed and the joint flow completed — the watchdog is a no-op.

## Proxy flow (for reference)

The proxy validates the request (fail-closed gate, player identity on that connection,
claimed source == connection's server, routing from **its** config, idempotency by
`request_id`, replay of completed ops), executes `ConnectionRequest` with a
`Duration` timeout, and answers with `dimension_transfer_result`. The respawn-point lookup
is **not** answered by the proxy: both backends share the journal/respawn database, so the
end exit portal resolves the destination locally (see below).

## Target flow (arrival)

Two Bukkit events, one service (`TransferArrivalService`):

```text
AsyncPlayerSpawnLocationEvent   (configuration phase; player NOT in the world yet; async)
  1. read the newest pending op for the UUID (findActiveForPlayer is oldest-first; pick the
     last CREATED|PREPARING)
  2. CAS claim: CREATED -> PREPARING (if still CREATED) -> TARGET_CREATED
     (both steps legal in the state machine; a lost CAS means another path owns the op -> skip)
  3. ArrivalPlanResolver decides placement (see below); the listener applies
     setSpawnLocation(location) to the event
  4. an async 2x-timeout "claim watchdog" is armed for the whole claim->finalize window

PlayerJoinEvent                 (player now in the world)
  5. sync wait: SyncServices.get() -> awaitSync(player) — never joined; the placement
     work below runs only after the wait completes normally:
       - degraded=true (level D / disabled): logged; the arrival proceeds unconfirmed
       - sync services entirely missing (get() throws): warning; proceed degraded
     then, on the entity's scheduler:
       - stored position: optional PositionValidator veto (bed/anchor presence + charge);
         invalid -> teleport to the world spawn (vanilla "missing respawn block" semantics);
         materially different placement -> teleport (PLUGIN cause)
  5b. on exceptional completion (SyncTimeoutException or any future error):
       - the player is NOT moved (keeps the provisional spawn placement)
       - journal row -> FAILED with the mapped error; the player uuid + reason are
         logged at SEVERE; DimensionTransferFailedEvent dispatched exactly once
         (playerOnline=false when the player already left)
       - DimensionTransferCompleteEvent is NOT dispatched
  6. two entity-scheduler ticks later:
       - chunk ticket is added for the arrival chunk (floor-div, 15s TTL refresh semantics
         from the vanilla destination-loader window; the ring-of-8 is governed by
         entities.cross-server-transfer.chunk-tickets)
       - journal advances TARGET_CREATED -> SOURCE_REMOVED -> COMMITTED (two CASes)
       - DimensionTransferCompleteEvent is dispatched (durationMillis = now - createdAt)
```

`DimensionTransferCompleteEvent` fires **only** if the sync wait completed normally and
this backend's CAS transitions succeeded — a concurrent terminal move (e.g. the source
watchdog) skips the event entirely, so the exactly-one-terminal-event guarantee holds on
the target too.

### Arrival plans

`ArrivalPlanResolver` decides placement on the target, resolved **per backend** at wiring
time. The default (`ArrivalPlanResolver.journalPosition()`) places the player at the
journal position in the backend's respawn world. The End module plugs in
`EndArrivalPlanResolver`, which also re-reads the shared respawn store for exit-portal
operations (`cause=END_PORTAL` and `sourceDimension=minecraft:the_end`):

- respawn record present and its dimension equals the operation's target dimension ->
  `Stored(record.world(), record.position())` (bed/anchor coordinates)
- otherwise -> `WorldSpawn()` — the backend's `getRespawnWorld().getSpawnLocation()`,
  explicitly set on the spawn event so returning players cannot land at a stale data-file
  position (the vanilla "unset/obstructed respawn point" semantic)

Entry-portal operations (source not The End) always use the journal position — the obsidian
platform for End entry.

### Position validation (the bed/anchor hook)

`PositionValidator` is an optional veto for stored positions, evaluated on the target's
region thread after join. `BedAnchorValidator` implements it for the End exit flow: a
recorded bed must exist (block or face neighbour is bed-shaped) and a recorded anchor must
exist **with at least one charge**; otherwise the arrival falls back to world spawn. The
validator never vetoes plain portal-target arrivals (no respawn record) and never blocks
the transfer — it only redirects placement.

## Idempotency and double-dispatch protection

- **Journal is the source of truth.** Every terminal transition is a CAS; the party whose
  CAS succeeded dispatches the terminal event. Duplicate joins, duplicated proxy results,
  racing watchdogs — all serialize on the row.
- **Start events dispatch at most once per operation** by construction (the request is sent
  only after the Start event fires). A cancelled Start is terminal for the operation:
  journal row `FAILED (REJECTED)`, no failed event.
- **Complete event idempotent per operation**: the target's claim CAS is what arms it;
  firing is owned by the same party that commits (`SOURCE_REMOVED -> COMMITTED`).
- **Piggybacked results** correlate by `request_id` only; a late failure result for a
  terminally-failed row is dropped with a debug log, never re-fires the event.
- **Pending tracker** (source side, in memory): bounded map (1024 entries) with a 2-minute
  hard TTL, used purely to construct the entity for the api events when the failure cannot
  be attributed to a still-usable player object — an entry may legitimately vanish
  (eviction, TTL), in which case the journal is still terminally failed and the failed
  event is dropped with a SEVERE log line (the api contract requires an entity, and none
  exists anymore). This is the only designed gap: **an offline player can cost the target
  its `DimensionTransferFailedEvent` dispatch — never the journal's terminal state.**

## Failure matrix

| Where it fails | Detection | Journal | Events | Player |
|---|---|---|---|---|
| Backend degraded | `isHealthy()` gate | none | none | stays, message |
| Unroutable dimension / not ours | config routing | none | none | stays, message |
| Journal record failure | `recordCreated` | none (or fail) | none | stays, message |
| Start event cancelled by an integration listener | listener | `FAILED (REJECTED)` | **none** | stays |
| Journal race before dispatch | CAS CREATED->PREPARING lost | `FAILED (UNKNOWN)` | none* | stays, message |
| Encode failure (our bug) | encode exception | `FAILED (UNKNOWN)` | failed (PREPARING) | stays, message |
| Channel send failure | `TransferChannelException` | `FAILED (UNAVAILABLE)` | failed (PREPARING) | stays, message |
| Proxy never answered | watchdog @ 2x timeout | `FAILED (TIMEOUT)` | failed (phase at detection) | current placement kept (or proxy switched them anyway) |
| Proxy reported failure | result message, CAS to FAILED | `FAILED (error from result)` | failed | stays, message |
| Player disconnected mid-switch | proxy replies DISCONNECTED -> result path, or claim watchdog | `FAILED (DISCONNECTED or TIMEOUT)` | failed (playerOnline=false) | gone; journal terminal |
| Player quit during configuration | claim watchdog @ 2x timeout | `FAILED (TIMEOUT)` | none (no player to reference) | gone; journal terminal |
| Stored respawn block gone | `PositionValidator` at join | `COMMITTED` | complete (fallback placement) | world spawn (vanilla semantics) |
| Sync wait timed out / errored | `SyncTimeoutException` / exceptional future | `FAILED` (timeout: `TIMEOUT`; player left: `DISCONNECTED`; broken integration: `UNAVAILABLE`) | failed (phase = state at detection, never terminal) | NOT moved — keeps the provisional spawn placement |
| Sync degraded completion (level D) | normal completion, `degraded=true` | `COMMITTED` | complete | normal arrival (unconfirmed) |
| Sync services entirely missing | `SyncServices.get()` throws | `COMMITTED` | complete | normal arrival, degraded + warning logged |
| Player quit before finalize | claim watchdog @ 2x timeout (sync wait failure wins first in practice) | `FAILED (TIMEOUT)` | none if the watchdog wins with no player reference; failed (playerOnline=false) if the sync path fails first | journal terminal; partial teleport possible (edge) |
| Lost CAS at finalize | concurrent terminal move | unchanged (already terminal) | complete **skipped** | normal arrival |
| Chunk-ticket failure | caught | unchanged | complete | normal arrival (ticket is an optimization) |

\* none: the `DimensionTransferFailedEvent` requires an entity and a mutation that did not happen — the row is failed; no event fires. `Refused` path carries the reason.

## Event ordering contract

- Source: `DimensionTransferStartEvent` strictly before any disconnect / request send;
  cancellation vetoes the transfer; cancelled start produces **no** failed event.
- Target: `PlayerJoinEvent` -> bounded sync wait (`SyncServices.get()` →
  `awaitSync(player)`, never joined, bounded by `sync.wait-timeout`) -> placement
  (validator/teleport) -> two entity-scheduler ticks -> `DimensionTransferCompleteEvent`.
  The complete event fires only after the wait completes normally; if the wait fails, the
  complete event never fires and `DimensionTransferFailedEvent` is dispatched instead.
  On a degraded completion (level D / wait disabled) the event fires unconfirmed — the
  report's `degraded` flag is log-only.
- Exactly one of `DimensionTransferCompleteEvent` / `DimensionTransferFailedEvent` per
  operation, always dispatched on the backend that terminally recorded the outcome.

## Configuration and knobs

- `network.transfer.timeout` (ms, default 10000) — feeds the watchdog (2x) and the target
  claim watchdog (2x).
- `network.dimensions.{overworld,nether,end}` — routing for both directions.
- `portals.end.enabled` — when false, the end portal handler cancels the event and refuses
  (never lets vanilla fall through to a same-server endpoint).
- `entities.cross-server-transfer.chunk-tickets.{enabled,ring}` — arrival chunk tickets.
- `debug.transfers` — reserved (orchestration logs are info-level and sparse; no
  per-transfer spam by default).

## What the Nether wave plugs in

The orchestration is portal-family-agnostic. Nether reuses:

- `PortalTransferService` (same health gate, journal, watchdog, result handling)
- `TransferArrivalService` with a **composite `ArrivalPlanResolver`** — portal operations
  resolve to the journal position as a *provisional* spawn and are then relocated by the
  settle step (the search/create runs on the destination backend); the End parts of the
  composite delegate to `EndArrivalPlanResolver` (nether-specific: `NetherArrivalPlanResolver`)
- `PositionValidator` chain: portal operations bypass the bed/anchor veto entirely (a stale
  bed record must not redirect a nether arrival to world spawn); everything else delegates
  to `BedAnchorValidator` (`NetherCausePositionValidator`)
- `TransferChannelSink` semantics: the proxy answers `dimension_transfer_result` the same
  way, and the source-side watchdog behaves identically.

The nether wave (`docs/nether-portals.md`) additionally wires a `PostClaimArrivalStep`
(`NetherArrivalExecutor`) into `TransferArrivalService`: after join it runs the vanilla
destination-side portal search (shared `portal_index` + live-world verification + the
64-chunk on-demand fill, full scan in degraded mode) or the vanilla creation algorithm, and
the settle step relocates the player to the computed exit. The End-specific bits —
`RespawnRecorder`, `EndPortalListener` — are still used for the end portals only; the
journal state machine and the wire protocol are shared.

## What the entities wave plugs in

Non-player entities reuse `PortalTransferService`'s journal/watchdog shape and the same
`TransferPending`/route-tracker idea, but: the request message differs, there is no
`AsyncPlayerSpawnLocationEvent` (arrival is materialization of an entity on the target),
there is no join event, and the cooldowns are vanillas (300/10/2 ticks by entity class, 0
with a player passenger). `EntityPortalEvent` is the interception point, and the
`EntityTransferEvent` api event replaces the join-based arrival. This document's failure
matrix and idempotency rules apply unchanged.

## Known limitations

- **Respawn point truth is local to the shared database.** If the operator's backends do
  not share the SQL/Mongo database, the End exit portal cannot route by respawn point and
  behaves as "no record" everywhere (world spawn). Sharing the database is a hard
  requirement of the cross-server respawn flow.
- **Anchor-in-Nether exit with a dead anchor** falls back to the *nether world spawn*;
  vanilla would send the player to the overworld world spawn. Redirecting to the overworld
  from the nether target would require a second proxy hop in the failure path — declined.
- **Custom dimensions** do not record respawn points (no route exists); a bed there is
  invisible to the exit portal.
- **/spawnpoint and plugin-set spawns** are not tracked; only bed/respawn-anchor
  interactions are.
- **A player whose stored bed/anchor vanished** lands at world spawn with a log line; the
  record itself is kept (it may be valid again later).
- **Chunk tickets are an optimization**: if the add fails, arrival proceeds normally.
- **The migration v2 is applied on open** (`PersistenceFactory`); a backend that cannot
  migrate enters fail-closed and refuses transfers entirely.

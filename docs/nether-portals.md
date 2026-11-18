# Nether portals - cross-server mechanics

What lives where, what the plugin does and - just as important - what it deliberately
does **not** do. Read alongside `docs/architecture-transfer.md` (the orchestration this
plugs into) and `docs/end-mechanics.md` (the sibling portal family).

Target Minecraft: **Java Edition 1.21.x (Paper/Folia, plugin 26.3 API)**. Anything else is
out of scope.

## Overview

**The Overworld backend is a full, native Overworld and the Nether backend a full, native
Nether.** Portals, obsidian frames, portal blocks, fire, random ticks, the vanilla chunk
loader behaviour - all of it is vanilla mechanics of one server and needs no implementation.
The plugin must never intercept, rewrite or re-route any of it.

Cross-server Nether mechanics are exactly two transitions:

1. **Overworld -> Nether** and back, through `NETHER_PORTAL` blocks
2. The chunk tickets that keep the arrival area alive (vanilla's portal chunk loader)

## Flow at a glance

```
overworld backend                               nether backend
-------------------                             ----------------
player stands in NETHER_PORTAL block
  vanilla delay elapses (gamerules)              (no work happens here yet)
        v
PlayerPortalEvent (completion!)                 
  NetherPortalListener: cancel - always         
  cooldown check (PDC, 300 t) - skip if hot     
  compute ideal destination via converter        
  (OW: floor(x/8), N: x*8, Y kept, yaw/pitch)   
  capture source portal context (POI scan):    
  fractions across the source inner rectangle    
        v                                        
journal row (NETHER_PORTAL) + start event        
+ transfer_contexts row (shared DB, op id)      
        v                                        
proxy switch ---------------------------------> player connects (configuration phase)
                                                  AsyncPlayerSpawnLocationEvent
                                                    claim (CAS) -> provisional spawn = journal position
                                                  PlayerJoinEvent (entity scheduler)
                                                    NetherArrivalExecutor:
                                                      async: portal index query (AABB, 16/128)
                                                      region: harvest portal blocks from chunks
                                                      search (vanilla argmin 3D squared distance)
                                                      or create (vanilla planner, region build)
                                                  verifyAndFix: teleport to the portal exit
                                                  finalize: chunk ticket + journal COMMITTED
                                                      + DimensionTransferCompleteEvent
```

The player never sees the provisional spot - it is only a pre-computation anchor on a
connection that has not been assigned its final position yet.

## The delay: vanilla owns it, completely

**Fact check that dictated the design:** on Paper the vanilla delay elapses **before**
`PlayerPortalEvent` fires.

- `io.papermc.paper.event.entity.EntityPortalReadyEvent` - *"Called when an entity is ready
  to be teleported by a plugin. **Currently, this is only called after the required ticks
  have passed for a Nether Portal.** Cancelling this event resets the entity's readiness
  regarding the current portal."* (26.3 javadoc, see
  https://jd.papermc.io/paper/26.3/io/papermc/paper/event/entity/EntityPortalReadyEvent.html)
- `org.bukkit.event.player.PlayerPortalEvent` is the *teleport-completion* event that
  follows it (*"Called when a player is about to teleport because it is in contact with a
  portal"*, 26.3 javadoc, see
  https://jd.papermc.io/paper/26.3/org/bukkit/event/player/PlayerPortalEvent.html).

Consequences:

- The vanilla dwell timer, the early-exit reset, the creative bypass and the gamerules
  `playersNetherPortalDefaultDelay` (default 80) and `playersNetherPortalCreativeDelay`
  (default 1) are all honoured **by vanilla itself** with zero additional code. The event
  is cancelled, not re-timed: a parallel in-Paper dwell counter would only re-implement the
  same timer with worse fidelity and a doubled delay.
- `playersNetherPortalDefaultDelay` / `playersNetherPortalCreativeDelay` are vanilla
  gamerules since Java Edition 1.20.3 (23w42a) and exist on Paper 26.3 as
  `GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY` / `PLAYERS_NETHER_PORTAL_CREATIVE_DELAY`
  (`GameRule<Integer>`) - verified on jd.papermc.io (26.3). The listener reads them per
  completion for diagnostics only; a world that cannot answer falls back to the vanilla
  constants (80/1) via the `NetherPortalGamerules` seam.
- **How an admin gets instant transitions:** set the gamerules to `0` on the source world.
  That is a vanilla-supported gamerule change and needs no plugin support.
- **`portals.nether.preserve-vanilla-delay` (schema key): no effect in this wave.** The
  delay *is* vanilla's; the plugin cannot shorten it from the completion seam, and the
  gamerules above are the supported lever. Setting the flag `false` does nothing.
- **Idempotency inside one dwell:** repeated completions for the same dwell are suppressed
  with a 250 ms (5 tick) process-local marker (`NetherCrossingMarker`); vanilla re-arms at
  the full gamerule cadence, so a re-attempt after a refused transfer is never swallowed.
  The marker is also what the mirage teleport guard consults to avoid double-handling the
  same completion (see "Coexistence with the mirage guards").

## The trigger: PlayerPortalEvent

`NetherPortalListener` - `PlayerPortalEvent`, `TeleportCause.NETHER_PORTAL`, source world
environment `NORMAL` (overworld) or `NETHER`:

- The event is **always cancelled** first (vanilla cannot switch servers). A disabled
  `portals.nether.enabled` refuses with a message, same fail-closed semantics as the end
  portals.
- `THE_END` / custom environments are not ours: the event stands.
- Protection-style vetoes at `LOWEST`/`LOW` are honoured (`ignoreCancelled=true`): a plugin
  that cancels the portal first keeps the veto; we do not transfer.
- Priority is `NORMAL`, i.e. **before** the mirage teleport guard (`HIGH`). If that handler
  took ownership of the crossing, the guard sees the marker and leaves the event alone - a
  portal completion never produces two transfers. Plain teleports (`/tp` into a mirage) are
  never suppressed by the marker.
- `ignoreCancelled=false` plugins after ours (mirage guard at HIGH) co-exist exactly
  because of the marker above; compat-B's synthetic completion events still fire on the
  target through the normal journal attribution (`NETHER_PORTAL` cause), unchanged.
- Registered in stage 7 of plugin enable, like the end portals.

The listener never touches the world beyond reading the player's location and the source
world's gamerules.

## Computing the destination

Vanilla scales coordinates by the 1:8 Nether ratio, Y never scales:

| Direction | Conversion | Border clamp |
|---|---|---|
| Overworld -> Nether | `x = Math.floor(owX / 8.0)`, `z = Math.floor(owZ / 8.0)`, Y unchanged | nether default |
| Nether -> Overworld | `x = nX * 8.0`, `z = nZ * 8.0`, Y unchanged | overworld default |

`Math.floor` for negative coordinates - never a raw cast. Yaw/pitch are preserved. The
conversion is computed on the **source** side at portal completion time and carried in the
journal as the `target_position` (`NetherCoordinateConverter`, same math as vanilla's
`worldBorder.clamp(x * scale) -> BlockPos.ofFloored` chain, except the pre-switch clamp
cannot see the remote backend's configured border).

> **Known approximation vs remote borders.** The source backend cannot read the destination
> backend's configured world border before the switch (the schema is frozen; the border is
> not in it). The pre-switch converter clamps to the **vanilla default** bounds
> (overworld `±29_999_985`, nether `±3_749_998.125`). The arrival search on the destination
> backend clamps with the destination's **real** border - so only a custom border set
> exclusively on the remote backend can shift the ideal spot; a pathological border would
> simply trade a few blocks of offset for a guaranteed-legal arrival. Documented here and
> in the notes; closing it requires a schema change and is out of scope.

The journal position is the **provisional** arrival, never the final one. The final exit is
computed on the destination backend (below).

## Arrival: search on the backend that owns the world

The search/create runs **on the destination backend**, in three phases, none of which
touch the world until the player is queued for placement:

1. **Async spawn (claim).** `AsyncPlayerSpawnLocationEvent` claims the newest pending
   operation (the same pathway the End portals use) and sets the provisional spawn to the
   journal position. Nothing is read from the world here.
2. **After join (entity scheduler).** `NetherArrivalExecutor` runs:
   - **async:** the shared `portal_index` is queried for portal candidates overlapping the
     vanilla search square (radius **16** blocks when the destination is the nether,
     **128** for the overworld; |dx| <= r && |dz| <= r; full world height; borders applied).
     The index is a *hint* - the world is the truth.
   - **region threads:** chunk loads for the candidates' chunks (fast path) or, when no
     candidate exists, a spiral-scan of the search square with a **64 chunk budget** - the
     on-demand index fill. When the index is unavailable (backend degraded, database down)
     the scan is a **full** world scan of the square; arrival never silently invents
     "no portals exist". Candidate verification harvests every live `NETHER_PORTAL` block
     with a horizontal `Orientable` axis from the loaded chunks (chunk snapshots).
   - **entity scheduler:** the winner is picked by the vanilla metric - 3D **squared**
     distance (Y counts fully, never Manhattan), tie-break lower Y then lower x then lower
     z - via `PortalSearch.selectForDestination`. A found portal is placed via the vanilla
     hitbox/rectangle math (`PortalExitCalculator`, inner rectangle, hitbox-bottom,
     hitbox-center), re-mapping the relative fractions captured on the source (see
     "Source-relative placement" below). If nothing is found, the vanilla creation algorithm runs
     (`PortalCreationPlanner`: spiral search radius 16, full > partial site, forced
     placement with the 2x3 obsidian platform as the last resort) and the plan is executed
     via region tasks (portal blocks get `Orientable.setAxis` from the plan orientation;
     frame/platform obsidian gets no axis; everything is placed without physics and never
     drops items).
3. **Settle.** The existing `TransferArrivalService` settling step teleports the player
   from the provisional spot to the computed exit (`PLUGIN` cause), then the existing
   finalize path commits the journal (`TARGET_CREATED -> SOURCE_REMOVED -> COMMITTED`),
   dispatches `DimensionTransferCompleteEvent`, and adds the arrival chunk ticket.

The plan scan reads the world through **chunk snapshots** into an offline bitset view
(`ColumnScan`/`OfflineWorldAccess`), so the creation algorithm runs without ever reading
block state from a wrong thread. Portal blocks are always located via snapshot +
`Orientable` check; the `NETHER_PORTAL` blocks of a found or created portal are what get
indexed.

### Portal index (`portal_index` table)

The index stores one row per known active portal: `orientation`, `origin`, `inner_min`,
`inner_max` (no blocks). It accelerates "which portals might be near"; it never answers
"is this portal real" - every hit is verified against the live world on every arrival, and
the harvest reconciles continuously:

- **fill-on-demand:** empty index result → spiral-scan with the 64-chunk budget, insert
  discovered portals (capped at 32 inserts per arrival);
- **reconcile-on-use:** entries whose AABB was scanned and produced **no** surviving portal
  blocks are **deleted** (self-healing compaction); newly found portals are inserted;
- **fast path:** non-empty index → harvest only the candidates' chunks, verify, select.

A portal that appears between arrivals is found on the next arrival that scans its chunks;
a portal that was broken is dropped from the index the first time its area is scanned. The
index exists to make the search O(few chunks) rather than O(search square) in the steady
state; it is never a source of truth.

### Chunk tickets

Unchanged from the shared flow: two entity-scheduler ticks after the position verify, the
arrival **exit chunk** is ticketed (and ring-of-8 when `entities.cross-server-transfer.chunk-tickets.ring` is true) by the existing finalize path - the 15 s vanilla
destination-loader window, re-acquired on every passage. If the add fails, the arrival
proceeds normally (tickets are an optimization).

### Portal cooldown (players)

Vanilla cools down **everything** that passes through a portal - 300 ticks (15 s) for
ordinary entities including players, 10 ticks for minecarts/boats, ~2 ticks for projectiles,
and **nothing at all** for a player-carrying assembly - so a player whose arrival lands them
back inside the exit portal cannot re-trigger it for 15 seconds. This build reproduces that
for players with the same PDC mechanism entities use (`distributeddimensions:portal_cooldown_at`
+ `portal_cooldown_op`, via `PdcCooldownStore`):

- **arrival stamp (target backend):** when the nether arrival step completes - on any of its
  paths, including the provisional-placement fallback - the arriving player's PDC is stamped
  with the 300 t ordinary-entity window and the operation id. A player riding a vehicle is
  exempt (assembly semantics) and is not stamped.
- **completion guard (source backend):** before routing a portal completion into a transfer,
  the listener consults the same stamp. On cooldown the completion is cancelled silently
  (no transfer, no message) and the crossing is marked (`NetherCrossingMarker`) so the
  mirage guard does not pick it up either - the player stays in the portal, exactly like
  vanilla's inert portal. A player riding a vehicle is exempt and transfers immediately.
- **what this closes:** without the guard, a player arriving inside the destination portal
  would re-arm vanilla's 80-tick delay and ping-pong back and forth forever, one server
  switch every ~4 seconds. The cooldown makes the arrival's re-entry window inert.
- **scope:** players only. The non-player entity cooldown rules (300/10/2 ticks, player-
  assembly exemption) already apply to the entity transfer; the End portal path does not
  stamp players (no portal-cooldown semantics there either).

### Source-relative placement (v4 context table)

The vanilla rule - the traveller's hitbox-reduced spot inside the source inner rectangle is
re-mapped onto the destination rectangle - is now reproduced for players, without touching
the frozen wire protocol. The source backend, at the moment the portal completion fires,
captures:

- the source portal's **inner rectangle** and **horizontal axis** (`HORIZONTAL_AXIS` of its
  blocks), via the same POI scan the entity transfer already uses (`locateAllPoiInRange`,
  never a world scan); if the scan finds nothing, the transfer proceeds without context;
- the **relative fractions** of the player's hitbox inside that rectangle -
  `right`/`up` in `[0, 1]` from `PortalExitCalculator.relativePosition` (living-entity
  rules: the forward offset is zero), plus the hitbox width/height at event time
  (a crouching player is 1.5 blocks high - the exact value travels);
- nothing else. Velocity is zero across a connection switch in vanilla's behaviour anyway;
  yaw/pitch are already carried in the journal's `target_position`.

The fractions and the source axis travel **not** on the wire (the target backend never sees
the wire request - the switch is mediated by the proxy) but in a small shared-database table,
`transfer_contexts` (schema migration **v4**: `op_id` primary key, `player_uuid`,
`source_axis`, `fx`, `fy`, `forward`, `entity_width`, `entity_height`, `created_at`). The
source writes the row next to the journal insert with the same operation id (a failure only
degrades placement to bottom-middle); the target reads it at arrival time, deletes it at the
terminal path (`finalize` / sync failure / claim timeout), and reaps rows older than 10
minutes opportunistically. The table is entity-agnostic - nothing player-specific beyond the
diagnostics-only `player_uuid`, so the same mechanism can serve non-player portal passes
later.

On the destination backend, `PortalExitCalculator.exitPlacement` re-applies the fractions to
the found (or created) destination inner rectangle with the source axis carried alongside:

- portals of the **same axis** - the position maps across the shared rectangle axes and yaw
  is preserved unchanged;
- **perpendicular** portals (x vs z) - the fractions map across the other axis and yaw
  **increases by 90** (always, never decreases, regardless of travel direction - the
  1.21.x bytecode rule reproduced in `PortalExitCalculator`); velocity stays zero;
- **pitch** is preserved in every case; **velocity is zero** across the switch;
- if the hitbox is wider/taller than the source portal the source-side fraction falls back
  to `0.5`/`0.0` exactly like vanilla's `entityPosInPortal`, and that fraction maps onto the
  destination; a hitbox wider than the destination portal itself is re-mapped out-of-bounds
  exactly as vanilla does (no extra clamping).

When the context row is missing - the source scan failed, or the transfer was created
programmatically - the arrival falls back to the previous behaviour:
bottom-middle (`right = 0.5`, `up = 0.0`) with shared-axis yaw preservation.

### What the exit placement cannot know (remaining gaps)

- **velocity** is zeroed across the connection switch (vanilla would rotate it for
  perpendicular portals) - momentum does not survive a re-login anywhere on this network;
- **pitch** is preserved, **yaw** is preserved for the shared-axis case and rotated +90 for
  perpendicular portals;
- **search Y** is unrestricted (full world height) as in vanilla;
- the player spawns at the provisional (journal) position for ~1-3 ticks before the settle
  teleport - a one-block suffocation cannot deal damage inside that window, and nobody sees
  the intermediate position;
- the **forward offset** inside the portal plane is always zero for players (vanilla
  living-entity rules), so the perpendicular placement within the plane block is always the
  plane's center;
- the **End** portal path carries no context row (the end arrival does not use the exit-rect
  mapping) - only the nether path above does.

## Coexistence with the mirage guards (compat D)

`MirageGuard.onPlayerTeleport` runs at `HIGH` with `ignoreCancelled=false` and would
otherwise also see the cancelled `PlayerPortalEvent` (target world = the local mirage) and
request a second transfer with `MIRAGE_TELEPORT`. The Nether listener marks every completion
it takes over with `NetherCrossingMarker` (process-local, 120 s TTL, 250 ms dedupe); the
guard skips **portal-completion events** marked by the nether/end flows - and never plain
teleports. A portal completion is handled by exactly one flow; `/tp` into a mirage is
handled by the guard as before.

## Gamerules

`playersNetherPortalDefaultDelay` (default 80) applies to survival/adventure players;
`playersNetherPortalCreativeDelay` (default 1) to creative players. Both are read by the
source backend per completion for diagnostics and are honoured by vanilla natively. Custom
gamerules added by other plugins are irrelevant to this flow.

`portals.nether.enabled` gates the feature exactly like `portals.end.enabled` gates the end
portals: disabled → cancelled event + refusal message, never a same-server fall-through.

There is no `preserve-vanilla-*` family of switches anymore (they were dropped from the
schema in config-version 4):

- the delay is always vanilla's (set the gamerules to `0` for instant transitions);
- the search **is** the vanilla algorithm (a 1:1 port of the Java Edition source, in
  `kz.bejiihiu.dimensions.common.nether`);
- the created portal's orientation uses the source default axis (`X_AXIS`) until the wire can
  carry the source orientation (protocol v1, frozen; the source axis *is* carried via the
  v4 context table for exit math, just not across the wire for placement).

## Known limitations / decisions

- **Two-step arrival** - provisional (journal) spawn, then a ~1-3 tick settle to the portal
  exit - replaces vanilla's atomic teleport. Invisible in practice, required for
  correctness under Folia (no world reads before the player's arrival, all heavy work
  chunked across region threads after it).
- **64-chunk on-demand index fill** - the largest search square (destination overworld,
  128-block radius) exceeds the budget when the index is empty; portals there are found
  only if the spiral scan reaches them. With a healthy database this converges after a few
  arrivals as the index fills; in degraded mode the scan is unlimited (full square).
- **Source axis and source fractions do not ride the wire** — they travel in the v4
  `transfer_contexts` table instead (protocol v1, frozen); a context row is written on the
  source and claimed on the target, see "Source-relative placement" below.
- **Pre-switch border approximation** - default bounds only; see above.
- **Chunk-load failure during search/build** - the player keeps the journal placement (never
  an invented portal); the arrival still completes and the next pass re-scans.
- **Waterlogged replaceable blocks** are classified as fluid-empty in the creation-site
  scan (material-level approximation); a portal built over them drains like any portal into
  liquid in vanilla.
- **Adventure mode** is treated as survival (vanilla behaviour).
- **The `EntityPortalReadyEvent` seam** exists on Paper 26.3 and is the correct hook if
  cross-server transfers should also be available in `allow-nether=false` environments
  (where the vanilla ready event fires with no target world and `PlayerPortalEvent` never
  follows). Not wired in this wave: in configured deployments the mirage worlds guarantee a
  resolvable destination, and double wiring would double-handle completions.

## Constants

| Constant | Value | Where |
|---|---|---|
| search radius (dest nether / overworld) | 16 / 128 | `NetherPortalConstants` |
| creation spiral radius | 16 | `NetherPortalConstants.CREATION_SEARCH_RADIUS` |
| inner portal rectangle | 2x3 | `NetherPortalConstants` |
| forced placement floor / ceiling | 70 / `maxPlaceable - 9` | `NetherPortalConstants` |
| arrival chunk ticket TTL | 300 ticks (15 s) | `NetherPortalChunkTickets` |
| completion dedupe | 250 ms per player | `NetherCrossingMarker.DEDUPE_NANOS` |
| marker TTL | 120 s | `NetherCrossingMarker.TTL_NANOS` |
| cold-scan chunk budget | 64 (unlimited in degraded mode) | `NetherArrivalExecutor` |
| index insert bound per arrival | 32 | `PortalIndexer.INSERT_LIMIT` |
| backstop for hung chunk loads | 10 s | `NetherArrivalExecutor.BACKSTOP_MILLIS` |
| player hitbox | 0.6 x 1.8 | `NetherArrivalExecutor` |

## Verification sources

- Player/Portal event timing: 26.3 javadoc for `EntityPortalReadyEvent` and
  `PlayerPortalEvent` (links above), minecraft.wiki (Nether Portal - delay, gamerules;
  "Java Edition, 23w42a, 1.20.3").
- Gamerules on 26.3: jd.papermc.io `org.bukkit.GameRules`
  (`PLAYERS_NETHER_PORTAL_DEFAULT_DELAY`, `PLAYERS_NETHER_PORTAL_CREATIVE_DELAY`),
  `World#getGameRuleValue(GameRule)`; vanilla defaults 80/1.
- Search metric/radii/creation algorithm: decompiled 1.21.x sources, documented with sources
  in `kz.bejiihiu.dimensions.common.nether` (package docs and the sources of the algorithm
  itself) and in `docs/architecture-transfer.md`.

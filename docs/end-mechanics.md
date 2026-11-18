# The End — cross-server mechanics

What lives where, what the plugin does and — just as important — what it deliberately
does **not** do. Read alongside `docs/architecture-transfer.md` (the orchestration this
plugs into).

Target Minecraft: **Java Edition 1.21.x (Paper/Folia, plugin 26.3 API)**. Anything else is
out of scope.

## Ownership model of The End

**The End backend is a full, native End.** The dragon, its crystals, the battle, the boss
bar, exit-portal activation, the fountain, the end gateway generation, outer islands, the
End cities, shulker boxes and elytra delivery — all of it is vanilla mechanics of one
server and needs no implementation. The plugin must never intercept, rewrite or re-route
any of it.

Cross-server End mechanics are exactly two transitions:

1. **Overworld -> End** (End Portal in a stronghold)
2. **End -> respawn point** (the exit portal after the dragon is defeated)

Everything else on an End backend is out of scope for this plugin.

## Entry portal (Overworld -> End)

Trigger: `PlayerPortalEvent` with `TeleportCause.END_PORTAL` and the world environment
`NORMAL`.

- The event is **always cancelled** (vanilla cannot switch servers); a disabled
  `portals.end.enabled` refuses with a message instead of letting vanilla fall through.
- The End backend resolves the target through `network.dimensions.end`; the target position
  is the **obsidian platform arrival anchor**: `(100.5, 49, 0.5)` facing west (yaw `90`,
  pitch `0`) — Java Edition, 1.16.2+ centers the player on the platform; the platform
  itself sits at `(100, 48, 0)` and is regenerated natively by the End backend whenever a
  new arrival materializes on it.
- No coordinate scaling (End is not Nether — no 8:1).
- No 80-tick delay (End portals are instant, like Creative nether portals).
- The arrival chunk is ticketed for 15 s on the target (the vanilla destination-chunk
  loader window), reacquired per passage.
- `PlayerSetSpawnEvent`-tracked respawn records in The End itself are honoured on the way
  back, never in the way in.

## Exit portal (End -> respawn point)

Trigger: `PlayerPortalEvent` with `TeleportCause.END_PORTAL` and the world environment
`THE_END`.

Resolution order (exactly what the target re-derives from the shared database):

1. **Respawn record found** for the player (written by `RespawnRecorder`, see below):
   - `kind = bed` -> the backend resolved from the record's dimension (in practice the
     overworld family) at the recorded coordinates. The arrival is vetoed if the recorded
     bed is no longer there (see "Missing respawn block").
   - `kind = respawn_anchor` -> the backend resolved from the record's dimension
     (in practice the nether family) at the recorded coordinates; same veto, plus a
     charges > 0 requirement.
   - The record dimension has no backend configured -> treated as "no usable home", see (2).
2. **No record (or an unusable one)** -> the overworld backend, at the world spawn of that
   backend (the vanilla "missing bed / uncharged anchor / unset respawn point" behaviour).
3. Entry is always instant; no cooldown; no dragon-gate beyond vanilla's own (the portal
   only exists/activates natively after the battle, which is the End backend's business,
   not ours).

Routing is by the **recorded dimension**, not by record kind: a bed recorded in
`minecraft:overworld` routes to `network.dimensions.overworld.server`; a respawn anchor
recorded in `minecraft:the_nether` routes to `network.dimensions.nether.server`. A kind is
used only for arrival validation.

## Respawn point tracking

Bed and respawn-anchor interactions fire `com.destroystokyo.paper.event.player.PlayerSetSpawnEvent`
on Paper — the documented choke point for "the player's spawn point was set", with causes
`BED`, `RESPAWN_ANCHOR`, `COMMAND`, `PLUGIN`, `PLAYER_RESPAWN`, `UNKNOWN`. The recorder:

- records **only** `BED` and `RESPAWN_ANCHOR` (a `/spawnpoint` or plugin-set spawn is not a
  bed or anchor and is deliberately not tracked)
- records the world's **dimension resource key** (`minecraft:overworld`,
  `minecraft:the_nether`, `minecraft:the_end`) plus the world's **level-name**
  (`world`, `world_nether`, ...) — both stored; the level-name is how the target backend
  finds the exact world (the dimension alone cannot locate multi-world deployments)
- records coordinates with rotation (yaw = spawn angle, as the event reports it) and the
  kind; `updated_at` is refreshed on every upsert
- **one row per player** (`respawn_points.player_uuid` primary key; upsert)
- a cleared spawn (`getLocation() == null`) deletes the record — vanilla "missing respawn
  point" semantics apply again
- records are kept **only for the three vanilla environments**; a bed in a custom world is
  not tracked (there is no backend route for it to be routed to)
- the write is handed to the async scheduler — interacting with a bed or anchor never
  blocks a region thread on database IO
- validation on use is **lazy**: the record is not deleted when the bed/anchor is broken;
  instead the arrival on the target side validates the block (see below) and falls back to
  world spawn. No delete-listeners are needed and stale records are harmless.

### Why `PlayerSetSpawnEvent` and not `PlayerBedEnterEvent`

`PlayerBedEnterEvent` fires when a player lies down (and before the interaction that
actually sets the spawn), misses respawn anchors entirely, and is cancellable by other
plugins for unrelated reasons. `PlayerSetSpawnEvent` is the only event that fires on the
actual "spawn point set" transition with a cause distinguishing bed from anchor — the
recorded state is therefore exactly the vanilla respawn point the game would use.

### Validation on the target

`BedAnchorValidator` (wired into the arrival) checks the recorded position on the region
thread after join:

- **Bed**: at least one neighbour/recorded block is a bed material (every vanilla bed
  material ends in `_BED`; `org.bukkit.Tag.BEDS` exists but is bound to the server registry
  and cannot be used in unit tests). Handles head/foot reorientation: the recorded block is
  the head, the foot is a horizontal neighbour.
- **Respawn anchor**: a `RESPAWN_ANCHOR` block with `>= 1` charge at the recorded position
  (or a neighbour).
- **Missing or invalid** -> the player is teleported to the world spawn of the target
  backend; the operation still completes (`DimensionTransferCompleteEvent`), with a log
  line. The record is kept — the block might come back.
- **Plain portal-target arrivals** (no respawn record) are never vetoed.

Known deviation: an end-exit with a *dead anchor* resolved to the **nether** falls back to
the **nether world spawn**, whereas vanilla would send the player to the overworld world
spawn. Redirecting from the nether target would need a second proxy hop in the failure
path — declined, documented. The bed case (overworld) matches vanilla exactly.

## What we do NOT implement (and must not)

- **Dragon / battle / boss bar / exit portal activation** — native to the End backend;
  re-summons via crystals likewise.
- **End Gateway** (`end_portal`/`end_gateway` blocks): intra-End only, 40-tick cooldown,
  `ExactTeleport`, landing algorithm — all native. The End gateway is *not* a
  cross-server portal and is not intercepted. (Entities riding a gateway between two ends
  of the same backend is out of scope for the transfer wave; the Nether wave is the place
  to consider entity mechanics.)
- **End cities, shulkers, elytra delivery** — native.
- **The credits/victory condition to unlock the exit portal** — vanilla gates the exit
  portal behind the battle on the End backend; we are not involved.
- **Fall damage immunity on End portal entry** — 1.21.9+ behaviour; native.

## Chunk tickets on arrival

Both entry and exit arrivals call `ChunkTicketService.addTicket` on the arrival chunk —
the vanilla destination-chunk loader window (~15 s) is reproduced with plugin tickets,
refreshed on each transfer. If the add fails, the arrival proceeds normally; tickets are
an optimization, never a gate.

## Respawn-point storage

`<prefix>respawn_points` (migration v2) — one row per player:

| column | type | meaning |
|---|---|---|
| `player_uuid` | `VARCHAR(36)` | primary key |
| `dimension` | `VARCHAR(64)` | resource key of the world the respawn point targets |
| `world` | `VARCHAR(128)` | level-name of that world (`world`, `world_nether`, ...) |
| `x`, `y`, `z` | `DOUBLE PRECISION` | position |
| `yaw`, `pitch` | `DOUBLE PRECISION` | rotation |
| `kind` | `VARCHAR(16)` | `bed` / `respawn_anchor` |
| `updated_at` | `BIGINT` | epoch millis |

Index on `updated_at`. `WORLD_SPAWN` is never stored — it is the absence of a record.
SQL backends get a real predicate-upsert (`UPDATE`, then `INSERT` with a unique-violation
retry as `UPDATE`); Mongo stores the row under `_id = player_uuid` with an `updated_at`
index. The migration runs on `PersistenceFactory.open`, the same as v1.

## Limitations

- Respawn routing requires the **shared database** — all backends must point at the same
  SQL/Mongo store. Without it, every exit behaves as "no record" (world spawn).
- Respawn records are only written for the three vanilla environments (custom dimensions:
  not tracked).
- A bed recorded in the nether is impossible to create by vanilla play (beds explode
  there) but would be stored and honoured if set by other means; the validator is the
  arbiter.
- HuskSync and other sync plugins may move the player's inventory on join; ordering
  relative to `DimensionTransferCompleteEvent` is documented in
  `docs/architecture-transfer.md` and is not guaranteed until the sync wave lands.
- Cross-server entity transport through End portals is a separate wave (the Nether wave's
  orchestration applies; wither/dragon/creaking heart exclusions are part of the vanilla
  parity table there).

## Test matrix

Unit tests cover: respawn store SQL (H2, PostgreSQL mode) + in-memory + Mongo contract
(gated on `TEST_MONGODB_URI`); migration v2 on top of a v1-only database; listener
classification by world environment; bed/anchor validation; arrival claim, plan resolution
(entry platform vs bed vs anchor vs world spawn vs unroutable record), validator fallback,
journal terminal transitions, chunk tickets, complete-event dispatch; watchdog expiry;
portal-refused paths; api-event emission.

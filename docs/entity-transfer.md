# Entity transfer (Phase 5)

How non-player entities cross backends — 1:1 with vanilla behavior as of 1.21.x. The code lives in
`kz.bejiihiu.dimensions.entities` (implementation + `EntityTransferModule` wiring), the persistence
in `kz.bejiihiu.dimensions.common.persistence` (`entity_transfer_payloads` table, migration v3).

Anything not covered here is not guaranteed — including full vanilla parity for entities whose
spawn circumstances differ from players (see Failure matrix).

## One-paragraph model

An entity that touches a portal in another dimension is intercepted by `EntityPortalEvent`
(HIGH, honourable of prior cancellations), serialized into the shared database
(`<prefix>entity_transfer_payloads`), and re-created on the destination backend by a
claim-and-spawn pipeline. Ownership of the assembly passes exactly once: the target "claims"
the payload rows with a compare-and-set, materializes the entities, and drops the rows. The
source retires its own copies only after the rows are gone. Vanilla mechanics — instant pass,
portal cooldowns, passenger order, portal creation, chunk tickets on arrival — are reproduced.
Wither, ender dragon and creaking never transfer, matching vanilla.

## Architecture at a glance

```text
source backend                                        target backend
┌──────────────────────────────┐        ┌──────────────────────────────┐
│ EntityPortalEvent (region)   │        │ poll loop (2 s, async)       │
│   ↓ cooldown / category /    │        │   findPendingOpIds(me)       │
│     exclusion checks         │        │   CAS claimOp (all-or-none)  │
│   ↓ serialize graph          │  rows  │   decode → resolve placement │
│   ↓ payload rows in DB       │ ─────▶ │   spawn pre-order + mounts   │
│   ↓ track assembly           │        │   stamp cooldowns            │
│   ↓ cancel event             │        │   chunk ticket               │
│                              │        │   DELETE rows (the "ack")    │
│ source sweep (2 s)           │        │                              │
│   rows=0/FAILED → retire     │        │ stale-claim watchdog (5 min) │
└──────────────────────────────┘        └──────────────────────────────┘
```

Player-carried assemblies additionally claim on `PlayerJoinEvent` of the riding player (the
"join fast-path"), so a boat carrying a player does not wait for the poll tick.

## What travels

Whitelist serializers, `kind` per entity in `entity_transfer_payloads`:

| kind | entities | preserved | degraded / lost |
|---|---|---|---|
| `item` | dropped `Item` | full stack NBT (Base64 of `ItemStack#serializeAsBytes`), pickup delay, owner, thrower, health, unlimited lifetime, canMobPickup, canPlayerPickup, willAge | — |
| `arrow` | arrow, spectral arrow, tipped arrow, trident | base damage, pierce level, crit, pickup status, lifetime, arrow item blob, firing weapon, tipped-arrow base potion type + custom effects, trident loyalty/glint/dealtDamage, fire ticks | arrow hit sound (deprecated vendor enum), weapon-knockback (function of the weapon now) |
| `boat` | all boats / rafts | type (the entity type key carries wood type and chest-raft-ness), chest contents for chest boats, base flags | `getMaxSpeed`/`occupiedDeceleration`/`workOnLand` (deprecated, broken across versions), `getStatus()` (read-only) |
| `minecart` | minecart, all variants | damage (double, clamped to 40), maxSpeed, slowWhenEmpty, derailed/flying velocity modifiers, display block + offset, all inventories slot-indexed, hopper enabled flag, furnace (powered) minecart **fuel ticks** and push X/Z, command minecart command/successCount/lastOutput, spawner minecart type/delay/ranges/counts | furnace minecart burn progress and cooked-progress (no public API — only fuel ticks survive); trial-spawner spawned item (setter exists, no getter); minecart HP semantics differ across versions |
| `horse` / `donkey` / `mule` / `llama` / `zombie_horse` / `skeleton_horse` | horse family | jump strength, domestication (+max), tamed state, owner UUID (offline owner restores loyalty), age, health, name, effects, armor equipment, saddle, armor (horse), color (horse, llama), style (horse), llama color/strength/decor, chest-carrying flag + chest contents | caravan links (llama caravan), "isEating/isRearing" animation state, horse armor for undead horses (not a vanilla concept) |
| `armour_stand` | armor stands | pose angles (body/head/arms/legs, EulerAngle radians), all equipment (main/off hand + armor), basePlate/visible/arms/small/marker/canMove/canTick flags, disabled slots | none material |
| `mob` | everything else living (zoological, villagers, animals, golems, water mobs, shulkers, armor frames…) | health/maxHealth, age (where Ageable), custom name (MiniMessage), fire/freeze ticks, silent/glowing/invisible/invulnerable flags, full equipment (6 slots), potion effects (type, duration incl. infinite, amplifier, ambient, particles, icon; unknown types skipped with a degraded increment), name visibility | AI flags except aware/persistence (Mob), memories, loot tables, raid state, variant NBT of custom mobs, vex-charging, sniffer animation, enderman carried block, panda genes, villager trade restock, cat/wolf collar variant… |
| `unknown_degraded` | anything outside the whitelist | type key, health (living), base flags, name | everything else — one WARN per operation lists the type(s), and `entity_transfer_degraded_total` counts it |

Non-material losses that are just **not applicable** anywhere, by design: PDC contents of foreign
plugins are never copied (extension isolation); loot tables are never copied; leads
(Leashable relations) are never constructed across servers — a leashed entity is **not**
transferred at all (`entity_transfer_leashed_skipped_total`).

**Costs that are always counted, never silent**: `entity_transfer_degraded_total` (field or entity
lost fidelity), `entity_transfer_failed_total`, `entity_transfer_spawn_failed_total`.

## What is deliberately absent

- **Player passengers and their data** — players travel through the player operation; a vehicle
  arriving on the target has *no player seats filled* and the player re-boards (or the vehicle
  waits for them). Assembly-carrying-player is cooldown-exempt and skips the player in the payload.
  The vehicle and the player operation are independent ops with fresh op ids; the ordering between
  them is not vanilla-guaranteed (the player can land a moment before or after the vehicle), but
  there is no split-brain risk: each carries its own state.
- **Wither / ender dragon / creaking** — never transfer (vanilla rule, checked twice: listener
  entry and graph build, so a passenger of a minecart carrying a wither also refuses the op).
- **`EntitySnapshot`-based transport** — the Paper snapshot is in-memory only and explicitly not
  serializable (`getAsString()` is `@ApiStatus.Experimental` and documented as unreliable); kept out.
- **`EntityTransferSpec` (common wire model)** — not reused. The payload table is the transport;
  the wire piggyback exists only on player connections. The spec's cooldown types remain in common
  (`EntityPortalCooldownType`) but the runtime uses the compat-layer PDC cooldown table instead.

## Anti-duplication state machine

Payload rows are `PENDING → CLAIMED → FAILED` (terminal; no retry, no history). The operation's
rows travel together and are claimed atomically:

1. **Source writes rows** (pre-order, `seq` = passenger-graph DFS order, `parentSeq` in `payload_json`
   links mount parents; depth ≤ 16, node count ≤ 1024). The source entity stays in the world.
2. **Target CAS-claims the whole op** (`UPDATE … SET state='claimed' … WHERE op_id=? AND target_server=? AND state='pending'`);
   all rows flip or none do (inconsistent ops flip only the rows the caller owns to `FAILED`;
   already-claimed rows owned by someone else are never touched).
3. **Target spawns** (see Placement), stamps cooldowns, adds the chunk ticket, then **deletes the rows** — that deletion is the acknowledgement.
4. **Source sweep** (every 2 s, global): rows gone → retire the source assembly; **any FAILED row → retire it too** (no-duplication wins over availability: the target may have spawned a prefix of the graph); otherwise wait.
5. **Source ack timeout** (10 min): `failOp` (rows → `FAILED`) + the source **keeps** its entities (nothing is known to have spawned — no evidence, no deletion).
6. **Stale claims** (5 min, target watchdog): `CLAIMED → FAILED` — the target died mid-arrival; the source retracts on its next sweep.
7. **Partial failure**: a mid-graph spawn exception leaves earlier nodes in the world — nothing is rolled back (rolling back an applied prefix risks a duplicate). Remaining rows stay `CLAIMED → FAILED` at the watchdog; the source deletes its copy and the outcome is **total loss of the remainder**, never a duplicate. This is the documented trade: no-duplication over availability.
8. **FAILED rows** are dead letters: reaper deletes rows older than 7 days.

## Cooldowns (vanilla 1.21.2+ table, wall-clock)

Stored per-entity in PDC (`distributeddimensions:portal_cooldown_at`), checked on portal entry
**source-side** — and stamped on arrival target-side, so a migrated entity's cooldown is visible
on the destination (no PDC copy needed; each backend stamps what it received).

| entity class | cooldown |
|---|---|
| ordinary (mobs, items, armor stands, unknown) | 300 t (15 s) |
| vehicles (minecarts, boats) | 10 t (0.5 s) |
| projectiles (arrows, tridents) | ~2 t (0.1 s) |
| assembly carrying a player (any depth) | **none** |

`entities.cross-server-transfer.players` toggles player-carrier travel; `mobs`, `items`,
`vehicles` gate the other categories (`items` also covers projectiles). Everything else
(Wither/Dragon/Creaking excluded outright) is refused without consulting cooldowns.

## Placement

- **Nether cause**: the hint is the event's own `getTo()` (vanilla already scaled+clamped it) or
  `NetherCoordinateConverter` fallback; y preserved for OW→N, ×8 exact for N→OW. On the target the
  vanilla sequence runs: POI search for `NETHER_PORTAL` (radius 16 destination-nether / 128
  destination-overworld) → nearest by 3D squared distance → inner-rectangle math
  (`PortalSearch`/`LargestRectangleFinder` in common) → `PortalExitCalculator.exitPlacement`
  (90°-turn rules: yaw +90, velocity `(vz, vy, −vx)`, zero-forward for living/boat/minecart).
- **No portal found**: one is created if `entities.cross-server-transfer.portals-create` (vanilla
  1.21+ behavior for any entity except the exclusions); blocks are placed per-chunk in their owning
  regions and the assembly spawns only after all chunks finish. Creation failure falls back to the
  ideal hint coordinates with a degraded counter (`entity_transfer_portal_create_failed_total`).
- **End entry** (any world → The End): the obsidian platform anchor — `(100.5, 49, 0.5)` facing west.
- **End exit** (The End → anywhere): entities have no respawn records; they land at the destination
  world spawn. Yaw/pitch follow the same 90° rules as nether when an exit rect exists.
- Velocity: preserved for nether (rotated if exit axis differs), preserved on end platform / world spawn.
- Passenger order: spawned pre-order, mounted in the same order — passengers ride in the same order they were picked up in.
- Chunk ticket: arrival chunk (and ring, when `entities.cross-server-transfer.chunk-tickets.ring`)
  is ticketed via `ChunkTicketService.addTicketForBlock` — 15 s TTL, refreshed on every pass, ring of 8 when enabled.

## Source flow, step by step

1. `EntityPortalEvent` fires (region thread of the portal). Protection vetoes earlier in the chain win (`ignoreCancelled = true`).
2. Exclusions: portal type not NETHER/ENDER → ignore; mirage world → ignore; custom environment → ignore; wither/dragon/creaking anywhere in the graph → refuse the whole op; leashed → `LEASHED` skip; backend degraded → refuse; unroutable dimension → refuse; category gate off → `CAT_DISABLED` skip; portal cooldown active (non-player assembly) → cancel event, `COOLDOWN_BLOCKED`, entity stays.
3. Intent (`cause`, ideal hint, source rect/axis via a small POI scan, relative position for exit math) is computed; the passenger graph is built pre-order (players dropped with a counter; wither/dragon/creaking refuse the whole op).
4. Root node carries `arrival` (placement inputs); rows are inserted async, then the assembly is tracked (UUID list + TTL 10 min). The event is cancelled — the crossing is consumed.
5. Source sweeps every 2 s (see anti-duplication).

## Failure matrix

| where it fails | detection | payload rows | source entity | assembly on target | counters |
|---|---|---|---|---|---|
| backend degraded at portal entry | `isHealthy()` gate | never written | stays | none | `refused` |
| no route for target dimension | routing lookup | never written | stays | none | `refused` |
| cooldown active | rules check | never written | stays (cancelled) | none | `cooldown_blocked` |
| leashed / wither/dragon/creaking / custom env | entity checks | never written | stays | none | `leashed` / `refused` |
| category gate off (mobs/items/vehicles/players off) | config gate | never written | stays | none | `category_disabled` |
| row insert failure (DB down) | async exception | none written | stays (untracked) | none | `failed` |
| target never claims / target never sees rows | source sweep (rows stay PENDING) | pending | stays until the ack timeout flips them FAILED, then retired | none | `source_retracted` |
| claim invariant broken (partial/inconsistent op) | claim: flipped ≠ total | flipped→FAILED | retracted (no dupe possible) | none | `failed` |
| spawn of a node throws (pre-add) | spawn exception | stay CLAIMED → watchdog FAILED | retracted | **prefix spawned, no duplicates** | `spawn_failed` |
| target crashes mid-claim / mid-spawn | stale-claim watchdog (5 min) | CLAIMED → FAILED | retracted | possibly partial/absent — bounded, no duplicates | `failed` |
| world for target dimension unresolved | environment scan | flipped FAILED | retracted | none | `failed` |
| spawn OK but row drop fails (target DB hiccup) | drop exception | stay CLAIMED | **kept** (never retracted while CLAIMED) | **exists — at risk of becoming an orphan but never duplicated** | `failed` |
| source ack expiry (10 min unclaimed) | source sweep | flipped FAILED (source-side) | **kept** (un-acknowledged is never deleted) | none known | `source_ack_expired` |
| player quits mid-transfer (carrier assembly) | source TTL; the player op fails independently | as above | as above | assembly arrives playerless | as above |

## Configuration

```yaml
entities:
  cross-server-transfer:
    enabled: false        # master switch
    players: true         # assemblies carrying a player
    mobs: false           # living entities, armor stands
    items: false          # dropped items + projectiles
    vehicles: false       # boats, minecarts, horse family
    portals-create: true  # vanilla 1.21+ behavior for entity-created portals
    chunk-tickets:
      enabled: true       # arrival-chunk tickets (15 s TTL, refreshed per pass)
      ring: true          # one-chunk ring, same TTL
```

Own-server identity comes from the environment variable `DD_SOURCE_SERVER` or the JVM system
property `-Ddimensions.source-server=<name>` — the entity module resolves it with
`SourceServerName.resolve()` and therefore does **not** see the `network.source-server`
config key (that key, added in config-version 3, feeds the mirage-guard / programmatic-API
path instead); it is also not the same mechanism the proxy uses (the proxy validates claims
against the requesting `ServerConnection`, never against your env). Payloads are routed by
`target_server`, so this must match the name the source backends wrote; when unset, the poll
is silent and the subsystem inert (portals refuse nothing, entities wait, source retracts on
TTL).

## Metrics

`entity_transfer_started_total`, `entity_transfer_refused_total`, `entity_transfer_completed_total`,
`entity_transfer_failed_total`, `entity_transfer_spawned_total` (nodes), `entity_transfer_degraded_total`,
`entity_transfer_cooldown_blocked_total`, `entity_transfer_leashed_skipped_total`,
`entity_transfer_player_dropped_total`, `entity_transfer_portal_created_total`,
`entity_transfer_portal_find_failed_total`, `entity_transfer_portal_create_failed_total`,
`entity_transfer_source_retracted_total`, `entity_transfer_source_ack_expired_total`,
`entity_transfer_spawn_failed_total`, `entity_transfer_category_disabled_total`. Diagnostic-grade
(atomic counters, exposed via `EntityTransferModule.metricsSnapshot()`), logged sparingly at
SEVERE/WARN for the dangerous paths.

## Testing

- common: contract tests for the payload store (InMemory + H2 PostgreSQL mode via
  `PersistenceFactory`; the Mongo contract is gated on `TEST_MONGODB_URI`, skipped without);
  migration v3 applies on top of a v2-only database; PK `(op_id, seq)` enforced; indexes present.
- paper: proxy-fake entity interfaces (no mock frameworks, consistent with the repo pattern) for
  serializer read/write and the assembly graph (pre-order, player drop/exemption, cycle cut,
  exclusions); codec structural validation; source-sweep decision matrix; the source retraction
  contract against a real in-memory store (`EntityTransferTrackerTest`).
- not unit-tested (Bukkit glue, requires a live server): `world.spawnEntity`, block placement,
  POI scan, chunk tickets. Verified by: `:common:build :paper:build` green.

## Known limitations

- **End exit lands at world spawn** — entity respawn records do not exist; vanilla sends players
  to their respawn point, which is meaningless for items. Computed at the destination; no per-player
  wiring exists for entities and none is invented.
- **Nether exit falls back to the ideal hint** only when no portal exists and creation is disabled —
  creation is on by default, so this is the interplay of two config knobs, not a gap.
- **Furnace minecart burn progress is lost**; only fuel ticks survive. Command minecarts keep
  command/successCount/lastOutput. Spawner minecarts keep type/delay/ranges/counts but not the
  trial-spawner item (no getter).
- **A vehicle carrying a player arrives empty.** Vanilla ties both in one op; we split them.
  Ordering is not vanilla-guaranteed; duplication never happens.
- **Cross-region portal placement** fans out per owning region; the assembly spawns only after
  every placement chunk finishes. If any placement task throws, the assembly spawns at the ideal
  hint and `entity_transfer_portal_create_failed_total` counts it.
- **Mirage worlds are never portals for entities** — they are void stubs; the entity stays.
- **Payloads are bounded by `TEXT` row size (~48 KiB)** on MySQL; the row is refused at the source
  when it exceeds that, never truncated.
- **Live smoke (a real server) has not been run in this phase** — the transfer playground
  (item → backend, minecart → nether) still needs a live-server pass with the nether wave merged.

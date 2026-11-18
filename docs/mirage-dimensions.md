# Mirage dimensions

Distributed Dimensions splits the three dimensions across separate Minecraft servers. Each
server therefore hosts only *its own* real dimension. A **mirage** is a void stub world the
backend plugin registers for the other dimensions, so plugins that probe for them keep working
on every backend.

On startup, for each canonical name (see *Names* below) the plugin creates a world that:

- has the right Bukkit environment (`NORMAL` / `NETHER` / `THE_END`) — so
  `world.getEnvironment()` answers correctly;
- has the right dimension type in its dimension codec (`minecraft:overworld` /
  `minecraft:the_nether` / `minecraft:the_end`);
- contains nothing but air: a void generator produces fully-air chunks and never runs any
  vanilla generation step; the biome everywhere is `minecraft:the_void`;
- never writes anything: `autoSave` is off, no chunk tickets are ever requested for a mirage,
  and no `*.mca` region files are created for it;
- is pinned to noon, clear weather, `spawn_mobs=false`, Peaceful difficulty, and spawn at
  (0, 100, 0) — a dead spot even if something forces a chunk load.

A mirage is created **only if this backend does not already have a real world under that
name** (`Bukkit.getWorld(name) == null`). A real overworld/nether/end on this server is never
shadowed or modified. Mirages are opportunistic: if creation fails, the plugin logs
`SEVERE` and continues — the mirage is simply missing, and this backend behaves like a plain
A/B setup.

## What admins should expect to see

Every backend registers all three worlds. So on an overworld backend you will see all of
`world`, `world_nether`, `world_the_end` in world lists, tab-completion, and `%world_name%`
placeholders. That is the point of mirages — not a bug. The mirage worlds show up empty:

- in `Bukkit.getWorld("world_nether")` and friends: non-null;
- in-game: no players, no mobs, no blocks, nothing to do there;
- on disk: usually no world folder at all (nothing generates, nothing saves);
- region commands (`/execute in minecraft:the_nether run ...`): run in the void — usually
  harmless, occasionally confusing; the vanilla `/execute in` behavior with several worlds of
  one dimension type is version-dependent.

World folders for mirages appear only if something forces chunk generation and/or a world
save happens; with `autoSave=false` and no generation, there is nothing to save, so typically
the folders never appear. If a third-party plugin force-loads a mirage chunk, the chunk is
empty air and costs a few KB of memory until it unloads.

## Guards: teleporting into a mirage

Any teleport into a mirage world is cancelled and turned into a real cross-server transfer
to the backend that hosts that dimension. This covers `player.teleport(...)`, `/tp`,
`/execute in ... run tp`, `Spreadplayers` — everything that goes through the teleport event.

| Attempt | Result |
|---|---|
| `player.teleport(mirageWorld)` | teleport cancelled; player moved to the real backend hosting the dimension; cause `MIRAGE_TELEPORT` |
| respawn with respawn point inside a mirage | player briefly appears in the void, is rerouted within a tick or so |
| entity teleport into a mirage | teleport cancelled; nothing else happens (entity transfers are not shipped yet) |
| `WorldUnloadEvent` for a mirage | cancelled — mirages never unload |
| blocks placed/broken in a mirage | cancelled with a rate-limited hint to the player; `mirage.block-edits` |
| mirage interaction by a non-player entity | block changes cancelled silently |

Coordinates are clamped on transfer: horizontal to ±29 999 984 (the world border extent; the
nether ×8 coordinate scaling is *not* applied — this is a plugin teleport, not a portal), Y to
[-64, 319]. For the End, arrival collapses to the spawn island anchor `(0.5, 66, 0.5)` — a
mirage carries no terrain information, and the outer End is void. Yaw/pitch are preserved.

Cancel semantics are the plain Bukkit kind: `teleport()` returns `false`. Plugins that want
to veto the whole cross-server operation can listen to `DimensionTransferStartEvent` and
cancel it — that is the supported veto point. Cancelling the (already cancelled) teleport
event is meaningless at that stage; cancel the transfer instead.

## Names

Mirage names follow the Bukkit level-name convention:

```
<level-name>             (overworld stub)
<level-name>_nether      (nether stub)
<level-name>_the_end     (end stub)
```

The bundled defaults are `world` / `world_nether` / `world_the_end` — correct for the default
level-name. With a custom `level-name` (e.g. `survival`), the plugin derives
`survival` / `survival_nether` / `survival_the_end` automatically. Override
`mirage.names` only when the worlds were renamed without following the convention:

```yaml
mirage:
  enabled: true
  names:
    overworld: "world"
    nether: "world_nether"
    end: "world_the_end"
  block-edits: cancel    # the only supported value
  fixed-time: 6000
```

`mirage.enabled: false` turns the whole feature off — no mirages are created, no guards are
registered, and this backend behaves like a plain A/B setup.

## What is *not* configured from the plugin

Per-world `paper-world.yml` spawn limits cannot be written by the plugin at runtime: the
file is read when the world loads, and its `_version` must match the server's
`config/paper-world-defaults.yml`. Writing it blindly would risk Paper refusing the file.
The `spawn_mobs=false` gamerule plus the void generator make natural spawning impossible
regardless. If you want belt-and-braces numeric limits, add to each mirage's
`paper-world.yml` (copy `_version` from `config/paper-world-defaults.yml` verbatim):

```yaml
_version: <copy from config/paper-world-defaults.yml>
entities:
  spawning:
    spawn-limits:
      ambient: 0
      axolotls: 0
      creature: 0
      monster: 0
      underground_water_creature: 0
      water_ambient: 0
      water_creature: 0
```

`keep-spawn-loaded` does not exist in Paper 26.3 — always-loaded spawn chunks were removed
from the server, so there is nothing to configure. Mirages do not load spawn chunks.

## Residual risks, honestly

- A plugin that calls `world.getChunkAt(mirage)` gets an instant empty air chunk in memory.
  It costs a few KB and unloads normally. No bytes are written to disk.
- A plugin that insists on building in the mirage gets everything cancelled — the world
  stays void. Operators see `SEVERE`/`WARN` lines only if they enable debug logging.
- `%world_name%` placeholders will list mirages. This is intentional and documented; if a
  scoreboard plugin counts worlds, it counts three per backend.
- `/save-all` does not save mirages (`autoSave=false`).
- `WorldUnloadEvent` on a mirage is always cancelled.

## What is not a mirage

The `DimensionTransferStartEvent` / `CompleteEvent` / `FailedEvent` family is the truth
about transfers. A mirage is just a world object. If your plugin checks
`world.getEnvironment() == NETHER` to decide "are we in the nether", it gets the honest
answer on the Nether backend and a harmless one everywhere else.

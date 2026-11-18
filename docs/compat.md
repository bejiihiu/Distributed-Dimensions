# Third-party plugin integration

How a plugin on a Distributed Dimensions backend can observe — or trigger — cross-server
dimension transfers, without breaking.

This document covers:

- the three compat modes (A, B, D) and what each one dispatches
- event ordering and delivery guarantees
- a listener example and the "never listen to this" list
- dependency ordering and API lookup
- the honest limits (synthetic events on 26.3, no `PlayerChangedWorldEvent`, no NMS)

## Declare the dependency

The backend plugin registers the public api during startup and unregisters it during
shutdown. Third-party plugins must enable **after** it:

```yaml
# paper-plugin.yml
name: MythicScale
main: com.example.mythicscale.MythicScale
load: POSTWORLD
dependencies:
  server:
    Distributed_Dimensions:
      load: BEFORE
      required: false   # true if you hard-depend on the api
```

`DistributedDimensionsAPI.getInstance()` throws `IllegalStateException` until the backend
registers (and again after it unregisters on shutdown). Probe defensively:

```java
try {
    DistributedDimensionsAPI api = DistributedDimensionsAPI.getInstance();
    // use api.transfers() / api.dimensions() / api.portals()
} catch (IllegalStateException absent) {
    getLogger().info("Distributed Dimensions api not present; continuing without it");
}
```

## Compat modes

Configured via `api.compat` in the backend plugin's `config.yml`. Defaults: strict A.

| | A — honest (default) | B — A + synthetic events | D — hardcore (mirages) |
|---|---|---|---|
| Our events (`DimensionTransfer*Event`) | on | on | on |
| Stopped block-edits in mirage | — | — | on |
| Advancement grants (`enter_the_nether`/`enter_the_end`) | on | on | on |
| Synthetic `PlayerPortalEvent`/`PlayerTeleportEvent` | off | **on** | via B |
| Mirage worlds + teleport guard | — | — | **on** |

### Compat A (default)

- Your plugins see our events.
- `minecraft:story/enter_the_nether` (criterion `entered_nether`) and
  `minecraft:story/enter_the_end` (criterion `entered_end`) are awarded idempotently on the
  target backend when the operation cause is `NETHER_PORTAL` / `END_PORTAL`.
- `PlayerTeleportEvent` is **not** synthesized, `PlayerChangedWorldEvent` is **never**
  synthesized, and nothing about vanilla internals is faked. The dimension type in the login
  packet is correct natively — nothing to fake.

### Compat B — opt-in, and what it really is

`api.compat.fire-synthetic-events: true` (default `false`) adds, on the **target** backend,
**after** the transfer lands:

- one `PlayerPortalEvent` (cause `NETHER_PORTAL` or `END_PORTAL` depending on the operation),
- one `PlayerTeleportEvent` (same cause),

with `from == to ==` the arrival point. Listeners that watch the `cause` see a portal
teleport. Listeners that read `getFrom()` see a zero-distance hop — that is unavoidable for a
synthetic event, it is documented on both events, and it is the reason B is off by default.

**The 26.3 caveat, plainly stated.** Every constructor of `PlayerPortalEvent` and
`PlayerTeleportEvent` is annotated `@ApiStatus.Internal` in Paper 26.3. The classes and
constructors are `public`, so the api calls compile and dispatch normally — the events are
inert after the fact (no portal logic runs for them). But Paper does not guarantee those
constructors will keep working across versions, and it does not guarantee the dispatch will
behave identically. Nothing in compat B breaks if a future Paper removes them — the flag can
simply be turned off — but we will not pretend the annotation does not exist. If you rely on
B, you accept that.

Also in B: dispatch is deduplicated per operation id for 5 minutes per JVM, matching the
un-deduped idempotency of the complete event. `EntityPortalEvent` is never synthesized
(entities are out of scope), `PlayerChangedWorldEvent` is never synthesized, and no synthetic
event ever fires for `RESPAWN` / `PROGRAMMATIC` / `MIRAGE_TELEPORT` causes — only the two
portal causes.

### Compat D — hardcore (mirage worlds)

D is A plus mirage worlds and the teleport guard (see `docs/mirage-dimensions.md`). For
third-party plugins D is mostly invisible except that:

- your `teleport()` into a mirage returns `false` and the player ends up on the correct
  backend with cause `MIRAGE_TELEPORT`;
- `WorldUnloadEvent` on a mirage never fires (cancelled);
- block edits in a mirage never apply (cancelled, with a rate-limited hint to the player).

D does not need to be enabled for A/B to work. All three can coexist.

## Event reference and ordering

Events are dispatched synchronously via the Paper plugin manager. On the **source** backend:

```
your call / portal entry / respawn
  → DimensionTransferStartEvent (cancellable — your only veto point; call setCancelled(true))
  → (proxy transfer)
```

On the **target** backend, after the player is ready and the sync plugin has caught up:

```
PlayerJoinEvent → bounded wait on the external sync plugin (sync.wait-timeout)
                → placement (validator/teleport) → two entity-scheduler ticks
                → DimensionTransferCompleteEvent
```

`PlayerJoinEvent` fires before `DimensionTransferCompleteEvent` — always, and the complete
event only fires after the sync wait settles normally. If the sync wait fails, the player
is not moved, a `DimensionTransferFailedEvent` fires instead, and the complete event never
does. If you need the post-transfer world state, wait for `CompleteEvent`.

`DimensionTransferFailedEvent` fires exactly once per operation, on the backend that
terminally recorded the failure:

- **source** — refused before dispatch, proxy/timeout failures;
- **target** — accepted but could not finish.

It can fire while the player is already offline; check `isPlayerOnline()` before touching the
player reference.

A cancelled `DimensionTransferStartEvent` produces **no** failed event — cancellation is not
a failure. Cancelled start → the operation never starts → your `transfer()` call returns a
`TransferResult` with `TransferError.REJECTED`.

## Listening example

```java
public final class MyPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onDimensionTransferComplete(DimensionTransferCompleteEvent event) {
        if (!event.isPlayerTransfer()) {
            return; // entity transfers land in a later phase
        }
        Player player = event.getPlayer();
        if (event.getTo().equals(DimensionId.nether()) && event.getCause() == TransferCause.NETHER_PORTAL) {
            getLogger().info(player.getName() + " arrived in the nether via portal");
        }
    }
}
```

## The never-list

Do **not** rely on any of these — they will never be dispatched by this plugin, in any compat
mode:

- `PlayerChangedWorldEvent` — no honest source world exists, and its constructor is internal.
- `EntityPortalEvent` — never synthesized (only ever cancelled or acted on, never created).
- Any NMS/Mojang-mapped event or class — we do not see them, and we do not fake them.
- `ChunkLoadEvent` — non-cancellable upstream; mirage emptiness does not depend on it.

The authoritative truth is `DimensionTransferStartEvent` / `CompleteEvent` / `FailedEvent`.
Read those and nothing else.

## Cooldowns

`PortalRegistry.isPortalCoolingDown(entity)` reports vanilla-shaped portal cooldowns
(15 s for ordinary entities, 0.5 s for minecarts and boats, 0.1 s for projectiles, and never
for an entity carrying a player passenger). The data lives on the entity itself
(`distributeddimensions:portal_cooldown_at` / `distributeddimensions:portal_cooldown_op`,
persistent data container) — no database is involved, and it is available on every backend.
Stamping is done by the portal waves; today the map is populated by nothing in this wave, so
the answer is almost always `false`.

## Error codes

`TransferError` is `TIMEOUT`, `BACKEND_DOWN`, `UNAVAILABLE`, `REJECTED`, `DISCONNECTED`,
`UNKNOWN` — deliberately coarse. The human-readable detail is in
`TransferResult.message()` / the failed event's context; treat it as diagnostic text, never
parse it.

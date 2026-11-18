# Player-data sync integration

Distributed Dimensions never synchronizes player data itself — inventory, armour,
ender chest, experience, potion effects and so on belong to an external plugin
that the network administrator chooses. What this plugin owns is a single
question: **&ldquo;has the player's data been applied on the target backend
yet?&rdquo;** — answered honestly, with a safe degraded mode when it cannot be.

This document covers: how the integration works, which sync plugins are
supported and how, what to configure on both sides, what the log messages mean,
and how to troubleshoot a transfer that will not proceed.

## Architecture

The transfer flow of the backend plugin reaches the external sync plugin through
one narrow surface (`kz.bejiihiu.dimensions.paper.sync`):

- `SyncOperations.awaitSync(Player)` — the entry point for the arrival flow.
  Returns a `CompletableFuture<SyncReport>` that completes once applied sync
  data is observed (or conservatively assumed). **Never `join()`/`get()` that
  future** — the arrival flow runs on region threads.
- `SyncReport{ strategy, durationMillis, degraded }` — the outcome. `degraded`
  is `true` whenever nothing about the data was actually confirmed; the arrival
  flow decides what to do about it (fail the transfer, or proceed with a warning).
- On failure the future completes exceptionally with `SyncTimeoutException`
  (`reason()` = `WAIT_TIMEOUT` | `PLAYER_OFFLINE` | `INTEGRATION_ERROR`).
  The arrival flow translates that into its terminal failure path
  (`DimensionTransferFailedEvent`). **This branch dispatches no public events.**
- `sync.wait-for-sync: false` (or `sync.enabled: false`) bypasses the integration:
  the future completes immediately with `strategy="disabled"`, `degraded=true`;
  a one-time warning is logged. For tests and trusted networks only.

Every wait is bounded by `sync.wait-timeout` and completes its callback exactly
once. No wait ever blocks a game thread.

### Strategy levels

At boot the plugin probes the environment and binds the strongest available
strategy. Which one is bound is logged at INFO, once, in two lines:

```text
sync: level A (husksync-api) bound — strategy=husksync-api, plugin=HuskSync, mode=auto, waitTimeout=5000 ms, waitTicks=20
Distributed Dimensions: waiting for player sync via husksync-api (HuskSync)
```

| Level | Strategy | Signal | Strength |
| ----- | -------- | ------ | -------- |
| **A** | `HuskSyncCompileIntegration` (`husksync-api`) | HuskSync's public API event `BukkitSyncCompleteEvent` | Event-confirmed |
| **B** | `ReflectionSyncIntegration` (`reflection`) | Any configured `org.bukkit.event.Event` subclass, resolved reflectively | Event-confirmed |
| **C** | `PresenceDelayedIntegration` (`presence-delay`) | The sync plugin gates *login* on applied data; fixed delay after join | Assumed, bounded |
| **D** | `DegradedFallbackIntegration` (`degraded-fallback`) | No signal; fixed delay, `degraded=true`, loud warnings | Unconfirmed by definition |

**A — HuskSync API (compileOnly).** `husksync-bukkit` API jar is a compile-time
dependency only (`net.william278.husksync:husksync-bukkit:4.1.0+26.3` from
`https://repo.william278.net/releases`); nothing of HuskSync is bundled in our
jar. At runtime everything is linkage-guarded: the bridge class that names
HuskSync types is loaded only when the `HuskSync` plugin is installed **and**
its `net.william278.husksync.event.BukkitSyncCompleteEvent` resolves from HuskSync's
own classloader and is a Bukkit event. A "present but API-incompatible" HuskSync
(e.g. an older version without the public events) degrades A to the next tier
— never to a broken state. The listener registers at `MONITOR` priority:
observation only, nothing is cancelled or modified. The event also fires on
admin-forced data restores of online players — harmless, because completions
are only attributed to waits that are actually pending (a completion that
fires *before* `awaitSync` is called is remembered briefly and completes the
imminent wait immediately instead of hanging until the timeout).

**B — Reflection listener (configurable).** For any plugin that fires a Bukkit
event when its sync completes, without compiling against it. Configured via
`sync.reflection.event-class` (fully qualified class name) and
`sync.reflection.getter` (no-arg method returning the player). The class is
located across all installed plugins' classloaders, validated
(`instanceof Event`), and registered at `MONITOR` through the server's
`PluginManager.registerEvent(...)` with a generic `EventExecutor` bridge —
no generics, everything `Throwable`-guarded, registration failures degrade to
the next tier. The getter accepts a `Player` (uses `getUniqueId()`), a `UUID`
(used directly) or any object exposing `getUuid()` (e.g. HuskSync's
`getUser()`).

**C — Presence implies ready (blocking-login plugins).** MC Data Bridge and
InvSYNC expose no completion event. Their design gates the *login itself* on
the data being applied (fencing locks / ownership claim) — a player that is
in the world has applied data, or was refused entry. Distributed Dimensions
does not pretend this is a confirmation: it arms a fixed delay
(`sync.wait-ticks`, capped at `sync.wait-timeout`) after join, then completes.
Waits report `SyncCause.FIXED_DELAY` and are **not** degraded (a known sync
plugin was detected) but are also never as strong as A/B.

**D — Degraded fallback (no usable sync plugin).** No lock, no event: the
player might see an outdated (never lost, the data is safely stored on the
source) inventory. Distributed Dimensions allows the transfer but flags it:
`SyncReport.degraded=true`, `SyncCause.DEGRADED`, plus a loud startup warning
naming the fix. If the wait cannot proceed (player left), the strategy flips
to `FAILED` with a SEVERE log — never a silent dead-end.

### Strategy selection (`sync.plugin`)

| Value | Behaviour |
| ----- | --------- |
| `auto` (default) | A if the HuskSync API is available, else B if `sync.reflection.event-class` is set, else C if a known sync plugin is installed, else D. |
| `husksync` | Pin A. If unavailable → **loud failure** (transfers fail after the timeout; `SEVERE` log with instructions), never a silent downgrade. |
| `reflection` | Pin B. Requires `sync.reflection.event-class` to be set; unconfigured/unbindable → loud failure. |
| `delayed` | Pin C (fixed delay). Downgraded to D with a warning if no known sync plugin is detected. |
| `none` | D directly — loud degraded mode, e.g. networks without a sync plugin. |

Known plugins for auto-detection (case-insensitive name match):
`HuskSync`, `MC Data Bridge`, `MCDataBridge`, `InvSYNC`, `InventorySync`,
`DataSynchronization`, `SyncInv`.

`auto` mode also downgrades stepwise when a binding fails mid-flight (broken
jar, racing load): reflection if configured, then C, then D. Pinned modes fail
loudly instead.

## What to configure

### Our side (`config.yml`)

```yaml
sync:
  enabled: true            # false: integration bypassed, transfers proceed unconfirmed
  wait-for-sync: true      # false: same bypass, one-time warning

  # auto | husksync | reflection | delayed | none
  plugin: "auto"

  # every wait, event-driven or delay-based, is bounded by this
  wait-timeout: 5000       # ms; keep below network.transfer.timeout
  wait-ticks: 20           # fixed delay for C/D; capped at wait-timeout

  reflection:               # only for sync.plugin=reflection (or auto-fallback to B)
    event-class: ""        # e.g. net.william278.husksync.event.BukkitSyncCompleteEvent
    getter: "getPlayer"    # Player | UUID | object with getUuid() (e.g. getUser for HuskSync)
```

These keys were added by config schema v2 (v1 files migrate automatically).
Validation: `wait-timeout` must be > 0, `wait-ticks` >= 0, `reflection.event-class`
must be a fully qualified class name when non-empty, `reflection.getter` a simple
method name.

### HuskSync side (what the administrator must do)

HuskSync (v4.x) is installed on **all three backends** and pointed at the same
database (and Redis, per its own docs). Nothing in Distributed Dimensions
replaces that; the following is required **for correctness of the arrival
point**:

1. **Disable location sync** — HuskSync can sync the player's coordinates
   between servers. If enabled, an applied snapshot can overwrite the arrival
   coordinates *after* the transfer positioned the player. In the HuskSync
   `config.yml`:

   ```yaml
   synchronization:
     features:
       location: false
   ```

   (This is off by default in HuskSync v4; it is listed here because turning
   it on silently breaks cross-server arrival positioning. The data-snapshot
   type is `husksync:location`. The docs describe the feature as intended for
   mirrored worlds where players should land at identical coordinates.)

2. **Do not rely on join order.** HuskSync applies snapshots asynchronously;
   `PlayerJoinEvent` at any priority (including `MONITOR`) does **not**
   guarantee applied data, and our listener is deliberately `MONITOR` on
   `BukkitSyncCompleteEvent` — the only documented
   "data is applied" signal.
3. Admin-forced restores/rollbacks of online players also fire
   `BukkitSyncCompleteEvent`. They do not corrupt a transfer: only pending
   waits complete.
4. This applies to HuskSync's supported platforms. HuskSync officially
   targets Paper (and Fabric); it is used on Folia unofficially
   (paper issue #556) — verify on your build before relying on it there.
5. Keep `husksync:location` off even for "same-shaped" worlds. Our arrival
   flow positions the player itself; the sync plugin must not second-guess it.

### MC Data Bridge / InvSYNC side

No API or events exist to wait on — by design. What Distributed Dimensions
does: `presence-delay` (level C). What the administrator must guarantee:

- MC Data Bridge: the target backend must be reachable and its MySQL
  consistent; a lock held longer than `lock-timeout` (default 60 s) kicks the
  player with `Data still syncing...` — that player never reaches the world,
  and our pending wait ends with `SyncTimeoutException(PLAYER_OFFLINE)`.
- InvSYNC: every server runs the same Minecraft version; the ownership claim
  happens before the player enters the world. Kick-on-timeout denies the join
  in the same way.

### DataSynchronization / commercial plugins

`DataSynchronization` declares a public API
(`de.squarecodefx:datasynchronization-api` from `https://repo.tmp0.cc/releases`)
and Bukkit events, but the repository has not resolved as of 2026-11-18, and
the API surface (event class names) is unverifiable — **do not configure the
reflection strategy against invented class names**. After the repository is
reachable again and the event class is verified, reflection mode can be wired
by config alone (no code changes). Everything else without a public API
(SyncInv, EasySync, PlayerDataSync, InventorySync, SolarSync, …) selects
level C when detected by name, else D.

## Degraded mode, exactly

When nothing usable is found (level D, or `sync.plugin=none`), the plugin:

- logs **once at enable**, at WARN, with the fix:

  ```text
  sync: DEGRADED — auto: no sync plugin detected. Transfers will proceed after a fixed 20-tick delay WITHOUT confirmation that the player's data was applied. Install HuskSync (https://william278.net/project/husksync) on every backend, or set sync.plugin=reflection with sync.reflection.event-class. See docs/player-sync.md.
  ```

- completes waits after `sync.wait-ticks` with `degraded=true` reports;
- completes with `SyncTimeoutException(PLAYER_OFFLINE)` and flips the strategy
  to `FAILED` (SEVERE log) if the player leaves mid-wait;
- never silently proceeds as if sync had been confirmed.

If a pinned integration cannot be armed (`sync.plugin=husksync` with HuskSync
absent, or `sync.plugin=reflection` with no/broken class), the plugin arms a
loud fallback (`logging-fallback`) instead of quietly degrading: a SEVERE at
enable with the fix, a WARN per timed-out wait, and transfers **fail** after
`sync.wait-timeout` — because the administrator asked for exactly one safe
including gate and it is missing.

The always-printed discovery lines at enable (INFO):

```text
sync: level D (degraded-fallback) bound — strategy=degraded-fallback, plugin=none, mode=auto, waitTimeout=5000 ms, waitTicks=20
Distributed Dimensions: waiting for player sync via degraded-fallback (none)
```

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Transfer fails with `SyncTimeoutException(WAIT_TIMEOUT)` ~wait-timeout after every arrival | Sync plugin installed but not applying, or completion event never fires | Check the sync plugin's own log; database connectivity; try `wait-timeout: 10000`. For HuskSync verify ≥ 3.2 (public events). |
| Player is kicked by the sync plugin mid-transfer (`Data still syncing...`) | MC Data Bridge / InvSYNC fencing still holds the lock | Investigate its lock/DB; the failed transfer leaves the player on the source (safe). Retry after the source server releases. |
| Player arrives at the wrong coordinates, or appears at the previous server's location | `synchronization.features.location: true` in HuskSync | Set it to `false` on every backend; see "HuskSync side" above. |
| `SyncTimeoutException(PLAYER_OFFLINE)` immediately | Player disconnected during the wait | Expected. The transfer fails cleanly; retrying the action starts a fresh operation. |
| Arrival position overridden by respawn point / spawn | Unrelated to data sync — see the arrival-flow documentation | Arrival positioning is computed by this plugin; check backend logs for the arrival branch. |
| `level D (degraded-fallback) bound` + `plugin=none` | No sync plugin detected on this backend | Install HuskSync on every backend, or configure `sync.plugin=reflection`/`delayed`. |
| `husksync-api` never bound, `HuskSync not installed` logged | paper-plugin.yml loads our plugin without HuskSync present, or the wrong HuskSync jar | Install HuskSync on all backends; restart; look for the `level A` line. |
| `husksync-api` armed but every wait times out | HuskSync's apply never completes (Redis/DB down) | Fix HuskSync connectivity; it applies asynchronously and our wait honestly times out. |
| Both `BukkitSyncCompleteEvent` and our teleport happen; player warped back to old spot | Location sync enabled (see above) | Disable `synchronization.features.location`. |
| Log shows `HuskSync API incompatible` | HuskSync version without the public events (< 3.2) | Upgrade HuskSync. |
| Strategy flips to `FAILED` | A player-side wait failed in degraded/fallback mode | See the SEVERE line; fix the sync plugin, then restart. |

Folia: our scheduler calls are region-safe (global region scheduler for the
fixed delay, entity-free). HuskSync itself is Paper-only officially; treat
any HuskSync issue on Folia as HuskSync's, not ours.

## Verification log

Facts verified on 2026-11-18:

- `husksync-bukkit:4.1.0+26.3` resolves and downloads from
  `https://repo.william278.net/releases` (HTTP 200); `4.2.0+1.21.10`
  **does not exist**; latest artifacts are `4.1.0+26.3` / `4.1.0+1.21.10/11` /
  `4.1.0+26.1.2/26.2/26.3`.
- API surface inspected in the artifact:
  `BukkitSyncCompleteEvent extends BukkitPlayerEvent implements SyncCompleteEvent`,
  non-cancellable, `getUser() → OnlineUser → getUuid()`, static
  `getHandlerList()`; `BukkitDataSaveEvent` / `BukkitPreSyncEvent` exist
  (latter cancellable). HuskSync's `plugin.yml`/`paper-plugin.yml` both name
  the plugin `HuskSync`; Paper loads the paper-plugin.yml variant.
- `paper-plugin.yml` soft-dependency declaration syntax (`dependencies.server`
  with `required: false`, `join-classpath: true`) confirmed against the
  PaperMC documentation; there is **no** `soft-depend`/`depend` key in
  paper-plugin.yml.
- `PluginManager#registerEvent` (the `SimplePluginManager` path from the
  research notes) is deprecated for removal in Paper 26.3; the interface now
  carries identical `registerEvent` signatures and is used instead.
- HuskSync location sync toggle: `synchronization.features.location`
  (docs "Sync Features"; off by default).
- DataSynchronization declared API repository (`repo.tmp0.cc`) does not
  resolve (DNS) — unverifiable; not wired.

## Contract for the arrival flow (orchestration branches)

```java
// somewhere on the arrival path, never from a pre-login/configuration context
SyncServices services = SyncServices.get();           // throws ISE when disabled/failed
services.coordinator().awaitSync(player)
        .thenAccept(report -> {
            if (report.degraded()) {
                // log/report; decide whether to proceed or abort
            } else {
                // data applied — position the player, complete the transfer
            }
        })
        .exceptionally(ex -> {
            // ex is SyncTimeoutException (PLAYER_OFFLINE | WAIT_TIMEOUT | INTEGRATION_ERROR)
            // -> terminal failure path (DimensionTransferFailedEvent); the player stays put
            return null;
        });
// NEVER: future.join() / future.get() / thenApplyAsync(...).join()
```

Guarantees this branch provides: the future completes at most once, always
within `sync.wait-timeout`; normal completion means a `SyncReport` whose
`degraded` flag honestly reflects confirmation; exceptional completion means
nothing was applied (or the player is gone); `shutdown()` fails pending waits
with `INTEGRATION_ERROR` instead of hanging them. This branch emits **no**
public events and never teleports anyone — that is the arrival flow's job.

# Limitations — the honest matrix

This document exists so nobody has to reverse-engineer what "supported" means by watching a
transfer fail at 2 a.m. Everything here is the truth as of this build, verified against the
source, not the aspiration. If a claim elsewhere sounds stronger than this file, this file
wins.

How to read it:

- **Support levels** — the four compatibility levels (1→4) from the project's support policy
  and where this build actually stands.
- **What works today** — the short, blunt list.
- **What each compat mode does and does not provide** — A vs B vs D, no glossing.
- **Known deviations from vanilla** — where ours intentionally differs.
- **Performance characteristics** — the numbers that matter operationally.
- **Security posture** — what is enforced, what is not.
- **Untested in this environment** — explicit gaps, including the ones that matter.

## Support levels — where this build stands

The support policy names four levels. Plain-English meaning, and the current position:

| Level | Meaning | Current position |
|---|---|---|
| **1 — Player-compatible** | Players move between backends; portals work; coordinates and player state carry over | **In code, never run live.** End portals work cross-server (entry + exit, wired at boot). The Nether portal flow (vanilla search/creation/arrival, wired at boot) ships too. Player coordinates/state travel via the external sync plugin — and since the sync-await fix the arrival flow **awaits it** (`SyncOperations.awaitSync`, bounded by `sync.wait-timeout`) before moving the player or firing the complete event (see below). |
| **2 — World-mechanics-compatible** | Portals, search, creation, End, Gateways, Dragon Fight per the targeted Java Edition | **Mostly, in code.** The End backend runs native mechanics (dragon, crystals, exit portal, gateways, outer islands) with zero interception — that half is a real End. Nether portals ship cross-server: vanilla 16/128 search, vanilla creation with forced 2x3 placement, dwell gamerules honoured natively, arrival with portal search/creation on the destination. Unit-tested; never exercised live in this environment. |
| **3 — Entity-compatible** | Mobs, items, vehicles, passengers cross backends | **Shipped — opt-in, experimental, disabled by default.** `entities.cross-server-transfer.enabled` defaults `false`. DB-mediated (payload rows in the shared store; no proxy channel): target claims by CAS, spawns, deletes rows (the ack); source retires only when rows are gone/FAILED. Anti-duplication is the invariant — worst case is loss of the remainder, never a duplicate. Never smoke-tested on a live network yet. Excluded forever by design: wither, ender dragon, heart-bound creaking. |
| **4 — Vanilla parity** | All supported mechanics verified against a single server of the same version | **Not claimed, not achievable yet.** Calling this build Level 4 would be false advertising. |

**The shape that is real today:** three backends + proxy; both portal families are
functional as code and covered by unit tests; entity transfer is code-complete behind an
opt-in flag; the journal, proxy validation, arrival flow, mirage worlds, sync integration
(bound and awaited), admin surfaces (`/dd`, `/ddproxy`) and the public API all exist.
**Nothing has been proven against a live multi-JVM deployment of this stack — see
"Untested in this environment".**

## What is fully supported in this build

Treat this list as the maximum, and read the caveats under each:

- **Cross-server End portals (entry)** — `PlayerPortalEvent` cancel → journal → proxy →
  arrival on the End backend at the obsidian-platform anchor `(100.5, 49, 0.5)` facing west.
  Unit-tested; never validated live in this environment.
- **Cross-server Nether portals** — `PlayerPortalEvent` cancel → journal → proxy → arrival
  on the Nether/overworld backend, where the vanilla destination sequence runs: POI search
  (radius 16 / 128) → nearest by 3D squared distance → vanilla creation if nothing found →
  exit placement. Vanilla dwell gamerules honoured natively. Unit-tested; never validated
  live in this environment.
- **Cross-server End exit portal** — resolves the recorded respawn point from the **shared
  database** (bed → overworld family, anchor → nether family), re-validates it on arrival,
  falls back to world spawn. Requires the shared DB on all three backends.
- **Respawn-point recording** — bed/anchor interactions (`PlayerSetSpawnEvent`, causes
  `BED`/`RESPAWN_ANCHOR` only; `/spawnpoint` and plugin-set spawns are deliberately not
  tracked), one row per player, lazy validation, cleared spawn deletes the record.
- **The transfer journal + proxy validation** — CAS state machine, exactly one terminal
  event per operation, watchdogs at 2× `network.transfer.timeout`, proxy-side idempotency by
  `request_id` (~10 min replay cache, 512 in-flight / 1024 completed bounds).
- **Entity transfer — opt-in, experimental, disabled by default** —
  `entities.cross-server-transfer.enabled` defaults `false`. Shipped as a DB-mediated
  pipeline (payload rows `<prefix>entity_transfer_payloads`, migration v3); the proxy is
  not involved. Not smoke-tested live. The safe default remains: no entity crosses unless
  you turn this on and accept that risk knowingly.
- **Mirage worlds** — void stub worlds for foreign dimensions (correct environment/dimension
  codec, `autoSave=false`, inert, unload-cancelled, edit-blocked). They exist so probes
  don't crash; they are not content.
- **Public API + compat A/D** — events, services, advancement criteria granting, mirage guard
  routing (`MIRAGE_TELEPORT`). Honest edges: `EntityTransferEvent` is declared in the api but
  never dispatched in this build; `transfer(Entity, …)` returns `REJECTED` by design.
- **Admin surfaces** — `/dd` on backends (`info` / `reload` / `mirage`) and `/ddproxy` on
  the proxy (`status` / `reload` / `transfers`), one permission node
  (`distributeddimensions.admin`, op default).
- **Chunk tickets on arrival** — reproduces the vanilla ~15 s destination-chunk window with
  plugin tickets (refresh on touch; ring-of-8 optional). An optimization, never a gate.

Everything else is either partial, experimental, or absent.

## Compat modes — what each does and does NOT provide

Full contract: `docs/compat.md`. The anti-hype version:

| You want | Mode A (default) | Mode B (opt-in) | Mode D (mirage) |
|---|---|---|---|
| Our transfer events | yes | yes | yes |
| Advancement criteria (`entered_nether`/`entered_end`) | idempotent grant on target | same | same |
| `PlayerPortalEvent` / `PlayerTeleportEvent` | **not fired** | fired, synthetic, `from == to`, zero distance, cause preserved | included |
| `PlayerChangedWorldEvent` | **never** | **never** | **never** |
| `EntityPortalEvent` | **never** | **never** | **never** |
| `ClientboundSetDimensionType` spoofing | no | no | no |
| `player.teleport(netherWorld)` travels cross-server | no (world doesn't exist) | no | yes (guard cancels + routes as `MIRAGE_TELEPORT`) |
| NMS `DimensionTransition` | no | no | no — does not exist, never will |
| Correct dimension type in the login packet | yes (native — each backend serves its own dimension) | yes | yes |

**Compat B specifics, stated plainly.** Every constructor of `PlayerPortalEvent` and
`PlayerTeleportEvent` is annotated `@ApiStatus.Internal` in Paper 26.3. The classes and
constructors are public, so the dispatch compiles and runs — and Paper guarantees neither
that those constructors keep working nor that the dispatch behaves identically across
versions. B additionally deduplicates per operation id (5 min per JVM), fires only for
`NETHER_PORTAL`/`END_PORTAL` causes, and never for `RESPAWN`/`PROGRAMMATIC`/`MIRAGE_TELEPORT`.
If you rely on B, you accept that a future Paper may remove the constructors; the flag can
simply be turned off. B is therefore **shipped behind `api.compat.fire-synthetic-events:
false` and should be treated as a compat shim, not a contract.**

**Mode D** is not an events mode — it is void worlds + guards. `WorldUnloadEvent` on a mirage
never fires (cancelled); block edits in a mirage never apply (cancelled, rate-limited hint);
a third-party `teleport()` into a mirage returns `false` and the player re-routes to the real
backend with cause `MIRAGE_TELEPORT`. Entity teleports into a mirage are cancelled and go
nowhere (mirage stubs are never portals for entities). `%world_name%`-style placeholders will
list three worlds per backend — that is the point, not a bug.

## Known deviations from vanilla (deliberate, documented)

- **Dead respawn anchor in the Nether falls back to the *nether* world spawn**, where vanilla
  would send the player to the overworld world spawn. Redirecting would need a second proxy
  hop in the failure path; declined. The bed case (overworld) matches vanilla exactly.
- **Respawn points are recorded only for the three vanilla environments.** A bed in custom
  dimensions is invisible to the exit portal — there is no backend route for it.
- **`/spawnpoint` and plugin-set spawns are not tracked.** Only `BED` and `RESPAWN_ANCHOR`
  causes via `PlayerSetSpawnEvent` are.
- **Custom dimensions:** no respawn records, no exit-portal routing; a bed there is silently
  untracked by design.
- **Portal cooldowns are tracked, with a boundary.** The PDC-backed cooldown
  (`distributeddimensions:portal_cooldown_at` / `portal_cooldown_op`) is stamped for players
  on the nether arrival and for entities on their arrival, and the source-side portal
  listener suppresses re-entry mid-cooldown; players riding a vehicle are exempt (assembly
  semantics). The stamp is per-server entity PDC: it is exact for the re-entry case it exists
  for (the player lands inside the arrival portal on the same backend), but it does not
  survive crossing to another backend (a fresh player entity elsewhere has its own PDC) —
  full cross-backend cooldown parity would need the cooldown in shared state, not PDC.
- **Remote border approximation.** World borders are server-local configuration. Nothing in
  this build replicates a destination backend's configured border across the proxy: portal
  search/creation math (`common.nether`) takes its `WorldBorderBounds` at the call site, the
  mirage clamp pins ±29,999,984, and the platform passes the *local* border at runtime. A
  network with a heavily shrunk or asymmetric custom border on one backend can push
  portal-search/creation decisions slightly off single-server vanilla behaviour. Vanilla
  default borders match. Full border policy: see "World state — the actual policy" below.
- **`WorldUnloadEvent` on mirages is always cancelled.** Deliberate.
- **Anchor requires ≥ 1 charge; bed requires a bed-shaped block** (head or paired foot
  neighbour). Otherwise: world spawn, operation still completes, log line written, record kept (the
  block may come back later).
- **Chunk tickets are best-effort.** If the add fails, arrival proceeds normally — the
  ticket is an optimization, never an integrity requirement.
- **One End backend per network.** Mirrored Ends are unsupported by design; each backend's
  dimension codec is correct natively and there is exactly one routing slot per dimension.

## World state — the actual policy (time, weather, gamerules, border)

- **Time and weather are per-backend and never synchronized.** There is no cross-backend
  day/night or weather policy in this plugin — not a hidden one, not a partial one. Each
  backend behaves like an isolated vanilla server with its own clock and weather; expecting
  one "network-wide sunrise" is a category error.
- **Gamerules are per-world and independent.** The plugin never changes gamerules on real
  worlds — including the daylight/weather cycle rules. The only gamerules this plugin ever
  sets are on mirage stubs: `advance_time` / `advance_weather` pinned `false`, `spawn_mobs`
  pinned `false` (plus a pinned noon and permanent clear weather). The Nether portal dwell
  gamerules (`playersNetherPortalDefaultDelay`, default 80;
  `playersNetherPortalCreativeDelay`, default 1) are **read live from the source world** per
  completion for diagnostics, and the dwell itself is vanilla's; the plugin never writes them.
- **World border: the source-side conversion clamps with the vanilla DEFAULT bounds**
  (overworld `±29_999_985`, nether `±3_749_998.125`) because the destination backend's
  configured border is unreadable before the switch. **The arrival-time search and creation
  on the destination backend clamp with that backend's REAL border** (`world.getWorldBorder()`).
  A network with heavily shrunk or asymmetric custom borders on one backend can push
  portal-search/creation decisions slightly off single-server vanilla behaviour; default
  borders match. Keep the three backends' borders coordinated yourself — the plugin clamps
  to them but does not manage, replicate or validate them.
- **Mirage worlds pin noon and clear weather, and disable the daylight/weather cycle and
  mob spawning — on the mirage stubs only.** Real worlds are never touched.
- **Dragon fight, end gateways, outer islands and the dragon egg are out of scope by
  design** — native to the End backend, zero interception, no plugin code, no plugin tests.
  Compatibility verification for those mechanics is left to the admin test loop
  (`docs/installation.md` checklist), exactly as you would verify any vanilla End server.
- **Config keys with no consumer in this build.** `general.strict-vanilla-mode`,
  `portals.nether.preserve-vanilla-delay` / `-search` / `-creation` / `-orientation`, and
  `network.transfer.max-retries` are parsed and documented but nothing branches on them
  (the vanilla gamerules are the supported lever for the dwell; the search/creation **is**
  the vanilla algorithm). `portals.gateway.enabled`, `network.proxy.enabled` and the four
  `debug.*` flags are likewise inert. A separate cleanup branch is handling this under
  review — until it lands, treat all of these as having no effect. See
  `docs/configuration.md`.

## Player-data sync — awaited, and honest about what that proves (read this twice)

The external sync integration is code-complete, bound at boot, and **awaited by the arrival
flow**: after `PlayerJoinEvent` the target resolves `SyncServices` and calls
`SyncOperations.awaitSync(player)` (never joined); the teleport/verify, the chunk ticket,
the journal `COMMITTED` transition and `DimensionTransferCompleteEvent` all happen only
after the wait completes normally. See the arrival order in `docs/architecture-transfer.md`
and the contract in `docs/player-sync.md`. On timeout or error the player is **not moved**:
the journal row goes `FAILED` with the reason, a SEVERE line logs player uuid + reason, and
`DimensionTransferFailedEvent` fires exactly once (with `playerOnline=false` if the player
already left). If the sync services are entirely missing (plugin disabled or boot failed),
the arrival proceeds in degraded mode with a warning — never a crash.

Practical consequences:

- Ordering guarantees elsewhere (`PlayerJoinEvent` → *sync* → `CompleteEvent`) are enforced
  by this build: the complete event never fires before the sync wait settles normally.
- A degraded "player sees a stale inventory" risk remains on networks without a working
  sync plugin (level D is honest about knowing nothing), and on networks where HuskSync
  silently fails to apply. Install HuskSync (recommended) or accept the risk knowingly.
- A sync timeout fails the transfer loudly; the player keeps the provisional spawn
  placement and the operation can be retried cleanly.
- `sync.wait-for-sync: false` disables the wait (a one-time warning is logged, transfers
  proceed unconfirmed) — trusted networks and tests only.
- HuskSync must have **location sync disabled** (`synchronization.features.location: false`)
  on every backend, or the applied snapshot can overwrite our arrival coordinates after
  placement. This is off by default in HuskSync v4; turning it on silently breaks arrival
  positioning. See `docs/player-sync.md` and `docs/installation.md`.
- HuskSync is officially Paper/Fabric-only; on Folia it is used unofficially (paper issue
  #556 territory). Verify on your Folia build.
- MC Data Bridge / InvSYNC class plugins are supported only at "presence-implies-ready"
  strength (fixed delay, never confirmed). Level D exists to be honest about "we know nothing".

## Entity-only transfers — the empty-backend truth

Bukkit only lets a backend talk to the proxy **on a player's connection**. An entity that
travels without a player has **no carrier at all**: the source cannot emit a
`entity_transfer` message (there is no connection to ride), and even if it could, the proxy
has no way to push the acknowledgement back to a backend with zero players online
(`RegisteredServer.sendPluginMessage` returns `false` when nobody is connected there).

That is why entity transfers are **DB-mediated, not proxy-routed**. This is shipped code,
not a sketch — and it is **opt-in, experimental, and disabled by default**:
`entities.cross-server-transfer.enabled: false` until you set it to `true`. What the build
does when you enable it:

- The source backend watches `EntityPortalEvent` (HIGH, honouring prior cancellations) and,
  on a portal crossing, serializes the passenger assembly (whitelist serializers; depth ≤ 16,
  nodes ≤ 1024) into payload rows — `<prefix>entity_transfer_payloads`, PK `(op_id, seq)`,
  `PENDING → CLAIMED → FAILED`, migration v3.
- The target backend polls the shared store every ~2 s, CAS-claims **all** rows of an
  operation (all-or-none), spawns the assembly pre-order with vanilla portal cooldowns
  stamped (300 t ordinary / 10 t vehicles / ~2 t projectiles; never for a player-carrying
  assembly), adds the arrival chunk ticket — and **deletes the rows; that deletion is the
  acknowledgement**.
- The source sweeps every ~2 s: rows gone → retire the assembly; any row `FAILED` → retire it
  too (no-duplication wins over delivery — the target may have spawned a prefix); expired
  (10 min) → flip rows to FAILED and keep the entities. A stale-claim watchdog (5 min) and a
  dead-letter reaper (rows older than 7 days) bound the rest.
- Excluded forever by design: wither, ender dragon, heart-bound creaking (checked twice —
  listener entry and graph build). Leashed entities never transfer. Player passengers never
  travel in the entity payload (they go through the player operation); an arriving vehicle
  has no seats filled.
- `transfer(Entity, …)` on the public API still fails fast with `TransferError.REJECTED` —
  the pipeline is portal-driven, not API-driven. `EntityTransferEvent` (api) is declared but
  **never dispatched** in this build.
- **Worst case is total loss of the remainder, never a duplicate** (partial spawn failure
  leaves the prefix in the world and the remainder permanently lost). Anti-duplication is
  the invariant; availability is not.
- The proxy never routes entities: an `entity_transfer` message at the proxy gets an
  immediate `entity_transfer_ack{outcome="failed", error="rejected"}` fail-fast. The shipped
  pipeline never sends one.

Honest limits: the whole path has **never been exercised on a live server** (no live smoke —
item → backend, minecart → nether playground are the untested proofs); it is covered by unit
tests (serializers, assembly graph, sweep decision matrix, payload-store contract tests on
H2-PostgreSQL-mode). Mirage worlds are never entity portals. End exit lands at the
destination world spawn (entities have no respawn records). Payloads are bounded by the
`TEXT` row size (~48 KiB on MySQL; refused, never truncated). If you enable this on a
production network before a live smoke has happened, you are the test.

## Journal & proxy edge cases (accepted, by design)

- **Proxy restart mid-transfer** erases idempotency state. A backend retrying after a restart
  re-executes the operation; Velocity's own `ALREADY_CONNECTED` / `CONNECTION_IN_PROGRESS`
  statuses make that usually a no-op `ok: true`. Avoid restarting the proxy during transfer
  waves; backends reconcile via the shared DB.
- **A result can be lost entirely** when the source backend is empty of other players: the
  piggyback fallback needs at least one other player online on the source backend. If both
  result paths fail, the source learns nothing and reconciles by watchdog (2× timeout).
- **Offline player at failure time** — `DimensionTransferFailedEvent` may fire with
  `isPlayerOnline() == false`; the entity reference can throw or return stale data. The one
  designed gap: an offline player can cost the target its failed-event dispatch; never the
  journal's terminal state.
- **Migrations v1–v3 run on open** (`PersistenceFactory`); a backend that cannot migrate is
  fail-closed forever until manually fixed. (v3: `<prefix>entity_transfer_payloads`.)
- **`CONNECTION_IN_PROGRESS` is reported as `ok: true`** — an in-flight connection for the
  same player is treated as success-ish; if it lands on a *different* server than the target,
  the player will not arrive where claimed. Unavoidable without veto APIs, documented in
  `docs/velocity.md`.
- **The `TransferPending` map (source, 1024 entries, 2-minute TTL)** can evict legitimate
  entries on a pathologically busy backend; the journal still reaches a terminal state.
- **Mirage chunks force-loaded by a third party** cost a few KB of memory each and unload
  normally; they never write to disk and never enter the portal-ticket system.

## Performance characteristics (measured shapes, not marketing)

- **Arrival is a two-step**: `AsyncPlayerSpawnLocationEvent` (async claim + placement
  decision; registry-only world lookups — `Bukkit.getWorld`/`getRespawnWorld()` — never
  block or entity access) → `PlayerJoinEvent` (entity thread: sync wait,
  verify/teleport) → **two entity-scheduler ticks** → journal commit + `CompleteEvent`.
  Expect the complete event after the sync wait settles (normally ≤ `sync.wait-timeout`,
  default 5000 ms) plus the placement window.
- **Sync waits are bounded by `sync.wait-timeout` (default 5000 ms)** and never block a
  game thread; nothing ever calls `join()` on them.
- **Transfer path**: journal warm-pool insert sub-millisecond; watchdog at 2× the configured
  timeout (default 10000 ms → 20000 ms); claim watchdog on the target at the same 2×.
- **Mirage memory**: one air-filled void chunk ≈ a few KB in memory, no region files ever.
  A scoreboard counting three worlds per backend is intended behaviour.
- **Proxy footprint**: in-memory only; 512 pending + 1024 cached outcomes; counters
  (`requests-received`, `validation-failures`, `completed-replays`, …) via `/ddproxy status`.
- **Chunk tickets** are plugin tickets with a reproduced 15 s TTL refreshed on each touch;
  ring-of-8 loaded when `entities.cross-server-transfer.chunk-tickets.ring: true`.
- **Portal search/creation math** is allocation-disciplined by tests but not load-tested;
  large spiral searches on a cold chunk cost what they cost — kept off the critical path by
  the vanilla portal-delay behaviour (nether) and instant arrival (End).

## Security posture

What is enforced today, in one screen:

- **Channel source verification (proxy).** Only `PluginMessageEvent` sources that are
  `ServerConnection` are backends; player-sourced or unknown-source messages are dropped
  silently and counted (`dropped-player-sourced`, `dropped-unknown-source`). A client cannot
  reach the transfer path at all.
- **Claim↔connection binding.** `player_uuid` must belong to the player on *the very
  connection* the message arrived on; the claimed `source_server` must equal that
  connection's registered server; the claimed `target_dimension` must be configured with
  the claimed `target_server`; the configured server must be registered in `velocity.toml`.
  A misconfigured/malicious backend cannot smuggle another player through its connection.
- **`request_id` idempotency + replay protection.** Pending and completed maps are bounded
  and TTL'd (~10 min); identical replays are swallowed or answered from cache; the same id
  with different coordinates is `REJECTED`; every message is `handled()` so it never
  propagates as an unknown packet.
- **Piggyback results** correlate by `request_id` only — foreign carrier connections are
  fine because the id is the capability.
- **Anti-duplication holds whoever loses.** All terminal transitions are CAS; exactly one
  terminal event fires per operation regardless of retry/interleave shapes.
- **Secrets**: `${VAR}` expansion at load only, masked `toString()`, URL credentials redacted
  from logs; `config-version` newer than supported = hard failure.
- **What is NOT enforced**: the payload is not cryptographically authenticated — the trust
  perimeter is "velocity.toml-registered backends on a network that enforces modern
  forwarding". Anyone who can connect to a backend as a *player* cannot redirect anyone;
  anyone who controls a registered backend can claim anything about itself (the proxy
  cross-checks only what it can see). **Bind backends to a private network and firewall them.**
- The 32,766-byte plugin-message cap and `v = 1` are enforced at the codec boundary before
  any handler sees the message.

## Untested in this environment — explicit list

These are true gaps, not formalities. No item below has been run against a live deployment
of this stack in the development environment:

1. **A full live network** (Velocity 4.2 + 3 Paper 26.3 backends + a real database): no
   end-to-end run has been executed and no player has ever transferred. The stack *has*
   been booted live in this environment (proxy + three Paper 26.3 backends on one machine):
   the proxy enabled its channel, the backends booted with persistence fail-closed (no DB
   present), mirage stubs materialized in-memory with foreign stems disabled, `sync` bound
   level D, `/dd` registered, ports answered pings. No logins, no transfers, no portal
   crossings, no HuskSync — none of that was exercised. Unit-level evidence stays: fakes
   (`FakeSchedulers`, `FakeTransferBackend`, test fixtures), codec/journal tests.
2. **Live HuskSync integration** — the A-level bridge is API-surface-verified against the
   `husksync-bukkit:4.1.0+26.3` artifact and unit-tested against fakes; **never** bound to a
   real HuskSync instance on a real server (level D degraded-fallback is what bound live in
   the boot smoke). Folia + HuskSync is doubly unverified.
3. **MongoDB behind the plugin** — contract tests exist and are correct in shape, but they
   are skipped unless `TEST_MONGODB_URI` is set; no CI or dev run has exercised them. All
   green SQL-side evidence is **H2 in PostgreSQL mode** — MySQL 8, MariaDB, and real
   PostgreSQL wire behaviour (index DDL, collation, upsert retry semantics) are untested here.
   Migrations are validated on H2 only.
4. **Folia runtime** — the scheduler facade is unit-tested via fakes; no live Folia server
   run is documented. Region-boundary edge behaviour (entity retirement mid-task, portal
   block on the exact region border) is unverified on real Folia.
5. **Nether portal wave** — the full cross-server Nether flow (search/creation/arrival,
   gamerule honouring, portal index) is implemented and unit-tested, but has never been
   exercised live; nothing about it is "supported" in practice until a live crossing happens.
6. **Mirage worlds** — mirage creation at boot has been **verified on a live Paper 26.3**
   in this environment (void stubs materialized in-memory with the foreign stems disabled;
   on a fresh default boot all three dimensions load, so no stubs are created). Guard
   interception, teleport-into-mirage routing, and `WorldUnloadEvent` cancellation are
   unit-tested only — never exercised on a live server.
7. **Entity transfers** — code-complete and unit/contract-tested (H2 PostgreSQL mode), but
   never exercised on a live network: no item → backend crossing, no minecart → nether run,
   no live poll/claim cycle. Treat as unproven.
8. **Dragon fight, end gateways, outer islands** — out of scope by design (native
   mechanics of the End backend; no plugin code and no plugin tests). Compatibility
   verification for them is left to the admin test loop (`docs/installation.md` checklist) —
   not something this build ships as tested.
9. **`/ddproxy` in a human's hands** — `/ddproxy` registered at boot on a live Velocity
   in the smoke run, but the commands have never been typed by a human into a real proxy
   console (logic is unit-tested).
10. **Version pinning** — `api-version: '26.3'` and `paper-api: 26.3.build.+` pin the family;
    older Paper/Folia/Velocity combos are untested and mostly unsupported.
11. **26.3-specific API behaviour cited in compat docs** (e.g. `@Internal` portal constructors
    actually dispatching) is verified by compilation and unit tests, not by a live server.
12. **Degraded-mode end-to-end** (kill the database mid-run, watch the backends refuse,
    restart clean) — designed and unit-tested; never rehearsed live. (The boot smoke *did*
    observe the persistence fail-closed path engage live — backends refused transfers with
    `SEVERE` lines and stayed enabled; nothing was killed mid-run.)

If you deploy this, you are the first production run. Plan accordingly: stage it, back it
up, and keep the previous jar.

## The anti-hype summary

- This is a pre-release build. Player transfers work in code paths with strong unit tests,
  and nowhere else.
- The shared database is mandatory. Without it the End exit portal is a world-spawn delivery
  mechanism and the journal — the exactly-once arbiter — does not exist.
- Player-data sync is outsourced, and the arrival flow awaits it (bounded by
  `sync.wait-timeout`) before moving or completing the transfer — but only the sync
  plugin can prove what actually landed.
- Entity transfers are opt-in (disabled by default), never exercised live, and worst-case
  lossy — never duplicative. Nether cross-server portals exist in code, never exercised
  live. Vanilla parity is a direction, not a claim.
- If someone tells you this build is "vanilla parity at level 4", they have not read this
  file.

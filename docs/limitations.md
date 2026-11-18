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
| **1 — Player-compatible** | Players move between backends; portals work; coordinates and player state carry over | **Partially.** End portals work cross-server in code (entry + exit, wired at boot). The Nether portal flow (search/creation/arrival) is **not wired** — the algorithms exist in `common` and are unit-tested, but no portal listener calls them. Player coordinates/state travel via the external sync plugin — and since this build the arrival flow **awaits it** (`SyncOperations.awaitSync`, bounded by `sync.wait-timeout`) before moving the player or firing the complete event (see below). |
| **2 — World-mechanics-compatible** | Portals, search, creation, End, Gateways, Dragon Fight per the targeted Java Edition | **Partially, End only.** The End backend runs native mechanics (dragon, crystals, exit portal, gateways, outer islands) with zero interception — that half is a real End. Cross-server Nether portals and end gateways landing cross-server are out. |
| **3 — Entity-compatible** | Mobs, items, vehicles, passengers cross backends | **Not shipped.** Wire protocol + journal states exist; the proxy rejects `entity_transfer`; no backend orchestrator exists. `entities.cross-server-transfer.enabled` defaults `false` and nothing in this build consumes it. Excluded forever by design: wither, ender dragon, heart-bound creaking. |
| **4 — Vanilla parity** | All supported mechanics verified against a single server of the same version | **Not claimed, not achievable yet.** Calling this build Level 4 would be false advertising. |

**The shape that is real today:** three backends + proxy; the End portal
(Overworld → End and End → respawn point) is functional as code and covered by unit tests;
the journal, proxy validation, arrival flow, mirage worlds, sync integration (bound and
awaited) and the public API all exist. **Nothing has been proven against a live multi-JVM
deployment of this stack — see "Untested in this environment".**

## What is fully supported in this build

Treat this list as the maximum, and read the caveats under each:

- **Cross-server End portals (entry)** — `PlayerPortalEvent` cancel → journal → proxy →
  arrival on the End backend at the obsidian-platform anchor `(100.5, 49, 0.5)` facing west.
  Unit-tested; never validated live in this environment.
- **Cross-server End exit portal** — resolves the recorded respawn point from the **shared
  database** (bed → overworld family, anchor → nether family), re-validates it on arrival,
  falls back to world spawn. Requires the shared DB on all three backends.
- **Respawn-point recording** — bed/anchor interactions (`PlayerSetSpawnEvent`, causes
  `BED`/`RESPAWN_ANCHOR` only; `/spawnpoint` and plugin-set spawns are deliberately not
  tracked), one row per player, lazy validation, cleared spawn deletes the record.
- **The transfer journal + proxy validation** — CAS state machine, exactly one terminal
  event per operation, watchdogs at 2× `network.transfer.timeout`, proxy-side idempotency by
  `request_id` (~10 min replay cache, 512 in-flight / 1024 completed bounds).
- **Mirage worlds** — void stub worlds for foreign dimensions (correct environment/dimension
  codec, `autoSave=false`, inert, unload-cancelled, edit-blocked). They exist so probes
  don't crash; they are not content.
- **Public API + compat A/D** — events, services, advancement criteria granting, mirage guard
  routing (`MIRAGE_TELEPORT`).
- **Chunk tickets on arrival** — reproduces the vanilla ~15 s destination-chunk window with
  plugin tickets (refresh on touch; ring-of-8 optional). An optimization, never a gate.

Everything else is either partial, planned, or absent.

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
nowhere (entity transfers are not shipped). `%world_name%`-style placeholders will list three
worlds per backend — that is the point, not a bug.

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
- **Post-exit portal cooldown is not tracked.** The PDC-backed cooldown mechanism exists
  (`distributeddimensions:portal_cooldown_at` / `portal_cooldown_op`) but **nothing in this
  build stamps it**, so `PortalRegistry.isPortalCoolingDown(entity)` almost always answers
  `false`, and nothing prevents an immediate re-trigger beyond tick order and the fact that
  the arrival lands the player at their respawn location rather than inside the portal. The
  Nether wave is what will stamp it; until then do not build UX on it.
- **Remote border approximation.** World borders are server-local configuration. Nothing in
  this build replicates a destination backend's configured border across the proxy: portal
  search/creation math (`common.nether`) takes its `WorldBorderBounds` at the call site, the
  mirage clamp pins ±29,999,984, and the platform passes the *local* border at runtime. A
  network with a heavily shrunk or asymmetric custom border on one backend can push
  portal-search/creation decisions slightly off single-server vanilla behaviour. Vanilla
  default borders match.
- **`WorldUnloadEvent` on mirages is always cancelled.** Deliberate.
- **Anchor requires ≥ 1 charge; bed requires a bed-shaped block** (head or paired foot
  neighbour). Otherwise: world spawn, operation still completes, log line written, record kept (the
  block may come back later).
- **Chunk tickets are best-effort.** If the add fails, arrival proceeds normally — the
  ticket is an optimization, never an integrity requirement.
- **One End backend per network.** Mirrored Ends are unsupported by design; each backend's
  dimension codec is correct natively and there is exactly one routing slot per dimension.

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

That is why entity transfers need an assumed or out-of-band confirmation path — and why the
planned design is a **poll, not a push**. The Phase 5 design has the source backend sweep its
tracked entity operations on a fixed cadence (~every 2 s / 40 ticks), directly against the
shared store: for each tracked operation id, **zero rows left** means the target claimed and
materialized and acknowledged → the source removes its copy; **any row FAILED** → the source
keeps its entities and logs SEVERE (no-dupe priority over delivery); **TTL expired without
ack** → the source CASes the row to FAILED and keeps the entities with a warning. The poll
exists precisely because the acknowledgement cannot physically ride an empty backend's
connections. None of this is implemented in this build:

- `entity_transfer` at the proxy → immediate
  `entity_transfer_ack{outcome="failed", error="rejected"}` — the source fails fast instead
  of hanging on a response that physically cannot arrive.
- The backend never even attempts the request: there is no entity orchestrator, no poll, no
  sweep — and `transfer(Entity, …)` on the API fails fast with `TransferError.REJECTED`.
- Excluded forever by design: wither, ender dragon, heart-bound creaking. These entities
  never cross backends; entity cooldown rules (300/10/2 ticks, none with a player passenger)
  exist only in design and in `PortalRegistry`'s report.
- Don't build on Phase 5 until it ships. The safe assumption is **no entity ever changes
  backends silently** — because it doesn't happen at all.

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
- **Migration v2 runs on open** (`PersistenceFactory`); a backend that cannot migrate is
  fail-closed forever until manually fixed.
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
   end-to-end run has been executed. All transfer-path testing is unit-level with fakes
   (`FakeSchedulers`, `FakeTransferBackend`, test fixtures), plus codec/journal tests.
2. **Live HuskSync integration** — the A-level bridge is API-surface-verified against the
   `husksync-bukkit:4.1.0+26.3` artifact and unit-tested against fakes; **never** bound to a
   real HuskSync instance on a real server. Folia + HuskSync is doubly unverified.
3. **MongoDB behind the plugin** — contract tests exist and are correct in shape, but they
   are skipped unless `TEST_MONGODB_URI` is set; no CI or dev run has exercised them. All
   green SQL-side evidence is **H2 in PostgreSQL mode** — MySQL 8, MariaDB, and real
   PostgreSQL wire behaviour (index DDL, collation, upsert retry semantics) are untested here.
   Migration v2 is validated on H2 only.
4. **Folia runtime** — the scheduler facade is unit-tested via fakes; no live Folia server
   run is documented. Region-boundary edge behaviour (entity retirement mid-task, portal
   block on the exact region border) is unverified on real Folia.
5. **Nether portal wave** — the math (conversion, search, creation) is unit-tested, but the
   cross-server Nether flow has **no runtime path at all**; nothing about it is "supported"
   in practice.
6. **Mirage worlds on a live Paper 26.3** — mirage creation, guard interception, and
   `WorldUnloadEvent` cancellation are unit-tested; never run on a live server.
7. **`/ddproxy` against a live proxy** — command logic is unit-tested; never typed by a
   human into a real Velocity console.
8. **Version pinning** — `api-version: '26.3'` and `paper-api: 26.3.build.+` pin the family;
   older Paper/Folia/Velocity combos are untested and mostly unsupported.
9. **26.3-specific API behaviour cited in compat docs** (e.g. `@Internal` portal constructors
   actually dispatching) is verified by compilation and unit tests, not by a live server.
10. **Degraded-mode end-to-end** (kill the database mid-run, watch the backends refuse,
    restart clean) — designed and unit-tested; never rehearsed live.

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
- Entity transfers don't exist. Nether cross-server portals don't exist. Vanilla parity is a
  direction, not a claim.
- If someone tells you this build is "vanilla parity at level 4", they have not read this
  file.

# Distributed Dimensions

Orchestrates the three Minecraft dimensions across three independent Paper/Folia servers behind a Velocity proxy — vanilla portals, vanilla End, one world, three JVMs.

[![CI](https://github.com/bejiihiu/Distributed-Dimensions/actions/workflows/build.yml/badge.svg)](https://github.com/bejiihiu/Distributed-Dimensions/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/release-v1.0.0-blue)](https://github.com/bejiihiu/Distributed-Dimensions/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
[![Paper 26.3+](https://img.shields.io/badge/Paper-26.3+-dfb317)](https://papermc.io/downloads/paper)
[![Folia 26.3+](https://img.shields.io/badge/Folia-26.3+-3ba676)](https://papermc.io/downloads/folia)
[![Velocity 4.2+](https://img.shields.io/badge/Velocity-4.2+-1b9aaa)](https://papermc.io/downloads/velocity)

## Status

Pre-release (`v1.0.0`). The transfer code paths are covered by unit tests and
this build has never been run as a full live, multi-server network — no
end-to-end production run has happened yet. Deploy on a staging network first
and keep a backup of `plugins/` and the database. See [docs/limitations.md](docs/limitations.md)
for the exact, unvarnished list of what works and what does not.

## What is this?

Minecraft ties each dimension to a single server process, so a network that
wants an isolated, independently restartable Nether or End has no honest way to
stretch one server across the boundary. Distributed Dimensions splits the
Overworld, the Nether and The End onto three independent Paper/Folia backends
behind a single Velocity proxy. To the player it behaves like one Minecraft
world: portals work the vanilla way, coordinates and mechanics carry over, and
the split is invisible except where it cannot honestly be.

Three components make up the network:

- **The backend plugin** (`distributed-dimensions-paper`) — the game side: portal
  detection, portal search and creation, the End, entity transfer, mirage worlds,
  the public API. Runs on each of the three backends.
- **The proxy companion** (`distributed-dimensions-velocity`) — the switch side:
  request validation, idempotent connection routing, `/ddproxy` admin surface.
  Holds no world state and no durable state.
- **The split is yours to choose** — three separate machines, VLANs or ports, sized
  and restarted independently. The plugin does not care where a backend lives as
  long as it can reach the shared database and the proxy.

Two more pieces complete the picture: player data (inventory, XP, effects, ...)
is synchronized by a **separate external plugin** — never by this one — and a
**shared database** holds the transfer journal and respawn points, which is what
makes exactly-once delivery possible.

## Features

- **Vanilla End portals, cross-server** — Overworld → End (obsidian platform
  arrival anchor) and End → recorded respawn point (bed/anchor, re-validated on
  arrival with a world-spawn fallback). Beds and respawn anchors are recorded
  via the vanilla spawn-point choke point; the dragon fight, crystals, gateways
  and outer islands stay fully native to the End backend.
- **Vanilla Nether portals, cross-server** — 1:8 coordinate scaling (Y untouched),
  the vanilla search algorithm (radius 16 for the Nether, 128 for the Overworld,
  full world height, Y-aware 3D squared distance), the vanilla creation fallback
  (spiral search, forced 2x3 placement as the last resort), vanilla dwell
  gamerules honoured natively, and arrival chunk tickets reproducing the 15-second
  vanilla destination-chunk window. An internal portal index keeps the search
  fast on repeat crossings.
- **Cross-server entity transfer (opt-in)** — off by default; enable per category.
  Dropped items and projectiles, minecarts (all variants), boats and chest rafts,
  the horse family, armor stands and living mobs cross backends 1:1 where the
  vanilla API allows it, with vanilla portal cooldowns honoured (300 ticks for
  ordinary entities, 10 for minecarts/boats, ~2 for projectiles; never for an
  assembly carrying a player). Wither, ender dragon and creaking never transfer.
  Anti-duplication is the invariant: a source entity is retired only after the
  target confirms materialization. Not yet smoke-tested on a live server —
  verify on staging before enabling it in production.
- **Public plugin API** — `distributed-dimensions-api`, versioned independently
  (`1.0.0`), `compileOnly` for third-party plugins. Events
  (`DimensionTransferStart/Complete/FailedEvent`), a `TransferManager` for
  programmatic transfers, `DimensionRegistry` and a cooldown-aware `PortalRegistry`.
- **Compat modes for third-party plugins** — mode A (default): our events plus
  idempotent vanilla advancement criteria. Mode B (opt-in): synthetic
  `PlayerPortalEvent`/`PlayerTeleportEvent`. Mode D: void "mirage" worlds plus
  teleport/block-edit guards. `PlayerChangedWorldEvent` and `EntityPortalEvent`
  are never fired in any mode.
- **Mirage dimensions** — the world-exists illusion: each backend registers void
  stub worlds for the *other* dimensions, so third-party plugins that probe for
  a foreign dimension's world keep working. A teleport into a mirage is routed
  to the real backend as a transfer.
- **Folia-native design** — one scheduler facade (entity/region/global/async),
  zero raw Bukkit scheduling outside it, chunk-ticket arrival loading with
  refresh-on-touch.
- **Fail-closed behaviour** — a backend that cannot reach its database or load a
  valid config refuses every transfer loudly instead of half-opening. Exactly one
  terminal event fires per operation, guaranteed by a compare-and-set journal.
- **Admin surface** — `/dd` on backends (`info` / `reload` / `mirage`) and
  `/ddproxy` on the proxy (`status` / `reload` / `transfers`), one permission node
  (`distributeddimensions.admin`). Admin messages in English and Russian.
- **Exactly-once semantics** — a shared-database transfer journal with CAS state
  transitions and proxy-side idempotency by request id, so retries and replays
  never duplicate an operation.

## Requirements

- **Java 25** on the proxy and every backend.
- **Paper or Folia 26.3+** for the backends — one server per dimension.
- **Velocity 4.2+** for the proxy.
- **A shared database — required.** MySQL 8+ / MariaDB / PostgreSQL, or MongoDB.
  All three backends must point at the same store; without it the End exit portal
  degrades to world-spawn delivery and the exactly-once journal does not exist,
  so the backends boot fail-closed and refuse transfers.
- **An external player-data sync plugin — required for correctness.** HuskSync is
  the tested, recommended path (officially Paper-only; used unofficially on Folia —
  verify on your build). Without any sync plugin, player data is never moved
  between backends.
- **Redis** is *not* used by this plugin. HuskSync requires its own Redis 5+
  instance — that is HuskSync's requirement, not ours.
- **Velocity modern forwarding** with a shared forwarding secret, and a private
  network for the backends — only the proxy's port should be reachable.
  See [docs/installation.md](docs/installation.md).

## Installation

Download the artifacts from [GitHub Releases](https://github.com/bejiihiu/Distributed-Dimensions/releases):

- `distributed-dimensions-paper-<version>.jar` — into `plugins/` on **every backend**
- `distributed-dimensions-velocity-<version>.jar` — into `plugins/` on **the proxy**

Releases also ship the `distributed-dimensions-api` artifact (for plugin
developers, `compileOnly`) and a `checksums.txt` with SHA-256 digests.

The full zero-to-hero network setup — shared database, `paper-global.yml`
velocity keys, the forwarding secret, the proxy and backend `config.yml`
walkthrough, HuskSync setup and a verification checklist — lives in
[docs/installation.md](docs/installation.md). Walk through it end to end; the
network is only as correct as its database and its sync plugin.

Build from source: see [CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation

| Document | What it covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | the big picture: modules, data flow, design constraints |
| [docs/architecture-transfer.md](docs/architecture-transfer.md) | the transfer/arrival contract, failure matrix, event ordering |
| [docs/installation.md](docs/installation.md) | zero-to-hero network setup and verification checklist |
| [docs/configuration.md](docs/configuration.md) | every `config.yml` key, load semantics, env-var indirection |
| [docs/limitations.md](docs/limitations.md) | the honest matrix: what works, what doesn't, what is untested |
| [docs/api.md](docs/api.md) | the public API: artifacts, events, compat matrix |
| [docs/commands.md](docs/commands.md) | `/dd` and `/ddproxy` admin commands |
| [docs/velocity.md](docs/velocity.md) | the proxy companion: validation, idempotency, `/ddproxy` |
| [docs/folia.md](docs/folia.md) | backend foundation: boot, scheduler facade, chunk tickets |
| [docs/player-sync.md](docs/player-sync.md) | external sync integration (HuskSync), troubleshooting |
| [docs/entity-transfer.md](docs/entity-transfer.md) | cross-server entity transfer: coverage, failure matrix |
| [docs/nether-portals.md](docs/nether-portals.md) | vanilla Nether portals, cross-server |
| [docs/end-mechanics.md](docs/end-mechanics.md) | End entry/exit, respawn-point recording & validation |
| [docs/mirage-dimensions.md](docs/mirage-dimensions.md) | mirage (void) worlds: creation, guards, risks |
| [docs/compat.md](docs/compat.md) | compat modes A/B/D in detail, ordering, never-list |

## Compatibility

Distributed Dimensions targets Java Edition 1.21.x via the Paper/Folia 26.3 API
and Velocity 4.2+; older combinations are untested and unsupported. This is a
pre-release build: no "vanilla parity" level is claimed, several subsystems are
awaiting a live smoke test, and entity transfer is opt-in. The honest
compatibility matrix — including what is deliberately absent — is
[docs/limitations.md](docs/limitations.md). If a claim elsewhere sounds stronger
than that file, that file wins.

## Contributing

Contributions are welcome. Start with [CONTRIBUTING.md](CONTRIBUTING.md) for the
build setup and coding rules, then open an issue or a pull request. Bug reports
and feature requests go in [issues](https://github.com/bejiihiu/Distributed-Dimensions/issues);
design questions and general discussion belong in
[Discussions](https://github.com/bejiihiu/Distributed-Dimensions/discussions).
Please read the [Code of Conduct](docs/CODE_OF_CONDUCT.md) before participating.
Release notes ship with each GitHub Release.

## Credits

Module layout inspired by [bejiihiu/multiloader-template](https://github.com/bejiihiu/multiloader-template).

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 bejiihiu.xs.

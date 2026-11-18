# Configuration reference

Both platforms (Paper/Folia backend, Velocity proxy) read the same YAML schema from
`config.yml`. The Paper module passes its data directory, the Velocity module passes
`@DataDirectory`; either way the loader receives a `java.nio.file.Path` and works the same.
The same bundled defaults ship in `common/src/main/resources/config.yml`.

Status legend used below: **active** = read by code that exists today; **consumed-by-design** =
the key is a frozen contract for a feature landing in a later phase — removing or renaming it
is a breaking schema change. Every key in the shipped defaults is either one or the other;
nothing is decorative.

## Boot semantics

1. Missing file → the bundled defaults are written to disk (byte-for-byte copy, atomic move,
   parent directories created). Existing files are never overwritten by this step.
2. File parsed → `config-version` checked → migrations applied sequentially (`vN → vN+1`,
   saved back atomically, only after the whole chain succeeds).
3. Missing keys → healed **in memory** from the bundled defaults (node-level merge).
   The file on disk is not rewritten unless a migration ran.
4. Unknown keys → collected into the load report as dotted paths (`portals.nether.bogus`)
   and logged as warnings. Never an error.
5. Broken file (unparsable YAML, wrong container types, missing required values, failed
   env expansion) → the last-good snapshot keeps serving; if nothing was loaded before,
   the bundled defaults serve instead. The cause is exposed via `isHealthy()` / `health()`
   and reaches the platform logger. If even the bundled defaults cannot be materialized
   (e.g. `${DB_PASSWORD}` is unset and the file is broken), boot fails with a
   `ConfigurationException` naming the problem.
6. `config-version` newer than the plugin supports → hard failure with a clear message
   (upgrade the plugin). A non-integer `config-version` is likewise a clear failure.

The file is UTF-8 without BOM. Comments are preserved on migration save (the bundled
defaults ship with comments and they survive a migration rewrite).

## Environment-variable indirection

In the `database` connection strings (`database.jdbc-url`, `database.mongo-uri`,
`database.username`, `database.password`) values may reference `${VAR_NAME}`. References
are expanded **at load time** against the process environment. An unset or blank variable
is a hard failure naming the variable and the config key — never its value. Plain `$`
outside `${...}` is passed through. References in the branch unused for the configured
engine type are left literal (a mongodb deployment does not need `DB_PASSWORD` unset to
satisfy the mysql branch).

Secrets are never printed: `DatabaseSettings.toString()` renders the password as `****`
and strips `user:pass@` credentials embedded in URLs. Exception messages name variables,
not values. Everything else in this file is treated as non-secret.

Local dev: `password: ""` means "no credentials" (local trust-auth MySQL). Real
deployments should use `password: "${DB_PASSWORD}"` (the shipped default).

## `config-version` and schema evolution

Integer key, top level. `2` is the current schema (v1 → v2 added the `sync.*`
integration keys). A file without the key is treated as
`0` and brought forward by the migration framework. Migrations are class-based
(`ConfigMigration`, one per version step), applied strictly in order; a registered gap in
the chain is a hard error. After a successful migration the file is saved back atomically
with `config-version` stamped. Never edit `config-version` by hand.

## Key reference

| Key | Type | Default | Platform | Purpose / status |
| --- | --- | --- | --- | --- |
| `general.language` | string (`en`/`ru`) | `en` | both | Message language for user-facing plugin text. Consumed-by-design. |
| `general.strict-vanilla-mode` | boolean | `true` | both | Restrict the plugin to assisting the vanilla flow. Consumed-by-design. |
| `network.proxy.enabled` | boolean | `true` | both | Proxy-mode participation. Consumed-by-design (proxy routing). |
| `network.transfer.timeout` | int (milliseconds) | `10000` | both | Transfer attempt timeout; > 0. Consumed-by-design. |
| `network.transfer.max-retries` | int | `2` | both | Transfer retries before giving up; ≥ 0. Consumed-by-design. |
| `network.dimensions.overworld.server` | string | `overworld` | both | Proxy server holding the overworld dimension. Grammar `[A-Za-z0-9_-]{1,64}`. Consumed-by-design. |
| `network.dimensions.overworld.dimension` | string | `minecraft:overworld` | both | Dimension id on that server. Namespaced id. Consumed-by-design. |
| `network.dimensions.nether.server` | string | `nether` | both | Same for the nether. |
| `network.dimensions.nether.dimension` | string | `minecraft:the_nether` | both | |
| `network.dimensions.end.server` | string | `end` | both | Same for the end. |
| `network.dimensions.end.dimension` | string | `minecraft:the_end` | both | |
| `portals.nether.enabled` | boolean | `true` | paper | Nether portal handling. Consumed-by-design. |
| `portals.nether.preserve-vanilla-delay` | boolean | `true` | paper | Keep the vanilla entry delay. |
| `portals.nether.preserve-vanilla-search` | boolean | `true` | paper | Keep the vanilla portal-search behavior. |
| `portals.nether.preserve-vanilla-creation` | boolean | `true` | paper | Keep vanilla portal creation in the target world. |
| `portals.nether.preserve-vanilla-orientation` | boolean | `true` | paper | Keep axis orientation of created portals. |
| `portals.end.enabled` | boolean | `true` | paper | End portal handling. Consumed-by-design. |
| `portals.gateway.enabled` | boolean | `true` | paper | End gateway handling (outer islands). Consumed-by-design. |
| `entities.cross-server-transfer.enabled` | boolean | `false` | paper | Master switch for entity transfer. Consumed-by-design. |
| `entities.cross-server-transfer.players` | boolean | `true` | paper | Players may travel cross-server. |
| `entities.cross-server-transfer.mobs` | boolean | `false` | paper | Mobs may travel cross-server. |
| `entities.cross-server-transfer.items` | boolean | `false` | paper | Item entities may travel cross-server. |
| `entities.cross-server-transfer.vehicles` | boolean | `false` | paper | Vehicles may travel cross-server. |
| `entities.cross-server-transfer.portals-create` | boolean | `true` | paper | Entities may create a portal when missing (vanilla 1.21+). |
| `entities.cross-server-transfer.chunk-tickets.enabled` | boolean | `true` | paper | Destination-chunk tickets for transferred entities. |
| `entities.cross-server-transfer.chunk-tickets.ring` | boolean | `true` | paper | Additionally load a one-chunk ring around the destination. |
| `api.enabled` | boolean | `true` | paper | Public API surface for other plugins. Consumed-by-design. |
| `api.compat.fire-synthetic-events` | boolean | `false` | paper | Re-dispatch synthetic portal/teleport events on the target server. Consumed-by-design. |
| `api.compat.grant-advancements` | boolean | `true` | paper | Idempotent advancement criteria grant (`enter_the_nether` / `enter_the_end`). Consumed-by-design. |
| `sync.enabled` | boolean | `true` | paper | Player-data sync via external plugin. Active. |
| `sync.wait-for-sync` | boolean | `true` | paper | Await applied sync data on the target before the transfer proceeds. When false, no wait is armed (transfers proceed unconfirmed; one-time warning). |
| `sync.plugin` | string | `auto` | paper | Sync strategy: `auto`, `husksync`, `reflection`, `delayed`, `none`. See `docs/player-sync.md`. |
| `sync.wait-timeout` | int (milliseconds) > 0 | `5000` | paper | Upper bound of every sync wait; never blocks the game thread. Active. |
| `sync.wait-ticks` | int >= 0 | `20` | paper | Fixed delay for the delay-based strategies; capped at `sync.wait-timeout`. Active. |
| `sync.reflection.event-class` | string (FQCN) or `""` | `""` | paper | Event class for the reflection strategy; must extend `org.bukkit.event.Event`. Active. |
| `sync.reflection.getter` | string (method name) | `getPlayer` | paper | No-arg getter on the event returning a Player, a UUID, or an object with `getUuid()`. Active. |
| `redis.enabled` | boolean | `false` | both | Redis availability hint. **Reserved** — no consumer reads it yet; enables nothing today. |
| `database.type` | enum (`mysql`/`mariadb`/`postgresql`/`mongodb`) | `mysql` | paper | Engine. `${VAR}`-free literal. Active. |
| `database.jdbc-url` | string | `jdbc:mysql://localhost:3306/distributed_dimensions` | paper | JDBC URL (SQL types). `${VAR}` expanded at load. Active. |
| `database.username` | string | `distributed_dimensions` | paper | SQL user. `${VAR}` expanded at load. Active. |
| `database.password` | string | `${DB_PASSWORD}` | paper | SQL password. `${VAR}` expanded at load. Blank = no credentials. Active. |
| `database.mongo-uri` | string | `mongodb://localhost:27017` | paper | MongoDB URI (mongodb type). `${VAR}` expanded at load. Active. |
| `database.mongo-database` | string | `distributed_dimensions` | paper | MongoDB database; blank = `distributed_dimensions`. Ignored for SQL. Active. |
| `database.maximum-pool-size` | int ≥ 1 | `10` | paper | Hikari max pool (SQL) / MongoDB pool max. Active. |
| `database.minimum-idle` | int in `[0, maximum-pool-size]` | `2` | paper | Hikari `minimumIdle` / MongoDB pool min. Active. |
| `database.connection-timeout` | int (milliseconds) > 0 | `5000` | paper | Connection acquisition timeout. Active. |
| `database.table-prefix` | string, `[a-z][a-z0-9_]{0,30}` + optional `_` | `dd_` | paper | Identifier prefix for all plugin tables/collections. Active. |
| `debug.portal-search` | boolean | `false` | both | Verbose portal-search logging. Consumed-by-design. |
| `debug.coordinate-conversion` | boolean | `false` | both | Verbose coordinate-conversion logging. Consumed-by-design. |
| `debug.transfers` | boolean | `false` | both | Verbose transfer-flow logging. Consumed-by-design. |
| `debug.entity-transfer` | boolean | `false` | both | Verbose entity-transfer logging. Consumed-by-design. |
| `mirage.enabled` | boolean | `true` | paper | Mirage (void) worlds for foreign dimensions. Consumed-by-design. |
| `mirage.names.overworld` | string | `world` | paper | Mirage world name for the overworld slot; Bukkit level-name convention. Consumed-by-design. |
| `mirage.names.nether` | string | `world_nether` | paper | Mirage world name for the nether slot. |
| `mirage.names.end` | string | `world_the_end` | paper | Mirage world name for the end slot. |
| `mirage.block-edits` | enum (`cancel`) | `cancel` | paper | Guard behavior for edits inside mirage worlds. Consumed-by-design. |
| `mirage.fixed-time` | int in `[0, 24000]` | `6000` | paper | Gamerule time pinned in mirage worlds (noon). Consumed-by-design. |
| `config-version` | int >= 1 | `2` | both | Schema version; drives the migration framework. Active. |

## Platform applicability notes

- Sections not applicable to a platform are simply not read there; no warnings are emitted
  for being ignored. Unknown keys — in applicable or inapplicable sections — are always
  reported as warnings.
- `database.*` is used by the backend (`PersistenceFactory` opens the journal and portal
  index). The proxy ignores it today; if proxy-side persistence lands it will read the
  same keys.
- `redis.enabled` is reserved for cross-server messaging transport; enabling it does
  nothing yet and is kept only to avoid a schema bump when the transport lands.

## Minimal shape

```yaml
general:
  language: "en"
network:
  dimensions:
    nether:
      server: "nether"
      dimension: "minecraft:the_nether"
  proxy:
    enabled: true
config-version: 2
```

Anything omitted is healed from the bundled defaults at load time; the file on disk is
not rewritten for healing.

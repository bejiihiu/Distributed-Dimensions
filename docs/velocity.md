# Velocity companion

The proxy half of Distributed Dimensions. The backend servers (Paper/Folia) own all game
logic - portal detection, portal search, portal creation, teleportation, the worlds
themselves. The Velocity companion owns **connection routing and request validation** and
nothing else: it takes a signed-off request off a backend, decides whether it may execute,
executes the server switch through Velocity's API, and reports the outcome back.

If you are looking for how the backend talks to this plugin, skip to
[What a backend must do](#what-a-backend-must-do).

## Requirements

- Velocity 4.2.0 (API; Java 25 at runtime)
- `distributed-dimensions-velocity-<version>.jar` in `plugins/` (built with
  `./gradlew :velocity:build`, the artifact is `velocity/build/libs/`)

No database is required or used. The proxy keeps everything in memory and holds only
volatile (per-process, restart-bound) state. Durable state - transfer journals, operation
ids, respawn points - lives in the backends' shared database and is none of the proxy's
business.

## Boot sequence

1. The `distributeddimensions:transfer` channel is registered with the proxy's
   `ChannelRegistrar` before anything else.
2. `config.yml` is read from the plugin's data directory (`plugins/distributeddimensions/`,
   `@DataDirectory`). On first start the bundled defaults are written to disk.
3. Configuration health is **recorded, never assumed**. A broken file does not disable the
   plugin - the plugin enables in a fail-closed mode: routing is refused, and every backend
   transfer request gets an explicit `UNAVAILABLE` failure result naming the problem. It
   never half-opens. `/ddproxy reload` re-reads the file; a clean load re-enables routing
   automatically. A broken reload keeps the last-good configuration (or, if nothing was
   ever loaded, the bundled defaults) - see "Configuration" in docs/configuration.md.
   Even a **total** load failure - where even the bundled defaults fail to load, e.g. the
   default `database.password: ${DB_PASSWORD}` with no `DB_PASSWORD` in the service
   environment - only degrades the plugin; it is never propagated out of the boot sequence.
   See "Boot failure and DEGRADED mode" below.
4. The plugin-message listener, the disconnect listener, and the `/ddproxy` admin command
   are registered unconditionally, before the configuration outcome is reported.

Nothing but field injection happens in the plugin constructor.

## Boot failure and DEGRADED mode

`ConfigurationService.load()` throws when no configuration can be produced at all - neither
the file nor the bundled-defaults fallback. The bundled defaults carry
`database.password: ${DB_PASSWORD}`, so on a stock Velocity host with no `DB_PASSWORD`
set, the very first boot of this plugin hits exactly that: the file is created from the
bundled defaults, the `${DB_PASSWORD}` expansion fails, the fallback fails the same way,
and `load()` throws.

The plugin catches that (and any other `RuntimeException` from `load()`) and boots in
**DEGRADED (diagnostic) mode** instead of dying inside the `ProxyInitializeEvent`:

- the `distributeddimensions:transfer` channel **is** registered;
- the plugin-message and disconnect listeners **are** registered;
- `/ddproxy` **is** registered and fully functional;
- every `dimension_transfer_request` is refused with an explicit
  `dimension_transfer_result{ok:false, error:"unavailable"}` whose `message` names the
  cause - the (sanitized, single-line) `ConfigurationException` message - and no
  connection attempt or player lookup is ever issued;
- one SEVERE (ERROR-level) line is written at boot with the actionable fix;
- `/ddproxy status` prints the degraded reason;
- `/ddproxy reload` after fixing the environment or `config.yml` **fully recovers**:
  a clean load re-enables routing live, no restart. If the reload is still broken, the
  command reports the failure (naming the cause) and the plugin stays degraded - the
  exception is never thrown out of the command.

Example of the one SEVERE boot line (wrapped and with the duplicated fallback part elided;
the em dashes are part of the configuration service's message):

```
[distributeddimensions] *** DEGRADED MODE *** configuration failed to load: database.password references environment variable 'DB_PASSWORD' which is not set or is blank — set it in the service environment before starting, or replace ${DB_PASSWORD} with a literal value (empty string = no credentials) (bundled-defaults fallback also failed: ...) — set DB_PASSWORD (or set database.password to "" for no credentials) — the proxy enables in diagnostic mode until then. Every transfer is refused with UNAVAILABLE; run '/ddproxy status' for the full reason and '/ddproxy reload' after fixing the environment or config.yml
```

Example of the `/ddproxy status` output in DEGRADED mode (the wrapped middle line is one
console line, elided with `...`; the rest is verbatim):

```
config: BROKEN - database.password references environment variable 'DB_PASSWORD' which is not set or is blank — set it in the service environment before starting, or replace ${DB_PASSWORD} with a literal value (empty string = no credentials) (bundled-defaults fallback also failed: ...)
  transfers are DISABLED until '/ddproxy reload' reports a clean load
channel: distributeddimensions:transfer (registered)
dimensions: unavailable (configuration is broken)
```

The exact text after `BROKEN - ` is the failure from the configuration service (never the
secret value itself, only the variable name and the remediation). The wire message back to
backends carries the same cause in the shape
`proxy configuration is broken: <cause>; transfers are disabled until '/ddproxy reload' reports a clean load`,
single-line, length-capped at 240 characters for the cause part.

## The `distributeddimensions:transfer` channel

Wire format: JSON, `{"v":1,"type":"<token>",...}`, UTF-8, max 32766 bytes (the Minecraft
plugin-message limit; the codec enforces it before parsing). Only `v = 1` is accepted;
any other version is a hard decode failure.

Every message on our channel, no matter what we do with it, is marked `handled()` - an
unhandled message would otherwise be forwarded by the proxy as if the proxy were
transparent.

### Source policy

Only a source whose `PluginMessageEvent.getSource()` is a `ServerConnection` is a backend.
Anything else - a player, or something unexpected - is dropped silently and counted
(`dropped-player-sourced` / `dropped-unknown-source`). Player-sourced messages can never
redirect or switch another player; there is no path from "client sent bytes" to "proxy
moved a player".

A backend can speak on behalf of the player whose connection carries the message only. The
proxy verifies **which** connection, not just that it was a backend.

### Message types the proxy acts on

| Type | Proxy behaviour |
|---|---|
| `dimension_transfer_request` | validated (below) and executed; outcome reported with `dimension_transfer_result` |
| everything else | ignored or refused - see below |

### Message types the proxy does not act on

The proxy has no game semantics; these indicate a misrouted flow, are logged at WARN, and
are counted (`unsolicited-messages`):

| Type | Behaviour |
|---|---|
| `dimension_transfer_result` | logged and ignored - results are correlated by the requesting backend, not by the proxy |
| `player_respawn_point_query` | logged and ignored **without an answer**; the proxy deliberately does not answer respawn queries. A fabricated `respawn_point: null` answer would be indistinguishable from "no respawn point exists" and would corrupt the end-exit flow. Backends own bed/anchor state via the shared database and fall back to world spawn on their own timeout. |
| `player_respawn_point_answer` | logged and ignored (unsolicited) |
| `respawn_anchor_hint` | logged and ignored (unsolicited) |
| `entity_transfer` | refused with `entity_transfer_ack{outcome="failed", error="rejected", message="entity transfers are not enabled on this proxy"}` so the source fails fast instead of waiting out its timeout |
| `entity_transfer_ack` | logged and ignored (unsolicited) |

If these show up in your server logs, the backend is talking to the proxy with the wrong
message - fix the backend, not the log level.

## Request validation pipeline

For `dimension_transfer_request`, in order. All failures send a
`dimension_transfer_result{ok:false, error, message}` back to the requesting backend; the
pipeline stops at the first failed check.

0. **Fail-closed gate** - if the proxy configuration is broken, the request is refused
   with `UNAVAILABLE` before anything else happens. No connection attempt is issued.
1. **Player online on the exact connection** - the player named in `player_uuid` must be
   online and their current connection must be *the very `ServerConnection` object* the
   message arrived on. A request that claims a connection the player is not on is refused
   (`REJECTED`). This is the strongest usable binding between the message and the player.
2. **Source server match** - the claimed `source_server` must equal the server the
   requesting connection is registered as.
3. **Target dimension is configured, with the claimed server** - the claimed
   `target_dimension` must appear in `network.dimensions.*` in `config.yml`, and the
   configured server name must equal the claimed `target_server`. Server names come from
   the config; nothing about "overworld"/"nether"/"end" is hardcoded in the routing logic
   (the three well-known *dimension keys* are fixed, their server names are not).
4. **Target server exists** - the configured target must be registered in the proxy
   (`proxyServer.getServer(name)` present). A configured-but-absent server fails here
   (`BACKEND_DOWN`).
5. **Idempotency gate** - the `request_id` must not be an operation this proxy is already
   processing or has completed within the TTL window. See "Idempotency" below.
6. **Protocol version** - `v` must be 1. Enforced at the codec boundary (the message never
   reaches the pipeline otherwise); when version negotiation fails the proxy answers
   `REJECTED` with "unsupported protocol version" if a correlation id can be salvaged from
   the envelope, and always counts `unsupported-version`.

`source_dimension` in the request is carried for correlation and logging only; the proxy
does not validate it (routing never depends on it).

## Transfer execution

On a clean pass the proxy executes:

```java
player.createConnectionRequest(targetRegisteredServer).connect()
```

plus a proxy-side `orTimeout` (`network.transfer.timeout` milliseconds, default 10000).
`connect()` waits for the login handshake to complete - TCP refusal completes the future
exceptionally; a backend that accepts TCP but kicks during login completes it with
`SERVER_DISCONNECTED`. Velocity never fires `KickedFromServerEvent` on this path and never
pings the backend first.

The outcome is mapped like this:

| Velocity result | Reported to backend |
|---|---|
| `SUCCESS` | `ok: true` ("transfer complete") |
| `ALREADY_CONNECTED` | `ok: true` - treated as success (idempotent: the player is where the request wants them) |
| `CONNECTION_IN_PROGRESS` | `ok: true` - "success-ish": a connection attempt for this player is already in flight (e.g. a double portal use); no new connect is issued. The backend must not re-apply anything and should treat the player's arrival on the target as the real signal. If the in-flight attempt is to a *different* server, the player will not land on the claimed target - unavoidable without veto APIs, documented here honestly |
| `CONNECTION_CANCELLED` | `REJECTED` - a proxy policy (`ServerPreConnectEvent`) denied the switch |
| `SERVER_DISCONNECTED` | `UNAVAILABLE` - the target accepted the socket but refused the login |
| future completed exceptionally | `BACKEND_DOWN` - the target is unreachable (connect refused, no route, unknown host) |
| future did not complete within `timeout` | `TIMEOUT` |
| player disconnected before/during connect | `DISCONNECTED` (via abandonment, see below) |
| unknown/new status enum constant (future Velocity) | `UNKNOWN` |

After the outcome is decided the operation is removed from the pending set on **all**
paths, the outcome is cached in the completed map (TTL, for replay), and the outcome is
appended to the recent-operations ring (`/ddproxy transfers`).

The `final_position` field of the result is always absent - the proxy does not know where
the player will materialize; that is the backend's business.

## Idempotency

Semantics are keyed on `request_id` (a v4 UUID minted by the backend):

- **First sight** - the operation registers as pending; one connection attempt is issued.
- **Same id, same player/source/target, still pending** - the request is swallowed
  (no second connect, no message; the in-flight operation owns the id). Counted as
  `in-flight-replays`.
- **Same id, finished within the TTL window (~10 minutes)** - the cached outcome is
  re-sent; the connection is **not** repeated. Counted as `completed-replays`.
- **Same id with different player/source/target coordinates** - rejected `REJECTED`; an id
  can never name two operations. Counted as `conflicting-replays`.
- **Window expired** - the id is treated as unknown and the operation starts fresh.

Pending and completed maps are bounded (512 in-flight, 1024 completed outcomes) and expire
lazily, so the maps cannot grow without limit on a busy proxy.

**After a proxy restart the whole idempotency state is gone.** A backend retry arriving
after a restart re-executes the operation. Velocity's own `ALREADY_CONNECTED` /
`CONNECTION_IN_PROGRESS` statuses make this safe in practice (the duplicate connect becomes
a no-op `ok: true`), but a proxy restart mid-transfer can produce a second connection
attempt where the design would normally dedup one. Do not restart the proxy during a
transfer wave if you can avoid it; the backends will reconcile via their own state and
their shared database.

## Result delivery back to the backend

`dimension_transfer_result` is delivered back to the **requesting backend**:

1. Primary: `sendPluginMessage` on the very `ServerConnection` the request arrived on.
   This works only while that connection is alive - in every failure mode in which the
   player is still logged into the source (`BACKEND_DOWN`, `UNAVAILABLE`, `TIMEOUT`,
   rejected requests, broken-config refusals) it is alive, which is exactly when the
   backend needs the answer.
2. Fallback: if the direct send fails because the source connection is already gone (a
   successful switch tears it down), the proxy piggybacks the message via the source
   backend's `RegisteredServer.sendPluginMessage` - it rides the connection of *some other
   player* online on that backend and is lost (silently, returns `false`) when nobody is
   online. Correlation is by `request_id`, so a foreign carrier connection is fine; the
   backend dedups on the id. Counted as `result-send-fallbacks`.

If both paths fail, the proxy logs a warning, counts `result-send-failures`, and the
backend falls back to its own timeout reconciliation. A result is never sent to the
*target* backend, and never sent to the client.

## Disconnect during connect

Velocity fires `DisconnectEvent` when the player leaves the proxy. The companion listens
for it and marks every in-flight operation of that player abandoned:

- the operations are dropped from the pending set (no leak, no crash);
- their cached outcomes become `DISCONNECTED` (a backend replay with the same `request_id`
  learns the operation failed instead of hanging);
- nothing is re-kicked and no message is forced onto the dead connection;
- the underlying `connect()` future is still awaited; its late completion is observed and
  discarded with a debug log (it can still land the player on the target - that is
  velocity's internal state machine, not something the proxy can cancel).

## Failure matrix

| Scenario | Player stays | Backend sees | Retry advice |
|---|---|---|---|
| Target down (TCP refusal) | on source | `BACKEND_DOWN` result | retry with a **new** request_id |
| Target kicked during login | on source | `UNAVAILABLE` result | retry with a new id, or after a delay |
| Target unreachable network/firewall blackhole | on source | `TIMEOUT` after `timeout` ms; the underlying connect may still land later | retry with a new id; make the timeout comfortably above the backend's login time |
| Player disconnects mid-connect | disconnected | (nothing - no carrier); replay sees `DISCONNECTED` | treat as failed; the backend's own player-state handles it |
| Identical request while in-flight | unchanged | nothing (swallowed) | wait for the result; never mint a second request for the same operation |
| Replay within 10 min | n/a | cached outcome re-sent | free |
| Proxy enabled but config broken (or config unloadable at boot) | n/a | `UNAVAILABLE` naming the config problem | fix config or environment (e.g. set `DB_PASSWORD`), `/ddproxy reload` - routing re-enables live |
| Proxy restarts mid-operation | undefined | nothing | backends reconcile via shared DB; avoid proxy restarts during transfers |
| Target configured but not registered in proxy | on source | `BACKEND_DOWN` | fix `velocity.toml` server registration |
| Entity transfer requested | n/a | `entity_transfer_ack{failed, rejected}` | this build does not route entities |
| Unsupported protocol version | n/a | `REJECTED` if a correlation id could be extracted, plus a WARN | upgrade the side that speaks the old version |

## Admin command

Native command, no external command framework. Permission: `distributeddimensions.admin`
(console is always allowed; without a permission plugin, nobody else is).

```
/ddproxy status            # config health, channel registration, dimensions, in-flight ops,
                           # TTL map sizes, counters
/ddproxy reload            # re-read config.yml; keeps last-good on failure
/ddproxy transfers [n]     # last n operations (default 10, max 50), newest first
```

`status` reports: configuration health (or the exact load error and the degraded mode it
implies - on a stock host with no `DB_PASSWORD` set, the boot is DEGRADED and this is where
the reason shows up), whether the `distributeddimensions:transfer` channel is registered,
the resolved `network.dimensions` table, how many transfers are in flight and how many
completed outcomes are cached (with capacities and TTL), and all transfer counters
(`requests-received`, `validation-failures`, `in-flight-replays`, ...).

`reload` re-reads `config.yml` and re-expands `${VAR}` references. A broken file keeps the
last-good configuration - the command says so and lists the load report notes; routing stays
disabled only if nothing was ever loaded successfully. When no fallback exists at all (an
unloadable configuration at boot), the command reports the failure instead of throwing and
routing stays disabled. A clean load re-enables routing with no restart.

`transfers` prints the recent-operations ring: timestamp, request-id prefix, player,
source → target, outcome kind (`SUCCESS` / `FAILED` / `ABANDONED`), error code, duration.

## Configuration (proxy-side keys)

The same `config.yml` schema serves both platforms; the keys the proxy reads are:

```yaml
network:
  transfer:
    timeout: 10000        # milliseconds; budget for the whole connect operation
  dimensions:
    overworld:
      server: "overworld"   # velocity.toml server name, NOT hardcoded - change freely
      dimension: "minecraft:overworld"
    nether:
      server: "nether"
      dimension: "minecraft:the_nether"
    end:
      server: "end"
      dimension: "minecraft:the_end"
```

Notes:

- `network.proxy.enabled` is a backend-advertised flag; the proxy does not read it.
- The proxy ignores `database.*` and does not need them.
- Server names must match the registered names in `velocity.toml` exactly.
- `dimension` values are namespaced resource keys; they are matched literally.

## What a backend must do

Backend implementers, this is the contract:

1. Use the channel `distributeddimensions:transfer`. Register it on Paper with
   `registerOutgoingPluginChannel` / `registerIncomingPluginChannel` (or the equivalents in
   your framework).
2. When you want a player switched, send one message from the backend that currently holds
   the player, over that player's connection:

   ```json
   {"v":1,"type":"dimension_transfer_request",
    "request_id":"<v4 UUID, mint once, reuse on retries>",
    "player_uuid":"<the player's UUID>",
    "source_server":"overworld",   // your own server name as registered in velocity.toml
    "target_server":"nether",      // a configured + registered server
    "source_dimension":"minecraft:overworld",   // correlation only
    "target_dimension":"minecraft:the_nether",
    "target_position":{"x":1.5,"y":64.0,"z":2.5,"yaw":90.0,"pitch":0.0},
    "cause":"nether_portal"}
   ```

   The message is capped at 32766 bytes; unknown fields are ignored (forward compatible),
   missing required fields are rejected by the codec.
3. The proxy answers with exactly one `dimension_transfer_result` per accepted request:

   ```json
   {"v":1,"type":"dimension_transfer_result",
    "request_id":"<the same id>",
    "ok":false, "error":"backend_down", "message":"target server 'nether' is unreachable (ConnectException)"}
   ```

   `ok: true` means the player is (or is about to be) on the target; `final_position` is
   never sent (the proxy does not know it). `ok: false` always carries one of:
   `timeout`, `backend_down`, `unavailable`, `rejected`, `disconnected`, `unknown`.
4. Where the result arrives: on **your** ServerConnection to the proxy, or, if that
   connection is already torn down (successful switch), piggybacked on the connection of
   some *other* player online on your backend. Either way the only reliable correlation is
   `request_id` - never correlate on "who carried it". Switch your read loop to match any
   incoming message whose `request_id` equals an in-flight one.
5. Wait for the result before applying player-side consequences (removing pending state,
   picking a respawn point, minting the arrival location). On timeout: fail the operation,
   and retry with a **new** `request_id` - replaying the same id within 10 minutes returns
   the cached outcome without re-executing.
6. Never send `dimension_transfer_result`, `player_respawn_point_query`,
   `respawn_anchor_hint`, or `entity_transfer_*` **to** the proxy in this build; they are
   ignored or refused as described above.
7. If your operation involves Beds/anchors (the End exit flow), you resolve the respawn
   point against the shared database **yourself**; the proxy never answers that query. The
   proxy will not help you here, by design.
8. Velocity-side switch is executed by `Player#createConnectionRequest(...).connect()`; do
   not try to hold or close the player's connection yourself around it.
9. Do not restart the proxy during active transfers. Completed-request replay state does
   not survive a restart (see Idempotency).
10. On any failure the proxy leaves the player's state alone: it never kicks, redirects,
    or alters the player. Only the success path moves the connection.

## Known limitations and non-goals

- No database on the proxy; all state is per-process and volatile.
- `CONNECTION_IN_PROGRESS` is reported as `ok: true` - see the outcome table for the
  precise caveat; the player will end up wherever the in-flight connect lands.
- Timeout does not cancel Velocity's internal connect state machine. A timed-out connect
  can complete later; the player can land on the target after you already saw `TIMEOUT`.
- On `SUCCESS`, the result is delivered best-effort: the direct source connection is
  usually gone, and the piggyback fallback needs at least one other player online on your
  backend. A result can be lost here; your timeout reconciliation is the backstop.
- In-flight duplicates of a request are swallowed without an answer (debug-logged only).
- The proxy does not authenticate the *payload* cryptographically; the trust perimeter is
  "registered backends only" enforced by `ServerConnection` + server-name + UUID checks.
  Do not route this channel through anything that lets untrusted backends in.
- Entity transfer routing is not implemented (refusals only, see the table above).
- The proxy cannot prevent a backend from claiming a different `source_server` than it is;
  the check is between the claim and the connection it arrived on (which is all the
  information the proxy has).

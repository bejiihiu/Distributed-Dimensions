# Admin commands

Distributed Dimensions ships two admin command surfaces, one per plugin:

| Command | Plugin | Platform | Purpose |
|---|---|---|---|
| `/dd` | `DistributedDimensionsPaper` | Paper/Folia backend | Inspect and re-check the **backend** configuration |
| `/ddproxy` | `DistributedDimensionsVelocity` | Velocity proxy | Inspect and re-check the **proxy** configuration and transfers |

Both are intentionally native commands — no external command framework. On the backend
`paper-plugin.yml` cannot declare commands (Paper has no `commands` field there), so the
plugin registers `/dd` from code; the proxy registers `/ddproxy` natively against the
Velocity API. Permission for both is the single node:

```
distributeddimensions.admin
```

Default: **op** (backend, declared in `paper-plugin.yml`) / **console-only** otherwise
(proxy). To grant to a rank, give that node via your permission plugin
(`lp user <name> permission set distributeddimensions.admin true`). The console can always
run both commands. With no permission plugin installed on the backend, operators can run
`/dd` out of the box.

Both plugins are independent: editing one configuration never affects the other, and the
two `reload` commands are unrelated — run both, in either order, if you changed both files.

---

## `/dd` — backend admin (Paper/Folia)

Permission: `distributeddimensions.admin` (default: **op**).
Usage: `/dd <info|reload|mirage>`. Tab completion suggests the subcommands.

With no subcommand (or an unknown one), the command prints usage plus the list of
subcommands. Subcommand names are case-insensitive. Output is localized: set
`general.language` in `config.yml` to `ru` or `en`. Anything else falls back to English
with a single startup warning (see "Language" below).

### `/dd info`

Backend overview, one fact per line:

- plugin version and server version (`Bukkit.getVersion()`, includes the MC version)
- **health**: `HEALTHY`, or `DEGRADED` with the reasons (e.g. `persistence unavailable`,
  `configuration fell back to bundled defaults`, `dimensions: ...`). A degraded backend
  **refuses transfers**; `/dd info` is the fastest way to see why
- configuration source (`FILE` / `LAST_GOOD` / `DEFAULTS_FALLBACK`), whether the file was
  created on this boot, `config-version`, and whether a migration ran (`v<from> -> v<to>`)
- database: **engine, pool sizes, table prefix — never the JDBC/Mongo URL and never the
  credentials** (URLs and passwords are redacted everywhere this plugin logs or renders)
- dimension routing map, `dimension -> server`, exactly as configured
- mirage (void stub) worlds: enabled/disabled and how many are tracked, plus a hint to run
  `/dd mirage` when some worlds failed to materialize
- sync level bound (`husksync-api`, `reflection`, `presence-delay`, `degraded-fallback`, …)
  with its phase (`READY`/`DEGRADED`/`FAILED`) and whether `sync.wait-for-sync` is on,
  or `not bound` when the sync integration could not be armed
- api switch and the two compat flags actually in effect (`synthetic-events`,
  `grant-advancements`)
- uptime since plugin enable

Notes:

- `/dd info` shows the **running** configuration snapshot. Right after a `/dd reload` it
  may differ from the file on disk by exactly the sections the reload reports as changed —
  see below.
- Safe to run at any time, including on a degraded backend (that is when you need it most).

### `/dd reload` — configuration re-check, honestly

`/dd reload` re-reads `config.yml` **for real**. What it does and does not do:

1. Re-runs the full boot-time load sequence for the file: parse → `config-version`
   migrations (applied and atomically **saved back to the file**) → heal from bundled
   defaults (in memory; the file is *not* rewritten for healing alone) → expand `${VAR}`
   for the active database branch → collect unknown keys as warnings.
2. Compares the freshly loaded snapshot against the **running** snapshot and reports which
   top-level sections changed (`general`, `network`, `portals`, `entities`, `api`, `sync`,
   `redis`, `database`, `debug`, `mirage`, `config-version`).
3. Applies **exactly one key** live: `general.language` — the message bundle is
   re-selected, so the very next `/dd` output is in the new language.
4. Nothing else is applied. The backend's services are constructor-wired at boot; a fake
   "everything reloaded" would silently lie to you. Instead the output says which sections
   changed and states plainly that a restart is required.

Output lines:

| Line | Meaning |
|---|---|
| `configuration check: OK` + `no changes ... restart` | File is healthy and identical to the running configuration |
| `configuration check: OK` + `changed but NOT applied: <sections>` | File is healthy; listed sections differ from the running configuration; restart to apply |
| `migration applied: v<a> -> v<b>` | A `config-version` migration ran during this check (the file on disk was rewritten) |
| `language applied: messages now use <lang>` | `general.language` changed; bundle re-selected live |
| `unknown keys ignored: ...` | The file carries keys the schema does not know (never fatal) |
| `configuration check: FAILED` | The file could not be loaded; **the running backend is unaffected** (last-good configuration kept); fix the file and re-run `/dd reload` |

Migrations are the only part of `/dd reload` that writes to `config.yml` — and they only
write on an actual version bump, exactly as they do at boot. A clean heal-only reload never
writes the file.

Deltas are section-level names only; values are never printed (the database section may
carry a password). The `<sections>` list tells you whether `database` changed — restart
applies it atomically with the rest.

**Recommended workflow**: edit `config.yml` → `/dd reload` → if it says `NOT applied`, plan a
restart → restart both backends (not just one — routing must see the same file).

### `/dd mirage`

Lists every world the mirage registry tracks, created or adopted:

- name, environment, the dimension the mirage stands in for, and its kind —
  `mirage (stub created by the plugin)` vs `real world` (an already-loaded real world that
  occupies the slot; the plugin never shadowed or recreated it)
- a totals line: stubs created vs pre-existing real world slots
- creation failures, if any (with the reason) — a degraded mirage registry keeps the
  backend running; those worlds simply do not exist locally

`mirage.enabled=false` prints a single line saying exactly that. The registry — not a world
scan — is the source of truth, consistent with the teleport guards.

### `/dd` output and security

No line ever contains the JDBC/Mongo URL, credentials, or passwords. Health reasons come
from the fail-closed subsystems and are already redacted. The `/dd` permission node governs
who may read this.

---

## `/ddproxy` — proxy admin (Velocity)

Permission: `distributeddimensions.admin` (console always allowed; without a permission
plugin, grant to admins explicitly).

```
/ddproxy status            # config health, channel registration, dimensions,
                           # in-flight operations, TTL map sizes, counters
/ddproxy reload            # re-read config.yml; keeps last-good on failure
/ddproxy transfers [n]     # last n operations (default 10, max 50), newest first
```

`status` reports: configuration health (or the exact load error and the degraded mode it
implies), whether the `distributeddimensions:transfer` channel is registered, the resolved
dimension → server map, in-flight operations and pending capacity, outcome-replay cache
size and TTL, and the operation counters.

`reload` re-reads the proxy `config.yml`; a clean load re-enables routing, a broken file
keeps the last-good configuration and routing stays disabled.

`transfers [n]` lists the newest completed/failed/abandoned operations with kind, player,
source → target, duration and error classification (if any). `n` is capped at 50.

See `docs/velocity.md` for the full proxy-side failure matrix and semantics.

---

## Language

`general.language` selects the message bundle. The backend honors `ru` and `en`:

- `ru` → Russian bundle
- `en` → English bundle (the bundled default)
- anything else → **English** + a single warning at selection time (not one per command)

A `/dd reload` that changes `general.language` applies the new language immediately
(see `/dd reload` above). The proxy has no language key; `/ddproxy` output is English.

### Changing the language

```yaml
general:
  language: "ru"
```

Then `/dd reload` and watch for `language applied: messages now use ru`.

---

## Permissions summary

| Plugin | Node | Default | Notes |
|---|---|---|---|
| Backend (`/dd`) | `distributeddimensions.admin` | `op` | Declared in `paper-plugin.yml`; the console always has it |
| Proxy (`/ddproxy`) | `distributeddimensions.admin` | console only | Grant via your permission plugin |

There are no per-subcommand nodes by design — the surface is three read-only/diagnostic
operations plus a config re-check, and splitting it further would be theatre.

---

## Cheat sheet

```
/dd info          # is the backend healthy? why not?
/dd reload        # re-check config.yml after editing it (language applies live)
/dd mirage        # which void stub worlds exist / failed
/ddproxy status   # is the proxy routing healthy?
/ddproxy reload   # re-check proxy config.yml
/ddproxy transfers 25
```

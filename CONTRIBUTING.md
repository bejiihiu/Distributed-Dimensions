# Contributing

In short: fork → branch → PR into main. Details below.

## Commits

Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.
Example: `feat: add nether portal search to common`.

## Code

- Java 25 everywhere, a single toolchain (see the root `build.gradle.kts`). Preview features
  (`--enable-preview`) are forbidden in shipped code: servers won't supply the flag, and the jar won't start.
- Shared logic lives in `common`; platform code is adapter-only, in `paper`/`velocity`.
- Public methods are covered by tests in `common/src/test` (JUnit, seams — public interfaces).
- User input into MiniMessage only through `Placeholder.unparsed/parsed`, never by concatenation.
- Folia-safe from the start: entity/region/global/async schedulers in the right place,
  no Bukkit API from foreign threads.
- No mocks/stubs in shipped code: implement it fully or don't do it.
- Code comments are human, in English. User-facing strings are proper, clean English.

## Checks before a PR

```bash
./gradlew build
```

A green build covers the tests and the shade assembly of both jars. Dev servers for manual checks:
`./gradlew :paper:runServer`, `./gradlew :velocity:runVelocity`.

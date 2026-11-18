# Distributed Dimensions

Paper/Folia + Velocity plugin that distributes the three Minecraft dimensions
across independent backend servers. Three independent Paper/Folia backends —
Overworld, Nether, The End — sit behind a single Velocity proxy and behave like
one Minecraft world. Portals and End mechanics stay vanilla; player data
(inventory, XP, health, effects, ...) is synced by a separate external sync
plugin, not by this project.

## Status

Under heavy development, not released yet. The skeleton compiles and loads
both platform jars, but no features are implemented. Expect breakage.

## Requirements

- Java 25 to build (Gradle downloads the toolchain automatically if missing)
- Paper 26.3+ (Folia supported) for the backend jar
- Velocity 4.2+ for the proxy jar

## Build

```bash
./gradlew build
```

Artifacts: `paper/build/libs/distributed-dimensions-paper-<version>.jar`,
`velocity/build/libs/distributed-dimensions-velocity-<version>.jar`.

## License

MIT — see [LICENSE](LICENSE).

## Credits

Structure inspired by [bejiihiu/multiloader-template](https://github.com/bejiihiu/multiloader-template).

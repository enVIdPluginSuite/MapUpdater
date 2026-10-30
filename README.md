# MapUpdater

Lazily refreshes vanilla terrain maps on your server so they stay up to date, without lagging it.

## Build

    ./gradlew build        # -> build/libs/MapUpdater-1.0.0.jar

Kotlin 2.2.21, Gradle 8.14.3. Compiled against the 1.13.2 API to Java 8 bytecode; the Kotlin
stdlib is bundled (relocated) because servers do not ship one. Use any JDK 17+ to build.
Run it on whatever Java your server already needs (8+ for 1.13, 25 for 26.1+).

## How it works

- **Discovery**: maps are noticed as they load, are held, framed, or stored in a chest you open.
  Known ids are saved in `plugins/MapUpdater/maps.yml`.
- **Lazy cycles**: every `refresh-interval-minutes` all tracked maps are queued. Each tick the
  plugin spends at most `max-ms-per-tick` / `max-chunks-per-tick` on one map's chunks, and pauses
  entirely while the server's average tick time is above `pause-above-avg-tick-ms`.
- **Only real terrain**: chunks never generated are never generated. Chunks with nothing explored
  on the map are not even loaded. Generated-but-unloaded chunks are loaded one at a time and released.
- **Leaves other plugins alone**: maps with any non-vanilla renderer (image plugins etc.), locked
  maps, virtual maps, maps with no world, and Nether maps are skipped. `/mapupdater info` tells you
  why a held map is or is not eligible.
- **Real updates**: the map's stored pixels are changed and flagged dirty, so they save to disk and
  are pushed to clients through vanilla's own mechanism.

## Commands (`mapupdater.admin`, default op; aliases `/mapupd`, `/mupdate`)

    /mapupdater status              what it is doing, last cycle, skip reasons
    /mapupdater reload              re-read config.yml
    /mapupdater refresh [mapId]     start a cycle now
    /mapupdater info                details for the held map
    /mapupdater track|untrack <id>
    /mapupdater config              list every setting
    /mapupdater config <key> <value>   change a setting (saved + applied immediately, tab-completes)

Note: on servers older than 1.18.1, saving config from in-game strips the comments from `config.yml`.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.

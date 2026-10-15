# Star Client

Client-side Fabric mod for Minecraft 26.1: ESP, base finder and freecam.

## Stack
- Java 25, Fabric Loom `1.17-SNAPSHOT` (resolves to 1.17.x, requires Gradle >= 9.5.0), Gradle wrapper 9.5.0
- Versions live in `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`, `loom_version`)
- Split source sets: `src/main` (resources only) and `src/client` (all Java)

## Layout
- `src/client/java/com/starclient/`
  - `StarClient` - entrypoint, keybinds (R = ESP, B = base finder, F = freecam, Right Shift = menu; all rebindable in the menu), status overlay text
  - `StarRenderer` - level render hooks and through-walls box pipeline
  - `BaseFinder`, `Freecam` - feature logic
  - `ModulesScreen` (menu, default Right Shift), `EspScreen` (ESP customisation), `ValueSlider`
  - `Config` - persisted settings (`config/starclient.json`)
- `src/main/resources/fabric.mod.json` - mod id `starclient`
- `src/main/resources/assets/starclient/lang/en_us.json` - keybind names

## Build
- `./gradlew build` -> jar in `build/libs/` (ignore `*-sources.jar`)
- CI: `.github/workflows/build.yml` builds on every push/PR and uploads the jar as artifact `star-client`
- Local dev may lack network access to Fabric/Mojang Maven; if so, rely on CI and read logs from the Actions run

## Conventions
- Mojang-mapped names (Minecraft 26.1 is unobfuscated). Mapping/API names change between versions, so when bumping `minecraft_version` expect compile errors in `StarClient` and `StarRenderer` first.
- When bumping Loom, check the required Gradle version and update `gradle/wrapper/gradle-wrapper.properties`.
- Mod id, package and lang keys must stay in sync (`starclient`).
- Tabs for indentation, matching existing code.

# Star Client

Client-side Fabric mod for Minecraft 26.1: ESP, base finder, freecam, and an in-game module menu.

## Stack
- Java 25, Fabric Loom `1.17-SNAPSHOT` (resolves to 1.17.x, requires Gradle >= 9.5.0), Gradle wrapper 9.5.0
- Versions live in `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`, `loom_version`)
- Split source sets: `src/main` (resources only) and `src/client` (all Java)

## Layout
- `src/client/java/com/starclient/`
  - `StarClient` - entrypoint, keybinds, module toggle/state helpers (`keys()`, `isOn()`, `toggle()`), status overlay text
  - `StarRenderer` - level render hooks and through-walls box pipeline; ESP categories/colours come from `Config`
  - `BaseFinder`, `Freecam` - feature logic
  - `ModulesScreen` - module menu: toggle each module, rebind its key, open ESP settings, rebind the menu key
  - `EspScreen` - ESP customisation: per-category toggle and RGB, opacity, range
  - `ValueSlider` - generic 0..1 slider used by `EspScreen`
  - `Config` - persisted settings, JSON at `config/starclient.json`, loaded on init and saved when `EspScreen` closes
- `src/main/resources/fabric.mod.json` - mod id `starclient`
- `src/main/resources/assets/starclient/lang/en_us.json` - keybind names

## Features and keys
| Module | Default key | Notes |
|---|---|---|
| ESP | R | Boxes through walls. Categories (Players, Hostile, Animals, Items) indexed as in `Config.ESP_NAMES` |
| Base Finder | B | Scans loaded chunks for marker blocks (`BaseFinder.MARKERS`), needs 10+ per chunk |
| Freecam | F | Client-only armor stand camera, speed 0.5, sprint x2.5 |
| Menu | Right Shift | Opens `ModulesScreen` |

All keys are rebindable from the menu (saved via `Options`) or Options > Controls.
Module order is fixed: 0 = ESP, 1 = Base Finder, 2 = Freecam (`StarClient.keys()`).

## Build
- `./gradlew build` -> jar in `build/libs/` (ignore `*-sources.jar`)
- CI: `.github/workflows/build.yml` builds on every push/PR and uploads the jar as artifact `star-client`
- The sandbox cannot reach Fabric/Mojang Maven, so it cannot compile locally; push and read the failing job log from the Actions run

## 26.1 API notes (learned from compile errors)
- No `LocalPlayer.displayClientMessage`; use `mc.gui.setOverlayMessage(Component, false)`
- No `StringWidget.alignCenter()`; position the widget manually using `font.width(title)`
- Screens use `keyPressed(KeyEvent)` with `event.key()`; avoid overriding render methods and let widgets draw themselves
- These names change between Minecraft versions, so when bumping `minecraft_version` expect compile errors in `StarClient`, `StarRenderer` and the screens first

## Conventions
- Mojang-mapped names (Minecraft 26.1 is unobfuscated)
- When bumping Loom, check the required Gradle version and update `gradle/wrapper/gradle-wrapper.properties`
- Mod id, package and lang keys must stay in sync (`starclient`)
- New module: add a key in `StarClient`, extend `isOn`/`toggle`/`keys`, add a lang entry, add a row in `ModulesScreen.NAMES`
- Tabs for indentation, matching existing code

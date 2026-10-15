# Star Client

Client-side Fabric mod for Minecraft 26.1: ESP, base finder, freecam, and an in-game module menu.

## Stack
- Java 25, Fabric Loom `1.17-SNAPSHOT` (resolves to 1.17.x, requires Gradle >= 9.5.0), Gradle wrapper 9.5.0
- Versions live in `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`, `loom_version`)
- Split source sets: `src/main` (resources only) and `src/client` (all Java)

## Layout
- `src/client/java/com/starclient/`
  - `StarClient` - entrypoint, keybinds, module toggle/state helpers (`keys()`, `isOn()`, `toggle()`), status overlay text
  - `Module` - module metadata (id, name, description, category, has-settings) and index constants; single source for menu rows and keybind registration
  - `ChestEsp` - scans nearby loaded chunks' block entities (chests, barrels, ender chests, shulkers)
  - `Movement` - per-tick Fullbright (client-side night vision), Auto Sprint, Auto Walk
  - `StarRenderer` - level render hooks and through-walls box pipeline; ESP categories/colours come from `Config`
  - `Combat` - Aim Bot (smooth turn to nearest player/hostile within 6 blocks, 90° FOV, line of sight) and Hit Bot (attacks the crosshair target at >=95% attack charge)
  - `BaseFinder`, `Freecam` - feature logic
  - `ModulesScreen` - module menu: category tabs (All/Render/Movement/World/Combat/Bases; All groups rows under category headings), toggle each module, rebind its key, tooltips, open ESP settings, rebind the menu key
  - `ReachScreen` - reach distance slider
  - `EspScreen` - ESP customisation: per-category toggle and RGB, opacity, range
  - `ValueSlider` - generic 0..1 slider used by `EspScreen`
  - `Config` - persisted settings, JSON at `config/starclient.json`, loaded on init and saved when `EspScreen` closes
- `src/main/resources/fabric.mod.json` - mod id `starclient`
- `src/main/resources/assets/starclient/lang/en_us.json` - keybind names

## Features and keys
| Module | Default key | Notes |
|---|---|---|
| ESP | unbound | Boxes through walls. Categories (Players, Hostile, Animals, Items) indexed as in `Config.ESP_NAMES` |
| Base Finder | unbound | Scans loaded chunks for marker blocks (`BaseFinder.MARKERS`), needs 10+ per chunk |
| Freecam | unbound | Client-only armor stand camera, 10 blocks/s moved per frame (not per tick), sprint x2.5, disables `smartCull` so terrain stays visible |
| Chest ESP | unbound | Boxes on storage/farm block entities (chests, hoppers, droppers, furnaces, crafters, brewing stands, beacons) and growing amethyst (budding block, buds, clusters) within 8 chunks |
| Fullbright | unbound | Applies a hidden client-side night vision effect |
| Auto Sprint | unbound | Sets sprint while forward is held |
| Auto Walk | unbound | Holds the forward key |
| Fly | unbound | Sets `mayfly`+`flying` abilities client-side; needs server permission |
| Reach | unbound | Sets block+entity interaction range attributes client-side (3-12, default 6); servers validate, so far hits are usually rejected. Settings in `ReachScreen` |
| Aim Bot | unbound | Combat tab; eases toward target (20% of the remaining angle per tick, max 8°) toward target |
| Hit Bot | unbound | Combat tab; auto attack via `gameMode.attack` |
| Elytra Fly | unbound | Movement tab; while gliding, alternates dive (+35°) until speed > 1.7 b/t and climb (-45°) until speed < 0.9 b/t, easing pitch 6°/tick. Start gliding yourself (jump twice in air) |
| Kill Aura | unbound | Combat tab; attacks the nearest player/hostile within entity interaction range (Reach extends it) with line of sight, at >=95% charge; no rotation |
| Crits | unbound | Combat tab; before Hit Bot/Kill Aura attacks (not manual clicks) sends two fake `ServerboundMovePlayerPacket.Pos` hops (+0.0625 y, off-ground) so the server counts a crit. Server ignores crits while you are sprinting |
| Hitboxes | unbound | Render tab; filled boxes on living entities through walls, red inside reach, white outside |
| Menu | Right Shift | Opens `ModulesScreen` |

Module keys are unbound by default (menu key is Right Shift). All keys are rebindable from the menu (saved via `Options`) or Options > Controls.
Module order is fixed by `Module.ALL`: 0 ESP, 1 Base Finder, 2 Freecam, 3 Chest ESP, 4 Fullbright, 5 Auto Sprint, 6 Auto Walk, 7 Fly, 8 Reach, 9 Aim Bot, 10 Hit Bot, 11 Elytra Fly, 12 Kill Aura, 13 Crits, 14 Hitboxes.

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
- New module: append to `Module.ALL` (+ index constant), extend `isOn`/`toggle`, add a lang entry `key.starclient.<id>`
- Tabs for indentation, matching existing code

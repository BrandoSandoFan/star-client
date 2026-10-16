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
  - `ChestEsp` - scans nearby loaded chunks' block entities (chests, barrels, ender chests, shulkers, spawners); `hits()` drops positions `AntiBait` flagged
  - `Movement` - per-tick Fullbright (client-side night vision), Auto Sprint, Auto Walk, Jesus and the other movement modules
  - `Utility` - Auto Eat, Anti AFK, Sorter
  - `Hud` - enabled-module list overlay
  - `SortButton` - "Sort" button on container screens (`ScreenEvents.AFTER_INIT`), calls `Utility.sortOpenContainer`
  - `ProfilesScreen` - 5 profile slots (`Config.Profile`: module on/off states plus ESP, Reach and Hitbox settings); Save/Load/Clear, opened from the "Profiles..." button in `ModulesScreen`; Load toggles modules to match the snapshot
  - `StarRenderer` - level render hooks and through-walls box pipeline; ESP categories/colours come from `Config`
  - `Combat` - Aim Bot (smooth turn to nearest player/hostile within 6 blocks, 90° FOV, line of sight) and Hit Bot (attacks the crosshair target at >=95% attack charge)
  - `BaseFinder`, `Freecam` - feature logic
  - `AntiBait` - per-tick diff of block entities in already-loaded chunks; bursts and new spawners are flagged and filtered out of `ChestEsp.hits()` and `BaseFinder.hits()`
  - `ModulesScreen` - module menu: category tabs (All/Render/Movement/World/Combat/Bases; All groups rows under category headings), toggle each module, rebind its key, tooltips, open ESP settings, rebind the menu key
  - `ReachScreen` - reach distance slider
  - `HitboxScreen` - hitbox expansion slider
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
| Chest ESP | unbound | Boxes on storage/farm block entities (chests, hoppers, droppers, furnaces, crafters, brewing stands, beacons, spawners) and growing amethyst (budding block, buds, clusters) within 8 chunks |
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
| Hitboxes | unbound | Combat tab; enlarges every entity hitbox by `Config.hitboxExpand` blocks per side (0-2, default 0.3, `HitboxScreen`). At the start of each tick `Combat.expandHitboxes` re-picks the crosshair entity against the inflated boxes (no mixin), so clicks, Hit Bot and Kill Aura use it; server still validates real reach |
| Speed | unbound | Movement tab; sets horizontal velocity to 0.45 b/t along held WASD keys (skipped when gliding, flying, swimming or riding). Servers with movement checks rubber-band |
| High Jump | unbound | Movement tab; on take-off with jump held, sets upward velocity to 0.9 b/t (vanilla 0.42) |
| Softfall | unbound | Movement tab; while `fallDistance` > 2 and airborne (not gliding/flying) sends `ServerboundMovePlayerPacket.StatusOnly(onGround=true)` each tick so the server resets fall damage |
| Trajectories | unbound | Render tab; `Trajectories.compute` simulates the held bow (draw-scaled), crossbow, trident, pearl, snowball, egg or potion (gravity/drag per item, block clip each tick, 200 steps max); `StarRenderer` draws small boxes along the path and a red box at the impact |
| Jesus | unbound | Movement tab; in water without sneak/jump, sets vertical velocity to hold you at the surface (0.2 b/t up when submerged, 0.1 below the fluid height, 0 on top). Water only; lava still burns |
| Auto Eat | unbound | World tab; when food <= 14 and `needsFood`, picks the highest-nutrition hotbar food (not `Utility.NOT_FOOD`), selects it, `gameMode.useItem` and holds `keyUse` until finished, then restores the slot. Hit Bot/Kill Aura/Aim Bot pause while eating |
| Anti AFK | unbound | World tab; after 400 idle ticks (no movement or view change, no screen) jumps for 2 ticks and nudges yaw +-10 alternately |
| Sorter | unbound | World tab; one-shot: plans merges then a selection sort of main inventory slots 9-35 (item id, then count desc, empties last) as `PICKUP` click triples, replays 2 per tick via `gameMode.handleContainerInput` (`ContainerInput.PICKUP`), then switches itself off. Toggling sorts the player inventory. Chests, ender chests, barrels and shulker boxes (`ChestMenu`/`ShulkerBoxMenu`) get a "Sort" button added by `SortButton` that sorts the container part (slots 0..size-36) |
| HUD | unbound | Render tab; `Hud` registers a `HudElementRegistry` element before chat that draws enabled module names top-right (widest first, category colour) |
| Panic | unbound | World tab; one-shot: `StarClient.panic` toggles off every enabled module (HUD included) and closes the screen. Never reports ON |
| Anti Bait | unbound | Render tab; every tick diffs each loaded chunk's block entities against what it held before (first sight of a chunk is baseline; unloaded chunks remembered 100 ticks so an unload+resend still diffs). 3+ new Chest ESP/Base Finder targets within 16 blocks of each other in one tick, or any single new spawner, are hidden from Chest ESP, and their chunk from Base Finder. Entity ESP is not affected. Plain blocks (amethyst, glass) are not tracked |
| Menu | Right Shift | Opens `ModulesScreen` |

Module keys are unbound by default (menu key is Right Shift). All keys are rebindable from the menu (saved via `Options`) or Options > Controls.
Module order is fixed by `Module.ALL`: 0 ESP, 1 Base Finder, 2 Freecam, 3 Chest ESP, 4 Fullbright, 5 Auto Sprint, 6 Auto Walk, 7 Fly, 8 Reach, 9 Aim Bot, 10 Hit Bot, 11 Elytra Fly, 12 Kill Aura, 13 Crits, 14 Hitboxes, 15 Speed, 16 High Jump, 17 Softfall, 18 Trajectories, 19 Jesus, 20 Auto Eat, 21 Anti AFK, 22 Sorter, 23 HUD, 24 Panic, 25 Anti Bait.

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
- Client chunks: `mc.level.getChunk(cx, cz, ChunkStatus.FULL, false)` is a `LevelChunk` when loaded; `LevelChunk.getBlockEntities()` is the live map (no copy, unlike `getBlockEntitiesPos()`)
- Tabs for indentation, matching existing code

# Star Client

Client-side Fabric mod for Minecraft 26.2: ESP, base finder, freecam, and an in-game module menu.

## Stack
- Java 25, Fabric Loom `1.17-SNAPSHOT` (resolves to 1.17.x, requires Gradle >= 9.5.0), Gradle wrapper 9.5.0
- Versions live in `gradle.properties` (`minecraft_version`, `loader_version`, `fabric_api_version`, `loom_version`)
- Split source sets: `src/main` (resources only) and `src/client` (all Java)

## Layout
- `src/client/java/com/starclient/`
  - `StarClient` - entrypoint, keybinds, module toggle/state helpers (`keys()`, `isOn()`, `toggle()`), status overlay text
  - `Module` - module metadata (id, name, description, category, has-settings) and index constants; single source for menu rows and keybind registration
  - `ChestEsp` - scans nearby loaded chunks for the blocks in `Config.chestBlocks`; `hits()` drops positions `AntiBait` flagged
  - `Movement` - per-tick Fullbright (client-side night vision), Auto Sprint, Auto Walk, Jesus and the other movement modules
  - `Utility` - Auto Eat, Anti AFK, Sorter
  - `Hud` - enabled-module list overlay
  - `SortButton` - "Sort" button on container screens (`ScreenEvents.AFTER_INIT`), calls `Utility.sortOpenContainer`
  - `ProfilesScreen` - 5 profile slots (`Config.Profile`: module on/off states plus ESP, Reach and Hitbox settings); Save/Load/Clear, opened from the "Profiles..." button in `ModulesScreen`; Load toggles modules to match the snapshot
  - `StarRenderer` - level render hooks and through-walls box pipeline; ESP categories/colours come from `Config`
  - `ProtocolSanitizer` - choke point for velocity writes (`setVelocity`, `clampJump`), view turns (`limitTurn`) and fake movement packets (`sendHop`, `sendGround`); clamps to vanilla limits unless global setting `sanitize.enabled` is off (`sanitize.maxturn` caps degrees per tick). New modules must write velocity, rotation and movement packets through it
  - `AimSpring` - critically damped spring (closed-form, one tick per step, no randomness) used by Aim Bot when `aimbot.spring` is on; `aimbot.omega` sets stiffness, turns still pass `aimbot.turn` and `sanitize.maxturn`
  - `Diagnostics` - module 26: overlay (`diag.overlay`) and JSON-lines log `logs/starclient-diagnostics.jsonl` (`diag.log`, `diag.logall`) of requested h-velocity/jump/turn/packet hop (reported by `ProtocolSanitizer` before clamping), observed speed/rise/position snap, and attack gap against each limit
  - `Gear` - hotbar/inventory helpers: Auto Tool, Auto Totem, Auto Armor, Auto Replenish, Auto Respawn. Inventory modules share one cooldown and run only when the player menu is open (`containerMenu == inventoryMenu`), the cursor is empty and the Sorter is idle; they click with `PICKUP` triples on `inventoryMenu` slots (armor 5-8, main 9-35, hotbar 36-44, offhand 45)
  - `Zoom` - lowers the `fov` option while on (min 30, the vanilla slider floor), restores it on off, disconnect and client stop
  - `Waypoints`, `WaypointsScreen` - saved positions in `Config.waypoints` (per dimension key string); beams in `StarRenderer`, nearest 5 with distance and direction arrow in a top-centre HUD element; death point recorded on the dying edge; screen opened from the Waypoints settings page
  - `PortalFinder` - section scan (like `BaseFinder`) for lit `NETHER_PORTAL`, `END_PORTAL_FRAME`, `END_PORTAL` and `END_GATEWAY`; boxes in `StarRenderer`. Unlit nether frames are plain obsidian and are not found
  - `SignReader` - nearest signs with text (front and back, `SignBlockEntity`) within `signreader.range`, listed top-left via a HUD element with `Waypoints.arrow`; optional highlight boxes
  - `AutoSafety` - Auto Log and Auto Water; `AttributeSwap` - slot swap around attacks; `Automation` - Automine, Chat Macro, Auto TPA; `Clipper` - frame ring buffer and clip writer
  - `Combat` - Aim Bot (smooth turn to nearest player/hostile within 6 blocks, 90° FOV, line of sight) and Trigger Bot (attacks the crosshair target at >=95% attack charge)
  - `BaseFinder`, `Freecam` - feature logic
  - `AntiBait` - per-tick diff of block entities in already-loaded chunks; bursts and new spawners are flagged and filtered out of `ChestEsp.hits()` and `BaseFinder.hits()`
  - `ModulesScreen` - module menu: category tabs (All/Render/Movement/World/Combat/Bases; All groups rows under category headings), module list scrolls with the mouse wheel (rows outside the viewport are not added; footer stays below it), toggle each module, rebind its key, tooltips, open ESP settings, rebind the menu key
  - `Settings` - registry of per-module numeric/toggle settings (`Settings.Def`, stored in `Config.values`) and free-text settings (`Settings.TextDef`, stored in `Config.texts`, edited with an `EditBox` in `ModuleSettingsScreen`; not part of profiles); read with `Settings.get/getI/getF/on(key)`. Add a module setting by adding a `Def` in the static block; `ModuleSettingsScreen` builds the page automatically
  - `ModuleSettingsScreen` - generic settings page for every module with `hasSettings` (except ESP, which uses `EspScreen`); Chest ESP adds block presets + picker; nested `MobFilter` is the ESP mob filter page
  - `PickerScreen` - searchable paged multi-select (mobs, blocks)
  - `Presets` - block lists (Chest ESP) and mob lists (ESP filter)
  - `EspScreen` - ESP customisation: per-category toggle and RGB, opacity, range
  - `ValueSlider` - generic 0..1 pixel-style slider used by `EspScreen` and `ModuleSettingsScreen`
  - `PixelDropdown` - dropdown widget (label + options, opens downward in columns of 6 via `GuiGraphicsExtractor.nextStratum()`); add it before widgets its open list covers so it wins their clicks. Used for presets and the ESP mob filter mode
  - `Theme`, `PixelButton`, `PixelScreen` - pixel UI (`Theme.star` and `PixelScreen.heading` draw the star heading): `Theme` draws bevelled boxes/panel/checker backdrop with `GuiGraphicsExtractor.fill`; `PixelButton` (builder like vanilla `Button`: `bounds`, `gold`, `danger`, `outline`, `stripe`) extends `AbstractButton` and draws in `extractContents`; every screen extends `PixelScreen` and calls `panel(x, y, w, h)` at the end of `init` (backdrop and panel are drawn in `extractBackground`). Use `PixelButton`, not `Button`, in new screens
  - `Config` - persisted settings, JSON at `config/starclient.json`, loaded on init and saved when a settings screen closes
- `src/main/resources/fabric.mod.json` - mod id `starclient`
- `src/main/resources/assets/starclient/lang/en_us.json` - keybind names

## Features and keys
| Module | Default key | Notes |
|---|---|---|
| ESP | unbound | Boxes through walls. Categories (Players, Hostile, Animals, Items) indexed as in `Config.ESP_NAMES`. Mob filter (`Config.espMobMode`: all / only selected / hide selected, `Config.espMobs` entity ids) applies to Hostile and Animals; 'Only selected' ignores the category toggles for mobs |
| Base Finder | unbound | Scans loaded chunks for marker blocks (`BaseFinder.MARKERS`), needs 10+ per chunk |
| Freecam | unbound | Client-only armor stand camera, 10 blocks/s moved per tick and interpolated by the renderer (partial tick); view rotation reads the player's live rotation (`Freecam.Camera` overrides `getViewYRot/getViewXRot`), sprint x2.5, disables `smartCull` so terrain stays visible |
| Chest ESP | unbound | Boxes on the blocks listed in `Config.chestBlocks` (default: chests, barrels, shulkers, hoppers, droppers, furnaces, crafters, brewing stands, beacons, amethyst) within `chestesp.radius` chunks. Block entities are read every pass; other blocks via section scans. Presets and a block picker in its settings |
| Fullbright | unbound | Applies a hidden client-side night vision effect |
| Auto Sprint | unbound | Sets sprint while forward is held |
| Auto Walk | unbound | Holds the forward key |
| Fly | unbound | Sets `mayfly`+`flying` abilities client-side; needs server permission |
| Reach | unbound | Sets block+entity interaction range attributes client-side (3-12, default 6); servers validate, so far hits are usually rejected. Setting `reach.distance` |
| Aim Bot | unbound | Combat tab; eases toward target (20% of the remaining angle per tick, max 8°) toward target |
| Trigger Bot | unbound | Combat tab (renamed from Hit Bot; id `hitbot` and `hitbot.*` settings kept so keybinds and configs survive); auto attack via `gameMode.attack`. Attacks need charge >= `hitbot.charge` and `hitbot.delay` game ticks since the last Trigger Bot/Kill Aura attack (Kill Aura: `killaura.charge`, `killaura.delay`); no randomness in timing or aim |
| Elytra Fly | unbound | Movement tab; while gliding, alternates dive (+35°) until speed > 1.7 b/t and climb (-45°) until speed < 0.9 b/t, easing pitch 6°/tick (all tunable). Preset 'Infinite (no rockets)' = 35°/40°, 2.2/0.7 b/t, from a vanilla-physics simulation (about 150 blocks vertical swing). `elytrafly.rockets` fires a hotbar firework rocket when speed drops below `elytrafly.rocketspeed`. Start gliding yourself (jump twice in air) |
| Kill Aura | unbound | Combat tab; attacks the nearest player/hostile within entity interaction range (Reach extends it) with line of sight, at >=95% charge; no rotation |
| Crits | unbound | Combat tab; before Trigger Bot/Kill Aura attacks (not manual clicks) sends two fake `ServerboundMovePlayerPacket.Pos` hops (+0.0625 y, off-ground) so the server counts a crit. Server ignores crits while you are sprinting |
| Hitboxes | unbound | Combat tab; enlarges every entity hitbox by `hitboxes.expand` blocks per side (0-2, default 0.3). At the start of each tick `Combat.expandHitboxes` re-picks the crosshair entity against the inflated boxes (no mixin), so clicks, Trigger Bot and Kill Aura use it; server still validates real reach |
| Speed | unbound | Movement tab; sets horizontal velocity to 0.45 b/t along held WASD keys (skipped when gliding, flying, swimming or riding). Servers with movement checks rubber-band |
| High Jump | unbound | Movement tab; on take-off with jump held, sets upward velocity to 0.9 b/t (vanilla 0.42) via `ProtocolSanitizer.requestJump`, which skips the vanilla clamp unless `highjump.vanilla` is on |
| Softfall | unbound | Movement tab; while `fallDistance` > 2 and airborne (not gliding/flying) sends `ServerboundMovePlayerPacket.StatusOnly(onGround=true)` each tick so the server resets fall damage |
| Trajectories | unbound | Render tab; `Trajectories.compute` simulates the held bow (draw-scaled), crossbow, trident, pearl, snowball, egg or potion (gravity/drag per item, block clip each tick, 200 steps max); `StarRenderer` draws small boxes along the path and a red box at the impact |
| Jesus | unbound | Movement tab; in water without sneak/jump, sets vertical velocity to hold you at the surface (0.2 b/t up when submerged, 0.1 below the fluid height, 0 on top). Water only; lava still burns |
| Auto Eat | unbound | World tab; when food <= 14 and `needsFood`, picks the highest-nutrition hotbar food (not `Utility.NOT_FOOD`), selects it, `gameMode.useItem` and holds `keyUse` until finished, then restores the slot. Trigger Bot/Kill Aura/Aim Bot pause while eating |
| Anti AFK | unbound | World tab; after 400 idle ticks (no movement or view change, no screen) jumps for 2 ticks and nudges yaw +-10 alternately |
| Sorter | unbound | World tab; one-shot: plans merges then a selection sort of main inventory slots 9-35 (item id, then count desc, empties last) as `PICKUP` click triples, replays 2 per tick via `gameMode.handleContainerInput` (`ContainerInput.PICKUP`), then switches itself off. Toggling sorts the player inventory. Chests, ender chests, barrels and shulker boxes (`ChestMenu`/`ShulkerBoxMenu`) get a "Sort" button added by `SortButton` that sorts the container part (slots 0..size-36) |
| HUD | unbound | Render tab; `Hud` registers a `HudElementRegistry` element before chat that draws enabled module names top-right (widest first, category colour) |
| Panic | unbound | World tab; one-shot: `StarClient.panic` toggles off every enabled module (HUD included) and closes the screen. Never reports ON |
| Anti Bait | unbound | Render tab; every tick diffs each loaded chunk's block entities against what it held before (first sight of a chunk is baseline; unloaded chunks remembered 100 ticks so an unload+resend still diffs). `antibait.min` (default 3) new Chest ESP/Base Finder targets within `antibait.area` (default 16) blocks of each other in one tick, or any single new spawner/trial spawner (`antibait.spawners`), are hidden from Chest ESP, and their chunk from Base Finder. Entity ESP is not affected. Plain blocks (amethyst, ores, glass) are not tracked |
| Diagnostics | unbound | Render tab; see `Diagnostics`. Red = value over its limit (requested values are clamped when `sanitize.enabled`), amber = 80%+. 'Position snap' (observed horizontal step over 3x the speed limit) is the hint for a server correction |
| Auto Tool | unbound | World tab; while attack is held on a block, selects the hotbar slot with the highest `ItemStack.getDestroySpeed` (needs > 1.0, skips tools at or below `autotool.protect` durability, ties keep the current slot), restores the slot on release if you did not change it. Not in creative or while eating |
| Tracers | unbound | Render tab; flat double-sided strips (`StarRenderer.Line`) from 0.6 blocks ahead of the camera to the nearest `tracers.max` targets of the modules that are on: ESP entities (`StarRenderer.espColour`: category toggles, colours, distance, mob filter), Chest ESP blocks, Portal Finder blocks. Nothing is drawn for a module that is off. Thin (`tracers.width`, default 0.003), widening only slightly with distance |
| Zoom | unbound | Render tab; see `Zoom`. `zoom.fov` 30-70 |
| Waypoints | unbound | Render tab; see `Waypoints`. Optional death marker (`waypoints.death`), range, beam height, opacity |
| Auto Totem | unbound | Combat tab; when the offhand is not a totem and is empty (`autototem.empty`) or health <= `autototem.health`, moves the first inventory totem into the offhand (returns the old offhand item to the source slot) |
| Auto Armor | unbound | Combat tab; per slot, equips the inventory piece with the highest armor + 0.5 x toughness (strictly better than worn; needs armor > 0 so elytra and pumpkins are never equipped). `autoarmor.elytra` keeps a worn elytra |
| Auto Respawn | unbound | World tab; on `DeathScreen` calls `player.respawn()` after `autorespawn.delay` ticks |
| Auto Replenish | unbound | World tab; when a hotbar slot empties (and the screen is closed, so manual moves and drags do not count) pulls the same item from inventory slots 9-35; also tops up a stackable stack at or below `autoreplenish.threshold`. Tracking follows hotbar contents while a screen is open |
| Step | unbound | Movement tab; sets the `STEP_HEIGHT` attribute to `step.height` (never below the original), clamped to 1.0 by `ProtocolSanitizer.clampStep`; restored on off |
| Safe Walk | unbound | Movement tab; on the ground, not sneaking or jumping, predicts next tick's movement (velocity plus held-key acceleration, `Movement.inputAccel`) and cancels an axis when the box moved there has no collision within 0.6 blocks below. Assumes normal friction, so ice slides |
| No Slow | unbound | Movement tab; while using an item on the ground adds back the 80% of key acceleration that item use removes, through `ProtocolSanitizer.setVelocity` |
| Velocity | unbound | Movement tab; when `hurtTime` rises, scales horizontal (`velocity.horizontal`, default 0%) and upward (`velocity.vertical`, default 100%) velocity. Runs at end of tick, so the first tick of knockback still applies |
| Portal Finder | unbound | Bases tab; see `PortalFinder` |
| Sign Reader | unbound | Bases tab; see `SignReader` |
| Auto Log | unbound | Combat tab; disconnects (`connection.getConnection().disconnect`) when health + absorption minus predicted damage is at or below `autolog.health`. Prediction = health lost over the last `autolog.window` ticks plus fall damage from `fallDistance` and the drop to the ground (none over water). Skipped while a totem is held (`autolog.totem`); turns itself off after logging out |
| Attribute Swap | unbound | Combat tab; with slot `attrswap.main` selected, a fresh attack press on an entity (START tick, before the click) or a Trigger Bot/Kill Aura attack (`AttributeSwap.around`) selects `attrswap.second` for the hit, then returns to main after `attrswap.back` ticks |
| Auto Water | unbound | Movement tab; falling past `autowater.fall` with a water bucket in the hotbar: selects it, turns straight down (through `ProtocolSanitizer.limitTurn`), uses it once the ground is in block reach, then optionally scoops the water after landing and restores the slot. Not in the Nether |
| Automine | unbound | World tab; holds `keyAttack` while the crosshair is on a block (pauses below `automine.minhealth`, with a screen open, in Freecam or while eating) |
| Chat Macro | unbound | World tab; sends the `chatmacro.messages` text (messages separated by `\|`, a leading `/` sends a command) every `chatmacro.interval` s varied by up to `chatmacro.jitter` % either way, in order or at random |
| Auto TPA | unbound | World tab; sends `/tpa <autotpa.player>` (or `/tpahere` with `autotpa.here`) every `autotpa.cooldown` s until a 8+ block position jump (tpa) or the player appearing within 6 blocks (tpahere) |
| Clipper | unbound | Render tab; one-shot like Panic (never reports ON; key = save). While `clipper.record` is on, a downscaled frame (`Screenshot.takeScreenshot`) is PNG-encoded on a worker thread every `clipper.interval` ticks into a ring of `clipper.seconds`; the key writes `clips/<time>/frame_NNNN.png` and, if ffmpeg is on the PATH, `clip.mp4`; otherwise (or with `clipper.ffmpeg` off) a pure-Java looping `clip.gif` (256 colours, ImageIO GIF writer), and the PNGs are deleted on success |
| Mace Aura | unbound | Combat tab; in `Combat.maceAura`: airborne (not gliding, flying, in water, climbing or riding) with `fallDistance` >= `maceaura.fall` (default 1.5, the vanilla smash threshold) and a mace in the hotbar, selects it, attacks the nearest player/hostile in range (`maceaura.range`, 0 = Reach) with line of sight at >= `maceaura.charge`, then restores the slot. Fall distance is never faked |
| Menu | Right Shift | Opens `ModulesScreen` |

Every module has a gear button opening its settings (Fullbright: `fullbright.maxlight`; Auto Walk: `autowalk.sneak`, `autowalk.minhealth`; HUD: `hud.bar` pixel backing with a category colour edge). Module settings live in `Settings` (defaults and ranges are the old hard-coded constants; Crits hop is now 0.06). Profiles also save the settings map, mob filter and Chest ESP blocks.

Module keys are unbound by default (menu key is Right Shift). All keys are rebindable from the menu (saved via `Options`) or Options > Controls.
Module order is fixed by `Module.ALL`: 0 ESP, 1 Base Finder, 2 Freecam, 3 Chest ESP, 4 Fullbright, 5 Auto Sprint, 6 Auto Walk, 7 Fly, 8 Reach, 9 Aim Bot, 10 Trigger Bot, 11 Elytra Fly, 12 Kill Aura, 13 Crits, 14 Hitboxes, 15 Speed, 16 High Jump, 17 Softfall, 18 Trajectories, 19 Jesus, 20 Auto Eat, 21 Anti AFK, 22 Sorter, 23 HUD, 24 Panic, 25 Anti Bait, 26 Diagnostics, 27 Auto Tool, 28 Tracers, 29 Zoom, 30 Waypoints, 31 Auto Totem, 32 Auto Armor, 33 Auto Respawn, 34 Auto Replenish, 35 Step, 36 Safe Walk, 37 No Slow, 38 Velocity, 39 Portal Finder, 40 Sign Reader, 41 Auto Log, 42 Attribute Swap, 43 Auto Water, 44 Automine, 45 Chat Macro, 46 Auto TPA, 47 Clipper, 48 Mace Aura.

## Build
- `./gradlew build` -> jar in `build/libs/` (ignore `*-sources.jar`)
- CI: `.github/workflows/build.yml` builds on every push/PR and uploads the jar as artifact `star-client`
- The sandbox cannot reach Fabric/Mojang Maven, so it cannot compile locally; push and read the failing job log from the Actions run

## 26.2 API notes (learned from compile errors)
- Rendering: `Screen.extractBackground/extractRenderState(GuiGraphicsExtractor, mx, my, a)`, `AbstractWidget.extractWidgetRenderState`, `AbstractButton.extractContents` (abstract) and `onPress(InputWithModifiers)`. `GuiGraphicsExtractor`: `fill(x1,y1,x2,y2,argb)`, `outline(x,y,w,h,argb)`, `text(font, Component, x, y, argb, shadow)`
- No `LocalPlayer.displayClientMessage`; use `mc.gui.setOverlayMessage(Component, false)`
- No `StringWidget.alignCenter()`; position the widget manually using `font.width(title)`
- Screens use `keyPressed(KeyEvent)` with `event.key()`. Custom widgets override `extractWidgetRenderState`/`extractContents`; mouse events carry `event.x()`/`event.y()` (`MouseButtonEvent`)
- 26.2: current screen is `mc.gui.screen()` / `mc.gui.setScreen(...)` (not `mc.screen`/`mc.setScreen`); overlay message is `mc.gui.hud.setOverlayMessage`; hidden HUD is `mc.gui.hud.isHidden()`; `ChatFormatting.getColor` is gone (`Module.Category.rgb` holds the RGB); the main render target is `mc.gameRenderer.mainRenderTarget()`
- 26.2 through-walls boxes: `StarRenderer` uses `StagedVertexBuffer` (`appendDraw`, `getVertexBuilder`, `upload`, `getExecuteInfo`, `endFrame`) and `RenderPass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)`, following the Fabric docs `CustomRenderPipeline` example
- These names change between Minecraft versions, so when bumping `minecraft_version` expect compile errors in `StarClient`, `StarRenderer` and the screens first

## Conventions
- Mojang-mapped names (Minecraft 26.2 is unobfuscated)
- When bumping Loom, check the required Gradle version and update `gradle/wrapper/gradle-wrapper.properties`
- Mod id, package and lang keys must stay in sync (`starclient`)
- New module: append to `Module.ALL` (+ index constant), extend `isOn`/`toggle`, add a lang entry `key.starclient.<id>`
- Client chunks: `mc.level.getChunk(cx, cz, ChunkStatus.FULL, false)` is a `LevelChunk` when loaded; `LevelChunk.getBlockEntities()` is the live map (no copy, unlike `getBlockEntitiesPos()`)
- Tabs for indentation, matching existing code

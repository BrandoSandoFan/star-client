# Star Client

Client-side Fabric mod for Minecraft 26.1 with ESP, chest ESP, base finder, freecam, fullbright, auto sprint and auto walk.

## Install
1. Install Fabric Loader 0.19.3+ and Java 25.
2. Put the mod jar and Fabric API (0.145.1+26.1 or newer) in `.minecraft/mods`.

Get the jar from the latest successful run in the Actions tab (artifact `star-client`).

## Use
- **Right Shift**: open the module menu (toggle modules, rebind keys, ESP settings)
- Module keys are unbound by default; bind them from the menu or Options > Controls

ESP colours, categories, opacity and range are set in the menu and saved to `config/starclient.json`.

## Build
`./gradlew build` (Java 25). The jar is written to `build/libs/`.

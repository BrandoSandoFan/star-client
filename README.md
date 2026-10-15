# Star Client

Client-side Fabric mod for Minecraft 26.1 with ESP, base finder and freecam.

## Install
1. Install Fabric Loader 0.19.3+ and Java 25.
2. Put the mod jar and Fabric API (0.145.1+26.1 or newer) in `.minecraft/mods`.

Get the jar from the latest successful run in the Actions tab (artifact `star-client`).

## Use
- **Right Shift**: open the module menu (toggle modules, rebind keys, ESP settings)
- **R**: ESP, **B**: Base Finder, **F**: Freecam

ESP colours, categories, opacity and range are set in the menu and saved to `config/starclient.json`.

## Build
`./gradlew build` (Java 25). The jar is written to `build/libs/`.

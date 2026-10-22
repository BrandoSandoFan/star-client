# Load Test Toolkit

Mineflayer-based bot swarm for stress-testing Minecraft servers.

## Prerequisites

- Node.js (download from https://nodejs.org)

## Usage

**Windows:**
```
run-loadtest.bat
```

**macOS/Linux:**
```
npm install mineflayer
node loadtest.js
```

## What It Does

1. Asks for server address, port, auth mode, and max bot count
2. Confirms you own/admin the server and it's currently empty
3. Ramps up bots in staggered stages (5 → 10 → 20 → 35 → 50 by default)
4. Each bot idles with randomized movement (turns, jumps, short walks)
5. Bot #0 polls `/tps` every 10 seconds (servers with Carpet/Paper/Spigot support it)
6. Logs all events with timestamps

## Configuration

Edit the tunables at the top of `loadtest.js`:

- `RAMP_BASE` - bot-count stages (default: [5, 10, 20, 35, 50])
- `STAGE_INTERVAL_MS` - hold time at each stage (default: 30 seconds)
- `SPAWN_STAGGER_MS` - delay between individual logins (default: 250ms)
- `MOVE_INTERVAL_MS` - movement interval range (default: 1.5–4 seconds)
- `TPS_POLL_INTERVAL_MS` - `/tps` poll frequency (default: 10 seconds)

## Safety

⚠️ **Only run against servers you own/admin, with explicit permission, while empty.** This generates real connection load that affects all connected players.

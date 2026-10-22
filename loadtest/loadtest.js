// Mineflayer load-test swarm.
//
// Only run this against a server you own/admin, or one whose owner/admin has
// explicitly authorized the test, and only while it's empty (no other real
// players connected) — this generates real connection load that affects
// everyone else on the server too.
//
// First-time setup (once):
//   npm install mineflayer
//
// Then just run it (no file editing needed — it asks you for everything):
//   node loadtest.js
//
// What it does:
//   - Asks for the server address/port and bot count, with a confirmation
//     that you have the owner's okay and the server is currently empty
//   - Spawns bots in staggered batches (avoids a login-packet burst that
//     looks like the thing we're trying to measure, not cause)
//   - Each bot idles with light randomized movement (turn, jump, walk short
//     bursts) to approximate real player packet patterns without needing
//     real pathfinding
//   - Periodically sends /tps (if the server supports it — vanilla doesn't;
//     Paper/Spigot/Fabric+Carpet do) via chat and logs the raw reply
//   - Ramps bot count over several stages so you can correlate degradation
//     with concurrent-connection count instead of guessing from one big jump

const mineflayer = require('mineflayer')
const readline = require('readline')

const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
function ask(question) {
	return new Promise((resolve) => rl.question(question, (answer) => resolve(answer.trim())))
}

// ---- Tunables (fine as defaults; only the prompts above change per run) --
const RAMP_BASE = [5, 10, 20, 35, 50]   // cumulative bot-count stages, scaled to your max below
const STAGE_INTERVAL_MS = 30_000        // time to hold at each stage before adding more
const SPAWN_STAGGER_MS = 250            // delay between individual bot logins within a stage
const MOVE_INTERVAL_MS = [1500, 4000]   // random interval range for movement actions
const TPS_POLL_INTERVAL_MS = 10_000     // how often bot #0 asks for /tps

// ---- State --------------------------------------------------------------
const bots = []
let stageIndex = 0
let RAMP_STAGES = RAMP_BASE

function randRange([min, max]) {
	return min + Math.random() * (max - min)
}

function log(...args) {
	console.log(`[${new Date().toISOString()}]`, ...args)
}

// ---- Bot behavior ---------------------------------------------------------
function attachBehavior(bot, index) {
	bot.once('spawn', () => {
		log(`bot ${index} spawned`)

		function scheduleNextMove() {
			const delay = randRange(MOVE_INTERVAL_MS)
			setTimeout(() => {
				if (!bot.entity) return // disconnected
				doRandomMove(bot)
				scheduleNextMove()
			}, delay)
		}

		function doRandomMove(bot) {
			const yaw = Math.random() * Math.PI * 2
			const pitch = (Math.random() - 0.5) * 0.5
			bot.look(yaw, pitch, false)

			const action = Math.random()
			if (action < 0.3) {
				bot.setControlState('jump', true)
				setTimeout(() => bot.setControlState('jump', false), 250)
			} else if (action < 0.7) {
				bot.setControlState('forward', true)
				setTimeout(() => bot.setControlState('forward', false), randRange([300, 900]))
			}
		}

		scheduleNextMove()

		if (index === 0) {
			setInterval(() => {
				if (bot.entity) bot.chat('/tps')
			}, TPS_POLL_INTERVAL_MS)

			bot.on('message', (jsonMsg) => {
				const text = jsonMsg.toString()
				if (/tps/i.test(text)) log('server tps reply:', text)
			})
		}
	})

	bot.on('kicked', (reason) => log(`bot ${index} kicked:`, reason))
	bot.on('error', (err) => log(`bot ${index} error:`, err.message))
	bot.on('end', () => log(`bot ${index} disconnected`))
}

function spawnBot(index, host, port, authMode) {
	const bot = mineflayer.createBot({
		host,
		port,
		username: `loadtest_${index}`,
		auth: authMode, // 'offline' or 'microsoft'
		version: false,
	})
	attachBehavior(bot, index)
	bots.push(bot)
}

// ---- Ramp control ---------------------------------------------------------
function runStage(host, port, authMode) {
	if (stageIndex >= RAMP_STAGES.length) {
		log(`ramp complete, holding at ${bots.length} bots. Ctrl+C to stop.`)
		return
	}

	const stageTarget = RAMP_STAGES[stageIndex]
	const toSpawn = stageTarget - bots.length
	log(`stage ${stageIndex + 1}/${RAMP_STAGES.length}: ramping to ${stageTarget} bots (+${toSpawn})`)

	let spawned = 0
	const spawnNext = () => {
		if (spawned >= toSpawn) {
			stageIndex++
			setTimeout(() => runStage(host, port, authMode), STAGE_INTERVAL_MS)
			return
		}
		spawnBot(bots.length, host, port, authMode)
		spawned++
		setTimeout(spawnNext, SPAWN_STAGGER_MS)
	}
	spawnNext()
}

process.on('SIGINT', () => {
	log('shutting down, disconnecting bots...')
	for (const bot of bots) {
		try { bot.quit() } catch (_) { /* already gone */ }
	}
	process.exit(0)
})

// ---- Interactive setup ----------------------------------------------------
async function main() {
	console.log('--- Minecraft server load test ---\n')

	const owned = await ask('Do you own/admin this server, OR has its owner/admin explicitly okayed this test? (yes/no): ')
	if (!/^y/i.test(owned)) {
		console.log('\nStopping. Only run this with the owner/admin\'s explicit okay.')
		rl.close()
		return
	}

	const empty = await ask('Is the server currently empty of other real players right now? (yes/no): ')
	if (!/^y/i.test(empty)) {
		console.log('\nStopping. Load test traffic will affect everyone currently connected — run this when the server is empty.')
		rl.close()
		return
	}

	const host = await ask('Server address (e.g. play.example.com or an IP): ')
	if (!host) {
		console.log('No address given, stopping.')
		rl.close()
		return
	}
	const portInput = await ask('Server port (press Enter for default 25565): ')
	const port = portInput ? parseInt(portInput, 10) : 25565

	const onlineModeInput = await ask('Is the server in online-mode (Mojang/Microsoft auth)? (yes/no, default no): ')
	const authMode = /^y/i.test(onlineModeInput) ? 'microsoft' : 'offline'
	if (authMode === 'microsoft') {
		console.log('  Note: with online-mode on, each bot needs a real Microsoft account to log in through')
		console.log('  a device-code prompt the first time. For a quick test, ask the owner to temporarily')
		console.log('  set online-mode=false in server.properties instead, then switch it back afterward.')
	}

	const maxInput = await ask('Max concurrent bots to ramp up to (press Enter for default 50): ')
	const maxBots = maxInput ? parseInt(maxInput, 10) : 50
	RAMP_STAGES = RAMP_BASE.filter((n) => n < maxBots).concat([maxBots])

	rl.close()

	log(`starting load test against ${host}:${port}, ramp stages: ${RAMP_STAGES.join(' -> ')}`)
	runStage(host, port, authMode)
}

main()

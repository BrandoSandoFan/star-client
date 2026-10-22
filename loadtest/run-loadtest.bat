@echo off
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
	echo Node.js is not installed. Download it from https://nodejs.org, run the installer, then double-click this file again.
	pause
	exit /b 1
)

if not exist "node_modules\mineflayer" (
	echo Installing mineflayer, one-time setup...
	call npm install mineflayer
)

node loadtest.js
pause

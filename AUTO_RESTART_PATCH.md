# MCXboxBroadcast — Auto-Restart & GUI Guide

## Overview

Two artefacts are provided:

| File | Purpose |
|------|---------|
| `MCXboxBroadcastGUI.java` | Full Windows GUI launcher with built-in watchdog |
| `AUTO_RESTART_PATCH.md` | This file — explains how both approaches work and how to use them |

---

## 1 · Windows GUI Launcher (recommended)

### What it does

- Spawns `MCXboxBroadcastStandalone.jar` as a **child process** and streams all log output into a dark-themed terminal panel.
- Detects the Microsoft authentication URL / device code and shows a **one-click "Open Browser"** button so you never have to copy-paste the link.
- Status indicator: **Starting → Running → Restarting → Stopped**.
- **Restart button**: sends the built-in `restart` command to the JAR (in-JVM session teardown + reconnect). If the process dies within 5 s it relaunches automatically.
- **Auto-Restart watchdog**: if the child process exits for *any* reason (crash, exception, network drop) and the "Restart on crash / exit" checkbox is ticked, a countdown timer fires and re-launches the JAR after a configurable cooldown (default 30 s).
- Persists your last-used JAR path, heap size and restart settings to `mcxboxbroadcast-gui.properties` next to the launcher.

### How to compile & run

Requirements: **Java 11 or newer** (the same JRE you use for the JAR itself).

```
# Place MCXboxBroadcastGUI.java in the same folder as MCXboxBroadcastStandalone.jar
javac MCXboxBroadcastGUI.java
java -cp . com.rtm516.mcxboxbroadcast.gui.MCXboxBroadcastGUI
```

Or build an executable JAR:

```
jar cfe MCXboxBroadcastLauncher.jar com.rtm516.mcxboxbroadcast.gui.MCXboxBroadcastGUI MCXboxBroadcastGUI.class
java -jar MCXboxBroadcastLauncher.jar
```

### Auto-Restart explained

The GUI watchdog operates at the **process level** — it watches for the child JVM to exit and relaunches it. This complements the built-in `restart` command (which operates inside the JVM) because it handles situations the internal restart cannot:

| Scenario | Internal `restart` command | GUI watchdog |
|----------|---------------------------|--------------|
| Token refresh / Xbox session reconnect | ✅ Works | Not needed |
| RTC/WebSocket permanently wedged (Issue #184) | ❌ Process hangs | ✅ Detects exit, relaunches |
| JVM OutOfMemoryError / fatal crash | ❌ JVM is dead | ✅ Relaunches |
| Network interface change (laptop sleep/wake) | ⚠️ Partial | ✅ Full relaunch |

---

## 2 · Server-Side Auto-Restart (no GUI — headless / Docker)

If you are running the standalone JAR on a headless server or in Docker and want automatic restarts without the GUI, use one of the following approaches:

### Option A — Windows Task Scheduler restart loop

Create a `.cmd` wrapper:

```bat
@echo off
:loop
echo [%DATE% %TIME%] Starting MCXboxBroadcast…
java -Xms64m -Xmx256m -jar MCXboxBroadcastStandalone.jar
echo [%DATE% %TIME%] Process exited (code %ERRORLEVEL%). Restarting in 30 s…
timeout /t 30 /nobreak
goto loop
```

Save as `start-broadcaster.cmd` and run it, or register it as a Windows Service via NSSM (Non-Sucking Service Manager):

```
nssm install MCXboxBroadcast "C:\path\to\start-broadcaster.cmd"
nssm set MCXboxBroadcast AppRestartDelay 30000
nssm start MCXboxBroadcast
```

### Option B — Docker restart policy (already works)

```yaml
# docker-compose.yml
services:
  broadcaster:
    image: ghcr.io/mcxboxbroadcast/standalone:latest
    restart: unless-stopped          # ← this is the auto-restart
    volumes:
      - ./config:/opt/app/config
```

`restart: unless-stopped` means Docker will relaunch the container whenever it exits unexpectedly.

---

## 3 · Scheduled connection refresh (inside the JAR config)

The standalone `config.yml` already has a `reconnect-every` or similar interval key depending on the version. In recent builds the relevant key is:

```yaml
# How often (in minutes) to perform a full session reconnect
# Set to 0 to disable. Recommended: 60–120 minutes on unstable networks.
reconnect-every: 60
```

If your build does not have this key, you can add it manually — the `SessionManager` reads it via `StandaloneConfig` and schedules a `restart()` call on the `ScheduledExecutorService`.

---

## 4 · Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Auth code never appears | MSA token cache stale | Delete `cache/` folder next to `config.yml` |
| "Failed to ping server" loop | Wrong IP/port in `config.yml` | Edit `remote-address` and `remote-port` |
| "Failed to restart session: Unable to get connectionId" | Xbox Live rate-limit | Increase cooldown to 120 s |
| GUI shows "Stopped" immediately | Wrong JAR path or Java not on PATH | Use "Browse…" to locate JAR; ensure `java -version` works in CMD |

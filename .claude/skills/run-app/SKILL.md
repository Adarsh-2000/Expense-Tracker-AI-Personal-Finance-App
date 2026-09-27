---
name: run-app
description: Launch and drive the Ledger Noir expense tracker — the FastAPI backend on the real emu.db plus the Android app on a physical/wireless device — to see changes working end to end. Use when asked to run, launch, screenshot, or verify the expense-tracker app on a device.
---

# Running Ledger Noir (backend + Android app) on a device

This launches the **real app** against the **real data** in `backend/emu.db` and
drives it on a physical Android device over **wireless adb**. It was verified on a
Samsung Galaxy S22 (`SM-S901E`, Android 16).

Two processes must both be up: the **FastAPI backend** (serves the data) and the
**Android app** (talks to the backend via an `adb reverse` tunnel).

## Environment (these are NOT on PATH by default — set them every session)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK 21, bundled with Android Studio
ADB=~/Library/Android/sdk/platform-tools/adb                                     # adb lives in the SDK, not on PATH
APK=app/build/outputs/apk/debug/app-debug.apk                                    # relative to android/
PKG=com.ledgernoir.tracker
```

There is no system JDK (`/usr/bin/java` is a stub) and no `ANDROID_HOME`. Gradle
reads `android/local.properties` (`sdk.dir=~/Library/Android/sdk`) for the SDK.

## 1. Connect the device (wireless adb)

The phone must have **Developer options → Wireless debugging = ON**, on the same
Wi-Fi as this Mac. adb does not auto-show it; discover + connect explicitly:

```bash
$ADB kill-server; $ADB start-server
$ADB mdns services            # prints e.g.  adb-XXXX  _adb-tls-connect._tcp  192.168.1.2:38337
$ADB connect 192.168.1.2:38337   # use the IP:PORT from the mdns line
$ADB devices -l               # must show the device as `device` (not unauthorized/offline)
```

If mdns shows nothing: the phone isn't broadcasting — re-toggle Wireless debugging,
or ask the user for the IP:Port shown on the phone's Wireless-debugging screen. The
first connection from a new Mac may need `adb pair <ip>:<pairport>` with the phone's
pairing code (a *different* port than the connect port).

Set `DEV` to the connected serial for the rest of the steps:
```bash
DEV=192.168.1.2:38337    # or `$ADB devices` serial
```

## 2. Backend on the REAL data (port 8000)

The app's findings/insights come from `backend/emu.db` (~8.6k real transactions).
Detector "Findings" (Money Radar) are written by a scan — pre-populate them so the
app has data to show, then serve the same DB.

```bash
cd backend
cp emu.db emu.db.bak.$(date +%s)        # emu.db is real user data — always back up first

# Pre-scan: populate Finding rows (idempotent; safe to re-run).
ET_DATABASE_URL=sqlite+aiosqlite:///./emu.db .venv/bin/python -c "
import asyncio, os
os.environ['ET_DATABASE_URL']='sqlite+aiosqlite:///./emu.db'
from app.db import Base, new_session_factory
from app.enrichment.money_radar import MoneyRadarService
from app.repositories import FindingRepository
async def m():
    e,S=new_session_factory(os.environ['ET_DATABASE_URL'])
    async with e.begin() as c: await c.run_sync(Base.metadata.create_all)
    async with S() as s:
        n=await MoneyRadarService(s).scan(); await s.commit()
        print('findings:', len(await FindingRepository(s).list_active()))
    await e.dispose()
asyncio.run(m())"

# Serve it (background). 0.0.0.0 so the reverse tunnel reaches it.
ET_DATABASE_URL=sqlite+aiosqlite:///./emu.db .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 > /tmp/et_backend.log 2>&1 &
sleep 3
curl -s -o /dev/null -w "docs=%{http_code}\n" http://localhost:8000/docs   # expect 200
curl -s http://localhost:8000/money-radar/summary | head -c 200            # expect JSON with new_count
```

Stop later with `pkill -f "uvicorn app.main"`.

Notes:
- No auth/PIN is configured in `.env.dev`, so read endpoints respond without a token.
  The app itself signs in with Google; an existing install keeps its 30-day session.
- A bug in a detector won't break ingestion (scan is best-effort), but it can 500 the
  explicit `POST /money-radar/scan`. The in-process pre-scan above surfaces such errors
  directly — read them.

## 3. Bridge the phone to the backend

`adb reverse` maps the **phone's** `localhost:8000` to this Mac's `localhost:8000`
(works over wireless). The app must therefore be pointed at `http://localhost:8000`.

```bash
$ADB -s $DEV reverse tcp:8000 tcp:8000
$ADB -s $DEV reverse --list                  # confirm: host... tcp:8000 tcp:8000
```

App base URL: stored in DataStore (`TokenStore`), **default `http://10.0.2.2:8000`**
(emulator alias — does NOT work on a real phone). For a physical device it must be
`http://localhost:8000` (or `127.0.0.1`). All three (`10.0.2.2`, `localhost`,
`127.0.0.1`) are allow-listed for cleartext in `res/xml/network_security_config.xml`.
If the app can't reach the backend, set the URL in the app: **Settings → base URL →
`http://localhost:8000` → Save**. An install that kept its data retains the saved URL.

## 4. Build, install, launch

```bash
cd android
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -3   # expect BUILD SUCCESSFUL
$ADB -s $DEV install -r $APK                                  # -r keeps app data (saved URL + Google sign-in)
$ADB -s $DEV reverse tcp:8000 tcp:8000                        # re-assert (reinstall can drop it)
$ADB -s $DEV shell am start -n $PKG/.MainActivity             # `am start` is more reliable than monkey
```

`compileDebugKotlin` is the fast inner loop (~1-5s) for Kotlin-only changes;
`assembleDebug` is the full APK.

## 5. Drive it — screenshot, read, tap

```bash
$ADB -s $DEV exec-out screencap -p > /tmp/s.png      # then Read /tmp/s.png and LOOK at it
```

**Do not guess tap coordinates** — the screen is 1080x2340 and eyeballing fractions
is unreliable (cost real time here). Dump the view tree and tap exact element centers:

```bash
$ADB -s $DEV shell uiautomator dump /sdcard/ui.xml && $ADB -s $DEV pull /sdcard/ui.xml /tmp/ui.xml
# grep /tmp/ui.xml for text="..." bounds="[x1,y1][x2,y2]"; tap the center:
$ADB -s $DEV shell input tap <cx> <cy>
```

Gotchas observed:
- Swipes that start/end near the **bottom edge** trigger the system **back gesture**
  and pop you out of the screen (or to the launcher). Keep `input swipe` within a
  mid-screen band, e.g. `input swipe 540 1500 540 800 500`.
- `monkey ... LAUNCHER` sometimes resumes a stale activity or stays on the launcher;
  prefer `am start -n $PKG/.MainActivity`.
- Bottom-nav tabs (Home/Txns/Insights/Savings/Invest/Bills) sit at y≈2124.

### Smoke path for Money Radar (the "hidden money" feature)
1. Dashboard shows the **"You might've missed"** strip when there are *new* findings.
   Tap it (find "See all →" via uiautomator dump) → Money Radar screen.
2. Or bottom-nav **Insights** → the **Money Radar** tile (4th).
3. The screen shows a severity-count row + findings grouped by severity with Dismiss.
4. **Seen lifecycle:** opening the screen calls `markRadarSeen()`, so `new_count`
   drops to 0 and the dashboard strip / tile badge clear. To see the badge again,
   re-run the pre-scan after flipping statuses, or dismiss/ingest to create new ones.
   Verify server-side: `curl -s localhost:8000/money-radar/summary`.

## Quick teardown
```bash
pkill -f "uvicorn app.main"           # stop backend
$ADB -s $DEV reverse --remove-all     # drop the tunnel
```

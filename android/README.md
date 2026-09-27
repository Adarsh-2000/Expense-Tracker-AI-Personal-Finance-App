# Ledger Noir — Native Android Companion App

A lean native Kotlin/Jetpack Compose Android app that (1) silently forwards bank
SMS messages to the Ledger Noir FastAPI backend and (2) provides a read-only
companion dashboard (Lock, Dashboard, Transactions + manual entry, Budget,
Settings) styled to match the web UI.

> Full design and rationale: see
> [`../docs/superpowers/plans/2026-06-02-android-app.md`](../docs/superpowers/plans/2026-06-02-android-app.md).
> This README is the quick-start; the plan is the source of truth.

---

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer.
- **JDK 17** — bundled with Android Studio; you do not need to install it
  separately.
- **Android SDK API 34** (compile/target) with **API 26** minimum. Android Studio
  installs the required SDK packages on first Gradle sync.
- An **Android Virtual Device (AVD)** running API 26+ (a Pixel 6 / API 34 image is
  recommended).

> **Gradle wrapper:** the binary `gradle/wrapper/gradle-wrapper.jar` is intentionally
> not committed (it cannot be authored as text). Android Studio regenerates it
> automatically on import. See [`WRAPPER_NOTE.md`](WRAPPER_NOTE.md) for details, or
> run `gradle wrapper --gradle-version 8.6` once and `chmod +x gradlew`.

---

## Open, build, and run

1. **Open the project.** In Android Studio: **File → Open** → select the `android/`
   folder. Let Gradle sync (it downloads Gradle 8.6, the Android SDK packages, and
   all dependencies). Expected: *BUILD SUCCESSFUL*.
2. **Create / start an AVD.** **Tools → Device Manager → Create Device** → Pixel 6 →
   system image **API 34 (x86_64)** → Finish → start the emulator.
3. **Run.** Select the emulator and click **Run ▶**. The app installs and launches.
   - If a PIN is configured on the backend (`ET_APP_PIN`), the **Lock** screen
     appears; otherwise the **Dashboard** loads directly.
4. **Grant SMS permissions.** On first launch Android prompts for `RECEIVE_SMS` and
   `READ_SMS` — tap **Allow**. The Dashboard SMS warning banner then disappears.

---

## Emulator host note (backend URL)

The Android emulator maps the special host address **`10.0.2.2`** to your laptop's
`localhost`. The app therefore defaults its backend base URL to:

```
http://10.0.2.2:8000
```

Cleartext HTTP is permitted **only** for local dev hosts (`10.0.2.2`, `localhost`,
`127.0.0.1`) via `@xml/network_security_config`; all other hosts require HTTPS. You
can change the URL at runtime in the **Settings** screen — `TokenStore.setBaseUrl`
rejects cleartext `http://` to any non-private host. See **Security notes** below.

Start the backend on your laptop before running the app:

```bash
cd backend && python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Verify reachability from the emulator browser: navigate to
`http://10.0.2.2:8000/accounts` — you should see a JSON array (e.g. `[]`).

---

## Send a test SMS via emulator Extended Controls

1. In the running emulator, click the **…** (Extended Controls) button.
2. Go to the **Phone** tab → **Send Message**.
3. **From:** `AX-AXISBK`
4. **Message:**
   ```
   INR 299 spent on AXIS BANK Credit Card no. XX1234 at NETFLIX on 02-06-2026. Avl Lmt INR 49701
   ```
5. Click **Send Message**.

Only senders whose normalized ID is an **exact** match for a whitelisted bank ID
(e.g. **AXISBK · IDFCFB · BOBTXN** and enumerated variants) are forwarded;
everything else (e.g. `VD-HDFCBK`, or look-alikes like `BOBSPAM`) is dropped
silently on-device by `SmsFilter`. There is no prefix matching.

---

## End-to-end smoke check

1. Send the test SMS above from `AX-AXISBK`.
2. **Backend log:** you should see a `POST /ingest/sms` request (201/202).
3. **App:** open the **Transactions** tab — the NETFLIX transaction appears (it
   reloads on each visit via `LaunchedEffect`).
4. **Web dashboard:** confirm the same transaction shows up in the React web app.
5. **Negative case:** send another SMS from `VD-HDFCBK` — **no** new transaction
   should appear anywhere (correctly filtered out).
6. **Lock flow:** set `ET_APP_PIN=1234` in `backend/.env`, restart uvicorn, tap
   **Settings → Lock Now (clear token)**, relaunch the app, enter `1234`, tap
   **Unlock** → the Dashboard appears.

---

## Unit tests

`SmsFilter` is pure Kotlin (no Android deps). Run its tests with no emulator:

- In Android Studio: right-click
  `app/src/test/java/com/ledgernoir/tracker/sms/SmsFilterTest.kt` → **Run**.
- Or terminal (requires a local JDK 17):
  ```bash
  cd android
  ./gradlew :app:testDebugUnitTest --tests "com.ledgernoir.tracker.sms.SmsFilterTest"
  ```
  Expected: `BUILD SUCCESSFUL` — 16 tests, 0 failed.

---

## Security notes

This is a single-user **local dev** companion app. A few intentional trade-offs:

- **Cleartext is scoped, not global.** HTTP is allowed only to local dev hosts
  (`10.0.2.2`, `localhost`, `127.0.0.1`) via `@xml/network_security_config`; the
  legacy global `android:usesCleartextTraffic="true"` has been removed. For real
  remote use, **serve the backend over HTTPS** and point the app at the `https://`
  URL. `TokenStore.setBaseUrl` enforces this: it rejects cleartext `http://` to any
  non-private host (only loopback / RFC-1918 LAN ranges may use plain HTTP).
- **Bearer token storage.** The token lives in plain DataStore (`ledger_prefs`), is
  **not** encrypted, and is excluded from device backup (`android:allowBackup="false"`).
  For production, store it in **EncryptedSharedPreferences** or the **Android Keystore**.
- **HTTP logging.** The OkHttp logging interceptor is gated by `BuildConfig.DEBUG`
  (`BASIC` in debug, `NONE` in release) and redacts the `Authorization` header, so
  tokens, the unlock PIN, and SMS bodies are never logged.

## Project layout

```
android/
├── app/
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/ledgernoir/tracker/
│   │   ├── MainActivity.kt          (single activity + runtime SMS permission)
│   │   ├── LedgerNoirApp.kt         (Application: holds TokenStore + Repository)
│   │   ├── sms/SmsFilter.kt         (pure-Kotlin whitelist, unit-tested)
│   │   ├── sms/SmsReceiver.kt       (forwards bank SMS to /ingest/sms)
│   │   ├── data/                    (TokenStore, AuthInterceptor, ExpenseApi, ExpenseRepository)
│   │   └── ui/                      (theme, AppShell + NavHost, 5 screens)
│   ├── src/main/res/                (strings, theme, launcher icon)
│   ├── src/test/.../SmsFilterTest.kt
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts                 (root)
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml        (version catalog)
└── gradle/wrapper/gradle-wrapper.properties
```

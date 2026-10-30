# Teledrive – Telegram as your cloud storage (phone-number login, no bot)

You sign in **as yourself** (phone number → login code → optional 2-step password), exactly like Telegram Desktop.
Files are stored in a private channel called **“Teledrive Storage”** that the app creates in *your* account.

* `web/index.html` – the web app. One self-contained file (no server). Open it in a browser or host it anywhere.
* `android/` – Kotlin/Compose app with scheduled auto-backup (photos, videos, audio, any folder).
* `engine/` – the shared Telegram client (GramJS / MTProto). Bundled into both apps.

## One-time: get a free API ID / API hash (1 minute)
Telegram requires **every** non-bot app – including Telegram Desktop clones – to use its own API credentials.
1. Go to <https://my.telegram.org/apps> and log in with your phone number.
2. Create an application (any name, e.g. "Teledrive").
3. Copy **api_id** and **api_hash**. They identify the *app*, they are not your password.

## Web app
Open `web/index.html` → paste API ID/hash → enter your phone number → enter the code Telegram sends you (→ 2-step password if enabled).
Then drag & drop files, make folders, search, preview, rename/move, multi-select download/delete.
Settings shows a **setup code** (API ID/hash only) you can paste into the Android app.

## Android app (Android 10+)
* **No Android Studio?** Push this folder to GitHub → Actions → *Build Android APK* → download the artifact → install.
* **Android Studio:** open `android/`, press Run.

In the app: API ID/hash (or paste the setup code) → phone number → code (→ password). Then choose
**what to back up** (Photos / Videos / Audio / any folder), **how often** (15 min … 24 h), conditions (Wi-Fi only, charging only,
only files added from now on) and switch **Auto backup** on. "Back up now" runs immediately; "Stop" aborts.
Phone backups show up in the web app under **Phone backups**.

## How it works
* The login creates a normal Telegram session (visible under Telegram → Settings → Devices). Log out in the app to revoke it.
* Each file is uploaded in 512 KB parts and sent as a document (original quality, up to 2 GB per message; bigger files are split
  and re-joined on download). Uploading from the browser reads the file lazily, so big files don't fill memory.
* The file list is a JSON document (`tgdrive-index.json`) in the same channel. A new browser/phone reads it, so nothing is lost if you clear your data.
* Android runs the same engine in a hidden WebView; Kotlin reads the files and feeds the bytes in parts. WorkManager schedules the runs
  and a foreground notification keeps long uploads alive. Interrupted runs resume without losing or duplicating records.

## Rebuilding after changing the engine
```
cd engine && npm install && node build.mjs     # -> engine/dist/engine.bundle.js
cd .. && node build-web.mjs                    # -> web/index.html
cp engine/dist/engine.bundle.js android/app/src/main/assets/
```

## Honest caveats
* **Untested against live Telegram.** The authoring environment cannot reach Telegram or the Android SDK. What *was* verified:
  the engine bundle loads and starts connecting in Chromium; every Telegram request it builds serialises correctly; the whole web UI
  (login steps, wrong code, 2FA, upload, second device, download, delete, revoked session) passes against a fake engine.
  The Android project has not been compiled. Expect to fix small things on first run, and please report them.
* **The session string is a key to your account.** The web app keeps it in the browser's localStorage, Android in app-private storage.
  Anyone who gets it can use your Telegram account. Don't share the browser profile; log out on shared computers.
* Not end-to-end encrypted beyond Telegram's own cloud encryption (Telegram can technically read stored files).
* Telegram can rate-limit accounts that upload very heavily (FLOOD_WAIT); the engine waits automatically.
* Android's background limits (battery optimisation, Doze) can delay runs; exempting the app from battery optimisation helps.
* Respect Telegram's Terms of Service; bulk-hosting abuse can get an account limited.

# CLAUDE.md

Guidance for working in this repo. For full state/features see [docs/STATUS.md](docs/STATUS.md).

## What this is

Jagoan = personal expense tracker. An Android `NotificationListenerService` reads **Jago**
bank notifications, sends the amount to a Node/Express middleware, which asks "what for?"
(via **Telegram** or an on-screen **overlay**) and writes the result to a **Notion**
budgeting database. Single-user.

## Repo layout

- `apps/server/` — Node 18 (ESM) + Express. **Single file:** `src/index.js`.
- `apps/android/` — Kotlin + Jetpack Compose. Package `com.nazhif.jagoan`.
- `docs/` — `STATUS.md` (status tracker), `architecture.md` (placeholder).

## Commands

**Server** (`cd apps/server`):
- `npm install`
- `npm run dev` (nodemon) · `npm start`
- Config via `.env` (git-ignored; copy from `.env.example`).
- Smoke test: `scripts/test-webhook.sh [amount] [type]`.
- Notion diagnostics (read-only): `node scripts/whoami.js`, `find-dbs.js`,
  `get-budget-db-id.js [dailyTxnId]`, `check-and-archive-page.js <pageId>`.

**Android** (`cd apps/android`, needs JDK 17 + a connected device):
- `./gradlew installDebug` — build + install debug.
- `./gradlew :app:assembleDebug` — build only.
- Logs: `adb logcat -s JagoanSensor:D JagoanOverlay:D`.
- **Debug builds talk to `http://localhost:3000`** via `BuildConfig.SERVER_BASE_URL`, so
  first run `adb reverse tcp:3000 tcp:3000`. Release builds target `jagoan.kalachakra.io`.

## Server endpoints

- `POST /webhook/transaction` `{amount,type}` — caches txn; telegram mode DMs the user,
  overlay mode returns `{mode,transactionId,amount,categories}`.
- `POST /webhook/transaction/confirm` `{transactionId,name,categoryKey}` — writes to Notion.
- `POST /webhook/transaction/discard` `{transactionId}` — drops a cancelled txn.
- `GET /health`.

## Key Android files

- `…/JagoanListenerService.kt` — sensor: parse amount/type, dedup, POST, overlay trigger,
  confirm/discard, notification-access auto-return (`onListenerConnected`).
- `…/overlay/` — `OverlayController` (WindowManager card), `OverlayLifecycleOwner`
  (ViewTree owners so Compose runs outside an Activity), `OverlayContent` (glass UI).
- `…/MainActivity.kt` — two-step permission flow + debug "Test Overlay" button.

## Modes

`MODE` env: `telegram` (default) or `overlay`. Telegram bot only launches in telegram mode.

## Conventions & gotchas

- **Never commit `apps/server/.env`** (real secrets; it's git-ignored).
- User-facing strings are **Indonesian** (Rp currency, "Untuk keperluan apa?", etc.).
- **Notion categories** come from the Daily Transactions `Budgeting` relation, auto-derived
  when `NOTION_BUDGET_DATABASE_ID` is unset. Each month, only `NOTION_DATABASE_ID` changes.
- Notion integration is connected at the **MONETERY** parent page (cascades to all months).
- The server DB uses `created_at` (created_time), so **leave `NOTION_DATE_PROPERTY_NAME`
  unset** — writing a non-existent `Date` property makes Notion reject the page.
- `minSdk 30` → only the **adaptive vector** launcher icon is used (no legacy PNGs).
- The overlay needs `SYSTEM_ALERT_WINDOW`; it shows on the main thread (the trigger arrives
  on an OkHttp IO thread, so `OverlayController` hops via a main `Handler`).
- Custom icon packs (e.g. Samsung Theme Park) override the launcher icon — not a build issue.

## Verifying changes

- Server logic can be tested with dummy creds + `MODE=overlay` (the webhook makes no Notion
  call); `/confirm` does hit Notion. Pass a temp env via `DOTENV_CONFIG_PATH`.
- Android overlay can be previewed without a real transaction via the debug Test Overlay button.

## Git

Default branch `main`. Branch for non-trivial work (e.g. `feat/…`); confirm before pushing.
End commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

# Jagoan — Project Status & Feature Tracker

_Last updated: 2026-06-07_

## What Jagoan is

Jagoan automates personal expense tracking. The moment you spend money in the
**Jago** banking app, your phone gets a notification → Jagoan intercepts it,
extracts the amount, and asks you what it was for. Your answer is written to a
**Notion** budgeting database. It's single-user (one Jago account, one Notion
workspace, one Telegram chat).

There are now **two ways** to capture the "what was it for?" step, selected by a
single server env flag:

- **`telegram`** (default) — the server DMs you on Telegram; you reply with the purpose.
- **`overlay`** — a floating card pops up over any app right after the transaction;
  you type a purpose + tap a category, and it saves straight to Notion.

## Architecture

```
[Jago app] --notification--> [Android: JagoanListenerService]
                                   │ parse amount + OUTGOING/INCOMING
                                   ▼ POST {amount, type}
                             [Node/Express server]  --(overlay mode)-->  returns {transactionId, amount, categories}
                                   │                                          │
                       (telegram mode)                                        ▼  Android floating overlay
                       sends Telegram DM                            user picks purpose + category
                                   │                                          │ POST /confirm {id, name, categoryKey}
                                   └──────────────► [Notion: Daily Transactions] ◄──────────────┘
                                                    (Budgeting relation = category)
```

- **`apps/android`** — Kotlin + Jetpack Compose. A `NotificationListenerService` is the
  sensor; the only screens are a permission/setup screen and the transaction overlay.
- **`apps/server`** — Node.js + Express middleware. Stateless glue between the phone,
  Telegram (Telegraf), and Notion (`@notionhq/client`); pending transactions live in an
  in-memory `node-cache` (1h TTL).
- **Notion** — a per-month **Daily Transactions** DB (where rows are written) related to a
  **Budgeting** DB (whose rows are the categories).

It is a thin, event-driven pipeline — not microservices, not MVC.

## Tech stack

| Layer | Stack |
|---|---|
| Android | Kotlin, Jetpack Compose (Material3), NotificationListenerService, Coroutines, OkHttp 4.12, lifecycle/savedstate; `minSdk 30 / target 36`, `versionCode 2 / 1.1` |
| Server | Node 18 (ESM), Express 4, Telegraf 4, `@notionhq/client` 2, node-cache, dotenv |
| Infra | Docker Compose (app + nginx), host nginx reverse proxy, `jagoan.kalachakra.io` |

## Feature status

| Feature | Status | Notes |
|---|---|---|
| Notification interception (Jago only) | ✅ | filters `com.jago.digitalBanking`, regex IDR amount |
| OUTGOING vs INCOMING classification | ✅ | keyword lists (EN + ID); server only logs OUTGOING |
| Duplicate suppression | ✅ | same amount within 5 s dropped |
| Telegram confirmation flow | ✅ | original path, still default (`MODE=telegram`) |
| **Overlay confirmation flow** | ✅ | floating glass card; `MODE=overlay` |
| Category picker | ✅ | chips in overlay; written as Notion `Budgeting` relation |
| **Live category derivation** | ✅ | categories auto-pulled from the Daily Transactions `Budgeting` relation; only `NOTION_DATABASE_ID` needs updating monthly |
| Notion write | ✅ | Name + Amount + optional Date + category relation |
| Two-step permission flow + auto-return | ✅ | notification access auto-returns to app; settle re-check fixes read race |
| Money-bag launcher icon + "Jagoan" name | ✅ | adaptive vector icon; legacy PNGs removed |
| Debug → localhost / release → prod URL | ✅ | via `BuildConfig.SERVER_BASE_URL` |
| Debug "Test Overlay" button | ✅ | preview overlay without a real transaction |
| Webhook authentication | ❌ | open endpoint — see Limitations |
| HTTPS/TLS on host nginx | ⚠️ | configured but `ssl_certificate` lines commented out |
| Automated tests / CI | ❌ | manual test plan + `test-webhook.sh` only |

## Key flows

**Overlay mode (current):**
1. Jago `OUTGOING` notification → `JagoanListenerService` parses amount + type.
2. `POST /webhook/transaction {amount, type}` → server caches it and returns
   `{mode:"overlay", transactionId, amount, categories:[{key,label}]}` (no Telegram).
3. App shows the floating overlay (`overlay/OverlayController` + `OverlayContent`).
4. User types a purpose, taps a category, **Simpan** →
   `POST /webhook/transaction/confirm {transactionId, name, categoryKey}`.
5. Server writes to Notion (`Name`, `Amount`, `Budgeting` relation), clears the cache.
   Cancel/Back → `POST /webhook/transaction/discard`.

**Telegram mode (default):** same `POST /webhook/transaction`, but the server sends a
Telegram DM and the user's text reply is written to Notion via `bot.on("text")`.

## Configuration (`apps/server/.env`)

| Var | Required | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN`, `MY_CHAT_ID` | ✅ | Telegram bot + authorized chat |
| `NOTION_KEY`, `NOTION_DATABASE_ID` | ✅ | Notion integration + Daily Transactions DB |
| `NOTION_BUDGET_RELATION_NAME` | for categories | relation column name on Daily Transactions (e.g. `Budgeting`) |
| `MODE` | optional | `telegram` (default) or `overlay` |
| `NOTION_BUDGET_DATABASE_ID` | optional | pin category DB; **unset = auto-derived from the relation** |
| `NOTION_CATEGORIES` | optional | static `[{key,label,pageId}]` fallback |
| `NOTION_DATE_PROPERTY_NAME` | optional | set only if the DB has a writable Date property (June DB uses `created_at`, so leave unset) |
| `NOTION_LIVING_COST_PAGE_ID` | optional | legacy single-category fallback |
| `PORT` | optional | default 3000 |

### Notion "connect once" setup

The server authenticates as the **"Daily Transaction February"** integration. Add it as a
connection on the top-level **MONETERY** page (not per-database) — Notion cascades access
to every child DB, including future months. Each month, point `NOTION_DATABASE_ID` at that
month's Daily Transactions DB; categories follow automatically via the relation.

Current month (June 2026):
- Daily Transactions: `36c37b82-ffab-81c9-994c-da9730fe5eb0`
- Budgeting (derived): `36c37b82-ffab-81b0-9eba-d8500a97fbc2`

## Deployment

- **Server:** Docker Compose (`app:3000` + `nginx:8081`) behind a host nginx that terminates
  `jagoan.kalachakra.io`. Env is read at startup — restart to change `MODE` / month.
- **Android:** debug build (`./gradlew installDebug`) targets `http://localhost:3000` via
  `adb reverse tcp:3000 tcp:3000`; release build targets the production URL automatically.

## Diagnostic scripts (`apps/server/scripts/`, read-only)

- `whoami.js` — which Notion integration the server key is.
- `find-dbs.js` — every DB the integration can see, grouped by parent page (find the month).
- `get-budget-db-id.js [dailyTxnId]` — relation target + category list + Date-property check.
- `check-and-archive-page.js <pageId>` — verify a created row, then archive it (test cleanup).
- `test-webhook.sh`, `debug-schema.js` — pre-existing helpers.

## Known limitations / caveats

- **Webhook is unauthenticated** — anyone who reaches it can inject transactions. Add a
  shared secret / HMAC before exposing more widely.
- **Host nginx TLS** — cert lines are commented out; enable for real HTTPS.
- **Concurrency** — Telegram mode pairs a reply with the *newest* cached transaction, so two
  unconfirmed transactions can be mismatched (overlay mode is unaffected — it uses the id).
- **Overlay permission has no auto-return** — only notification access can auto-return;
  granting "Display over other apps" still needs one Back press.
- **Auto-return needs background activity launch** — reliable when "Display over other apps"
  is already granted (that permission allowlists the app).
- **Monthly maintenance** — update `NOTION_DATABASE_ID` to the new month's Daily Transactions
  DB (one line). A single perennial DB would remove even that.
- **Custom icon packs** (e.g. Samsung Theme Park) override the launcher icon; update the
  icon inside the theme to see app icon changes.

## Roadmap / ideas

- [ ] Authenticate the webhook (shared secret / HMAC).
- [ ] Enable TLS on the host nginx.
- [ ] Auto-pick the current month's Daily Transactions DB (zero monthly edits).
- [ ] Overlay: optional amount-edit / false-positive dismiss.
- [ ] Automated tests + CI.

## Recent work

Branch `feat/overlay-mode` (2026-06-07):
- `feat(server): overlay mode with live Notion category derivation`
- `feat(android): floating overlay for transaction input + money-bag icon`

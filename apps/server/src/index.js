import "dotenv/config";
import express from "express";
import { Telegraf } from "telegraf";
import { Client } from "@notionhq/client";
import NodeCache from "node-cache";

// ============================================
// Configuration
// ============================================
const PORT = process.env.PORT || 3000;
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN;
const MY_CHAT_ID = process.env.MY_CHAT_ID;
const NOTION_KEY = process.env.NOTION_KEY;
const NOTION_DATABASE_ID = process.env.NOTION_DATABASE_ID;
const NOTION_LIVING_COST_PAGE_ID = process.env.NOTION_LIVING_COST_PAGE_ID;
const NOTION_BUDGET_RELATION_NAME = process.env.NOTION_BUDGET_RELATION_NAME;
// When set, overlay categories are fetched live from this Budgeting database
// (each row becomes a selectable category) instead of the static NOTION_CATEGORIES list.
const NOTION_BUDGET_DATABASE_ID = process.env.NOTION_BUDGET_DATABASE_ID;
// Optional: name of a Date property to stamp with today's date. Leave unset if your
// Daily Transactions DB uses a created_time property (e.g. "created_at"), which Notion
// fills automatically and cannot be set via the API.
const NOTION_DATE_PROPERTY_NAME = process.env.NOTION_DATE_PROPERTY_NAME;

// Confirmation mode: "telegram" (default) prompts via the Telegram bot;
// "overlay" lets the Android app collect purpose/category via a floating overlay.
const MODE = (process.env.MODE || "telegram").toLowerCase();

// Validate required environment variables
const requiredEnvVars = [
  "TELEGRAM_BOT_TOKEN",
  "MY_CHAT_ID",
  "NOTION_KEY",
  "NOTION_DATABASE_ID",
];

const missingEnvVars = requiredEnvVars.filter(
  (varName) => !process.env[varName]
);
if (missingEnvVars.length > 0) {
  console.error(
    "❌ Missing required environment variables:",
    missingEnvVars.join(", ")
  );
  console.error("Please create a .env file based on .env.example");
  process.exit(1);
}

// Validate MODE (fail fast on misconfiguration, like the env check above)
if (MODE !== "telegram" && MODE !== "overlay") {
  console.error(`❌ Invalid MODE: "${MODE}". Must be "telegram" or "overlay".`);
  process.exit(1);
}

// ============================================
// Initialize Services
// ============================================
const app = express();
const bot = new Telegraf(TELEGRAM_BOT_TOKEN);
const notion = new Client({ auth: NOTION_KEY });
const cache = new NodeCache({ stdTTL: 3600 }); // 1 hour TTL
const categoryCache = new NodeCache({ stdTTL: 300 }); // 5 min TTL for budget categories

// Middleware
app.use(express.json());

// ============================================
// Category Configuration (overlay mode)
// ============================================

/**
 * Parse the optional NOTION_CATEGORIES env var (JSON array of {key,label,pageId}).
 * Returns null when unset (callers fall back to the single Living-cost relation).
 * Fails fast on malformed JSON — a broken picker is a deploy error, not a runtime guess.
 */
function parseCategories() {
  const raw = process.env.NOTION_CATEGORIES;
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      throw new Error("NOTION_CATEGORIES must be a JSON array");
    }
    for (const c of parsed) {
      if (
        !c ||
        typeof c.key !== "string" ||
        typeof c.label !== "string" ||
        typeof c.pageId !== "string"
      ) {
        throw new Error("each category needs string key, label, and pageId");
      }
    }
    return parsed;
  } catch (e) {
    console.error("❌ Failed to parse NOTION_CATEGORIES:", e.message);
    process.exit(1);
  }
}

const NOTION_CATEGORIES = parseCategories();

/** Extract the title text of a Notion page (the property whose type is "title"). */
function extractPageTitle(page) {
  const props = page.properties || {};
  for (const name of Object.keys(props)) {
    const prop = props[name];
    if (prop && prop.type === "title") {
      return (prop.title || [])
        .map((t) => t.plain_text)
        .join("")
        .trim();
    }
  }
  return "";
}

/**
 * Derive the Budgeting database id from the Daily Transactions relation, so it never
 * has to be configured manually (and can't drift out of sync with NOTION_DATABASE_ID).
 * Looks up the relation property named NOTION_BUDGET_RELATION_NAME (or the first relation
 * if that's unset) and returns its target database_id. Cached (the schema rarely changes);
 * caches null on failure too, so we don't re-hit Notion on every request.
 */
async function deriveBudgetDatabaseId() {
  const cached = categoryCache.get("derived_budget_db_id");
  if (cached !== undefined) return cached;

  let result = null;
  try {
    const db = await notion.databases.retrieve({
      database_id: NOTION_DATABASE_ID,
    });
    let relation = null;
    for (const [name, prop] of Object.entries(db.properties)) {
      if (prop.type !== "relation") continue;
      if (NOTION_BUDGET_RELATION_NAME && name === NOTION_BUDGET_RELATION_NAME) {
        relation = prop;
        break;
      }
      if (!relation) relation = prop; // fallback: first relation property
    }
    result = relation?.relation?.database_id || null;
    if (result) {
      console.log(`🔗 Derived Budgeting DB from relation: ${result}`);
    } else {
      console.warn("⚠️  No relation property found on Daily Transactions to derive categories from");
    }
  } catch (error) {
    console.error("❌ Failed to derive Budgeting database id:", error.message);
  }

  categoryCache.set("derived_budget_db_id", result);
  return result;
}

/**
 * Fetch the rows of a Budgeting database as categories ({ key, label, pageId }),
 * where the key IS the page id. Cached for 5 minutes (per database id); on a Notion
 * error we serve a stale cache if available, otherwise an empty list (overlay still
 * works, purpose-only).
 */
async function fetchBudgetCategories(budgetDbId) {
  const cacheKey = `budget_categories:${budgetDbId}`;
  const cached = categoryCache.get(cacheKey);
  if (cached) return cached;

  try {
    const resp = await notion.databases.query({
      database_id: budgetDbId,
      page_size: 100, // personal use: well under one page of categories
    });
    const categories = resp.results
      .map((page) => {
        const label = extractPageTitle(page);
        return { key: page.id, label, pageId: page.id };
      })
      .filter((c) => c.label);
    categoryCache.set(cacheKey, categories);
    return categories;
  } catch (error) {
    console.error("❌ Failed to fetch Budgeting categories:", error.message);
    return categoryCache.get(cacheKey) || [];
  }
}

/**
 * The full set of category options ({ key, label, pageId }), in priority order:
 *   1. NOTION_BUDGET_DATABASE_ID (explicit pinned database)
 *   2. NOTION_CATEGORIES (explicit static list)
 *   3. the Budgeting DB derived from the Daily Transactions relation (default)
 *   4. a single Living-cost fallback
 * Async because the live sources query Notion.
 */
async function getCategoryOptions() {
  if (NOTION_BUDGET_DATABASE_ID) {
    return await fetchBudgetCategories(NOTION_BUDGET_DATABASE_ID);
  }
  if (NOTION_CATEGORIES) {
    return NOTION_CATEGORIES;
  }
  const derivedId = await deriveBudgetDatabaseId();
  if (derivedId) {
    return await fetchBudgetCategories(derivedId);
  }
  return [
    { key: "living_cost", label: "Living Cost", pageId: NOTION_LIVING_COST_PAGE_ID },
  ];
}

/**
 * Category list to send to the Android overlay — labels + keys only, pageId stripped.
 */
async function categoriesForClient() {
  const options = await getCategoryOptions();
  return options.map((c) => ({ key: c.key, label: c.label }));
}

/**
 * Resolve a category key to its Notion relation page id.
 * Returns undefined for unknown keys so callers can respond 400; a null/absent key
 * also yields undefined (createNotionTransaction then falls back to the Living-cost page).
 */
async function categoryPageIdForKey(key) {
  if (!key) return undefined;
  const options = await getCategoryOptions();
  const match = options.find((c) => c.key === key);
  return match ? match.pageId : undefined;
}

// ============================================
// Helper Functions
// ============================================

/**
 * Generate a unique transaction ID
 */
function generateTransactionId() {
  return `txn_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Format amount to IDR currency
 */
function formatIDR(amount) {
  return new Intl.NumberFormat("id-ID", {
    style: "currency",
    currency: "IDR",
    minimumFractionDigits: 0,
  }).format(amount);
}

/**
 * Create a new transaction in Notion
 *
 * @param {string} name - transaction purpose / title
 * @param {number} amount - transaction amount
 * @param {string} [categoryPageId] - optional Notion relation page id for the
 *   chosen category (overlay mode). Falls back to NOTION_LIVING_COST_PAGE_ID
 *   when omitted, preserving the original Telegram-path behavior.
 */
async function createNotionTransaction(name, amount, categoryPageId) {
  try {
    const properties = {
      Name: {
        title: [
          {
            text: {
              content: name,
            },
          },
        ],
      },
      Amount: {
        number: amount,
      },
    };

    // Only stamp a Date property if one is configured. DBs that use a created_time
    // property (e.g. "created_at") populate the date automatically — writing a
    // non-existent "Date" property would make the Notion API reject the whole page.
    if (NOTION_DATE_PROPERTY_NAME) {
      const today = new Date().toISOString().split("T")[0]; // YYYY-MM-DD
      properties[NOTION_DATE_PROPERTY_NAME] = {
        date: {
          start: today,
        },
      };
    }

    // Add category relation if a page ID is available (explicit category wins,
    // otherwise fall back to the configured Living-cost page).
    const relationId = categoryPageId || NOTION_LIVING_COST_PAGE_ID;
    if (relationId && NOTION_BUDGET_RELATION_NAME) {
      properties[NOTION_BUDGET_RELATION_NAME] = {
        relation: [
          {
            id: relationId,
          },
        ],
      };
    }

    const response = await notion.pages.create({
      parent: {
        database_id: NOTION_DATABASE_ID,
      },
      properties,
    });

    return response;
  } catch (error) {
    console.error("❌ Error creating Notion page:", error.message);
    if (error.body) {
      console.error(
        "Notion API Error Details:",
        JSON.stringify(error.body, null, 2)
      );
    }
    throw error;
  }
}

// ============================================
// Express Routes
// ============================================

/**
 * POST /webhook/transaction
 * Receives transaction data from Android Notification Listener
 */
app.post("/webhook/transaction", async (req, res) => {
  try {
    const { amount, type } = req.body;

    // Check notification type - only process OUTGOING transactions
    // INCOMING notifications (promos, received money, cashback, etc.) should be ignored
    if (type && type !== "OUTGOING") {
      console.log(
        `ℹ️  Ignoring ${type} notification with amount: ${formatIDR(
          amount || 0
        )}`
      );
      return res.json({
        success: true,
        skipped: true,
        message: `Notification type '${type}' ignored. Only OUTGOING transactions are processed.`,
      });
    }

    // Validate amount
    if (!amount || typeof amount !== "number") {
      return res.status(400).json({
        success: false,
        error: "Invalid request. Amount must be a number.",
      });
    }

    // Generate unique transaction ID
    const transactionId = generateTransactionId();

    // Store in cache
    cache.set(transactionId, { amount, type, timestamp: Date.now() });

    console.log(
      `✅ Transaction ${transactionId} received: ${formatIDR(amount)} [mode=${MODE}]`
    );

    // Overlay mode: let the Android app collect purpose/category on-device.
    // Do NOT send a Telegram prompt; return the data the overlay needs.
    if (MODE === "overlay") {
      return res.json({
        success: true,
        mode: "overlay",
        transactionId,
        amount,
        type: type || "OUTGOING",
        categories: await categoriesForClient(),
      });
    }

    // Telegram mode (default): prompt the user over Telegram.
    const message = `🎯 Jagoan! Ada pengeluaran baru sebesar ${formatIDR(
      amount
    )}.\n\nUntuk keperluan apa?`;

    await bot.telegram.sendMessage(MY_CHAT_ID, message);

    res.json({
      success: true,
      mode: "telegram",
      transactionId,
      message: "Transaction received. Please reply to the Telegram bot.",
    });
  } catch (error) {
    console.error("❌ Error processing webhook:", error.message);
    res.status(500).json({
      success: false,
      error: "Internal server error",
    });
  }
});

/**
 * POST /webhook/transaction/confirm
 * Overlay mode: the Android overlay submits the chosen purpose + category for a
 * previously cached transaction, and we persist it to Notion.
 */
app.post("/webhook/transaction/confirm", async (req, res) => {
  try {
    const { transactionId, name, categoryKey } = req.body;

    // Validate input
    if (!transactionId || typeof name !== "string" || name.trim() === "") {
      return res.status(400).json({
        success: false,
        error: "transactionId and a non-empty name are required.",
      });
    }

    // Look up the pending transaction (missing OR expired both land here)
    const transaction = cache.get(transactionId);
    if (!transaction) {
      return res.status(404).json({
        success: false,
        error: "Transaction not found or expired.",
      });
    }

    // Resolve the category to a Notion relation page id
    const categoryPageId = await categoryPageIdForKey(categoryKey);
    if (categoryKey && categoryPageId === undefined) {
      return res.status(400).json({
        success: false,
        error: `Unknown category: ${categoryKey}`,
      });
    }

    // Persist to Notion, then clear the cache (only on success, so a transient
    // Notion failure leaves the transaction available for retry).
    const notionPage = await createNotionTransaction(
      name.trim(),
      transaction.amount,
      categoryPageId
    );
    cache.del(transactionId);

    console.log(
      `✅ Transaction ${transactionId} saved to Notion via overlay:`,
      notionPage.id
    );

    res.json({
      success: true,
      message: "Transaction saved.",
      notionPageId: notionPage.id,
    });
  } catch (error) {
    console.error("❌ Error confirming transaction:", error.message);
    res.status(500).json({
      success: false,
      error: "Failed to save transaction.",
    });
  }
});

/**
 * POST /webhook/transaction/discard
 * Overlay mode: the user cancelled the overlay. Drop the pending cache entry so
 * it doesn't linger (and can't contaminate the Telegram newest-key heuristic).
 * Fire-and-forget — always succeeds.
 */
app.post("/webhook/transaction/discard", (req, res) => {
  const { transactionId } = req.body || {};
  if (transactionId) {
    cache.del(transactionId);
    console.log(`🗑️  Transaction ${transactionId} discarded.`);
  }
  res.json({ success: true });
});

/**
 * GET /health
 * Health check endpoint
 */
app.get("/health", (req, res) => {
  res.json({
    status: "ok",
    timestamp: new Date().toISOString(),
    cachedTransactions: cache.keys().length,
  });
});

// ============================================
// Telegram Bot Handlers
// ============================================

/**
 * Handle text messages from user
 * This captures the user's reply about the transaction purpose
 */
bot.on("text", async (ctx) => {
  try {
    // Only process messages from the configured chat ID
    if (ctx.chat.id.toString() !== MY_CHAT_ID) {
      console.log(
        `⚠️  Ignoring message from unauthorized chat: ${ctx.chat.id}`
      );
      return;
    }

    const userReply = ctx.message.text;

    // Get all cached transactions (in a real scenario, you might want to track which transaction is pending)
    const cachedKeys = cache.keys();

    if (cachedKeys.length === 0) {
      await ctx.reply("❌ Tidak ada transaksi yang menunggu konfirmasi.");
      return;
    }

    // Get the most recent transaction (last in cache - newest one)
    const transactionId = cachedKeys[cachedKeys.length - 1];
    const transaction = cache.get(transactionId);

    if (!transaction) {
      await ctx.reply("❌ Transaksi tidak ditemukan atau sudah kadaluarsa.");
      return;
    }

    // Create Notion page
    await ctx.reply("⏳ Menyimpan transaksi ke Notion...");

    const notionPage = await createNotionTransaction(
      userReply,
      transaction.amount
    );

    // Clear from cache
    cache.del(transactionId);

    // Send confirmation
    await ctx.reply(
      `✅ Transaksi berhasil disimpan!\n\n` +
        `📝 Nama: ${userReply}\n` +
        `💰 Jumlah: ${formatIDR(transaction.amount)}\n` +
        `📅 Tanggal: ${new Date().toLocaleDateString("id-ID")}`
    );

    console.log(
      `✅ Transaction ${transactionId} saved to Notion:`,
      notionPage.id
    );
  } catch (error) {
    console.error("❌ Error handling Telegram message:", error.message);
    await ctx.reply(
      "❌ Terjadi kesalahan saat menyimpan transaksi. Silakan coba lagi."
    );
  }
});

// ============================================
// Error Handling
// ============================================

// Express error handler
app.use((err, req, res, next) => {
  console.error("❌ Express error:", err.message);
  res.status(500).json({
    success: false,
    error: "Internal server error",
  });
});

// Bot error handler
bot.catch((err, ctx) => {
  console.error("❌ Bot error:", err);
});

// ============================================
// Server Startup
// ============================================

async function startServer() {
  try {
    // Start Express server first - bind to 0.0.0.0 for IPv4 connectivity
    app.listen(PORT, "0.0.0.0", () => {
      console.log("🚀 Jagoan Server is running");
      console.log(
        `📡 Webhook endpoint: http://localhost:${PORT}/webhook/transaction`
      );
      console.log(`💚 Health check: http://localhost:${PORT}/health`);
      console.log(`👤 Monitoring chat ID: ${MY_CHAT_ID}`);
      console.log(`⚙️  Confirmation mode: ${MODE}`);
      if (MODE === "overlay") {
        if (NOTION_BUDGET_DATABASE_ID) {
          console.log(
            `🗂️  Overlay categories: live from Budgeting DB (${NOTION_BUDGET_DATABASE_ID})`
          );
        } else if (NOTION_CATEGORIES) {
          console.log(
            `🗂️  Overlay categories: static (${NOTION_CATEGORIES.map((c) => c.key).join(", ")})`
          );
        } else {
          console.log(
            "🗂️  Overlay categories: derived from the Daily Transactions Budgeting relation"
          );
        }
      }
      // The Living-cost fallback only categorizes in telegram mode; overlay mode
      // categorizes via the picker, so don't warn about it there.
      if (MODE === "telegram") {
        if (NOTION_LIVING_COST_PAGE_ID) {
          console.log(
            `📊 Notion category: Living cost (${NOTION_LIVING_COST_PAGE_ID})`
          );
        } else {
          console.log(
            "⚠️  NOTION_LIVING_COST_PAGE_ID not set - transactions will not be categorized"
          );
        }
      }
    });

    // The Telegram bot is only needed in telegram mode. In overlay mode the overlay
    // collects input and writes via /confirm, so we skip launching the long-poller
    // entirely (avoids a needless getUpdates conflict / "Bot launch timeout" noise).
    let botStarted = false;
    if (MODE === "telegram") {
      console.log("🤖 Starting Telegram bot...");

      // Launch bot in background with timeout
      const botLaunch = async () => {
        try {
          await Promise.race([
            bot.launch({
              dropPendingUpdates: true,
              allowedUpdates: ["message"],
            }),
            new Promise((_, reject) =>
              setTimeout(() => reject(new Error("Bot launch timeout")), 10000)
            ),
          ]);
          botStarted = true;
          console.log("✅ Telegram bot is running");
          console.log("✨ All systems operational!");
        } catch (err) {
          console.log("⚠️  Bot polling failed, but server is still running");
          console.log(
            "💡 Tip: The webhook endpoint is active. Bot will respond when messages arrive."
          );
          console.log("Error:", err.message);
        }
      };

      // Launch bot without blocking
      botLaunch();
    } else {
      console.log("🛑 Telegram bot not started (overlay mode).");
      console.log("✨ All systems operational!");
    }

    // Enable graceful stop (only stop the bot if it was actually launched)
    process.once("SIGINT", () => {
      console.log("\n👋 Shutting down gracefully...");
      if (botStarted) bot.stop("SIGINT");
      process.exit(0);
    });
    process.once("SIGTERM", () => {
      console.log("\n👋 Shutting down gracefully...");
      if (botStarted) bot.stop("SIGTERM");
      process.exit(0);
    });
  } catch (error) {
    console.error("❌ Failed to start server:", error.message);
    console.error("Full error:", error);
    process.exit(1);
  }
}

// Start the server
startServer();

// Uncomment this to debug database schema
// (async () => {
//   const db = await notion.databases.retrieve({ database_id: NOTION_DATABASE_ID });
//   console.log('Database properties:', JSON.stringify(db.properties, null, 2));
// })();

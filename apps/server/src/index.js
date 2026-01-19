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

// ============================================
// Initialize Services
// ============================================
const app = express();
const bot = new Telegraf(TELEGRAM_BOT_TOKEN);
const notion = new Client({ auth: NOTION_KEY });
const cache = new NodeCache({ stdTTL: 3600 }); // 1 hour TTL

// Middleware
app.use(express.json());

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
 */
async function createNotionTransaction(name, amount) {
  try {
    const today = new Date().toISOString().split("T")[0]; // YYYY-MM-DD format

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
      Date: {
        date: {
          start: today,
        },
      },
    };

    // Add Living cost category relation if page ID is provided
    if (NOTION_LIVING_COST_PAGE_ID) {
      properties["Februari Budgeting (25 Jan – 25 Feb)\n"] = {
        relation: [
          {
            id: NOTION_LIVING_COST_PAGE_ID,
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
    const { amount } = req.body;

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
    cache.set(transactionId, { amount, timestamp: Date.now() });

    // Send Telegram message
    const message = `🎯 Jagoan! Ada pengeluaran baru sebesar ${formatIDR(
      amount
    )}.\n\nUntuk keperluan apa?`;

    await bot.telegram.sendMessage(MY_CHAT_ID, message);

    console.log(
      `✅ Transaction ${transactionId} received: ${formatIDR(amount)}`
    );

    res.json({
      success: true,
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

    // Get the most recent transaction (first in cache)
    const transactionId = cachedKeys[0];
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
      if (NOTION_LIVING_COST_PAGE_ID) {
        console.log(
          `📊 Notion category: Living cost (${NOTION_LIVING_COST_PAGE_ID})`
        );
      } else {
        console.log(
          "⚠️  NOTION_LIVING_COST_PAGE_ID not set - transactions will not be categorized"
        );
      }
    });

    // Start Telegram bot after Express is running
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

    // Enable graceful stop
    process.once("SIGINT", () => {
      console.log("\n👋 Shutting down gracefully...");
      bot.stop("SIGINT");
      process.exit(0);
    });
    process.once("SIGTERM", () => {
      console.log("\n👋 Shutting down gracefully...");
      bot.stop("SIGTERM");
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

# Jagoan Server

A Node.js middleware server that receives transaction amounts from an Android Notification Listener, sends confirmation messages via Telegram Bot, and saves confirmed data to a Notion Database.

## 🎯 Features

- **Webhook Endpoint**: Receives transaction data from Android Notification Listener
- **Telegram Bot Integration**: Sends confirmation messages and captures user responses
- **Notion Database**: Automatically saves transactions with categorization
- **Transaction Caching**: Temporarily stores pending transactions using node-cache
- **Error Handling**: Comprehensive error handling and logging

## 🛠️ Tech Stack

- **Node.js** with ES Modules
- **Express.js** - Webhook endpoint
- **Telegraf** - Telegram Bot API
- **@notionhq/client** - Notion SDK
- **dotenv** - Environment variables
- **node-cache** - In-memory caching

## 📋 Prerequisites

Before running the server, you need to set up:

1. **Telegram Bot**

   - Create a bot via [@BotFather](https://t.me/botfather)
   - Get your bot token
   - Get your chat ID (you can use [@userinfobot](https://t.me/userinfobot))

2. **Notion Integration**
   - Create a Notion integration at [https://www.notion.so/my-integrations](https://www.notion.so/my-integrations)
   - Get your integration token
   - Create a "Daily Transactions" database with these properties:
     - **Name** (Title)
     - **Amount** (Number)
     - **Date** (Date)
     - **Category** (Relation - optional, for linking to Living cost page)
   - Share your database with the integration
   - Copy the database ID from the URL

## 🚀 Installation

1. **Install dependencies**:

   ```bash
   npm install
   ```

2. **Configure environment variables**:

   ```bash
   cp .env.example .env
   ```

3. **Edit `.env` file** with your actual credentials:
   ```env
   NOTION_KEY=secret_xxxxxxxxxxxxx
   NOTION_DATABASE_ID=xxxxxxxxxxxxx
   NOTION_LIVING_COST_PAGE_ID=xxxxxxxxxxxxx  # Optional
   TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
   MY_CHAT_ID=123456789
   PORT=3000
   ```

## 🏃 Running the Server

**Development mode** (with auto-reload):

```bash
npm run dev
```

**Production mode**:

```bash
npm start
```

The server will start on `http://localhost:3000` (or your configured PORT).

## 📡 API Endpoints

### POST /webhook/transaction

Receives transaction data from the Android Notification Listener.

**Request Body**:

```json
{
  "amount": 50000
}
```

**Response**:

```json
{
  "success": true,
  "transactionId": "txn_1234567890_abc123",
  "message": "Transaction received. Please reply to the Telegram bot."
}
```

### GET /health

Health check endpoint to verify server status.

**Response**:

```json
{
  "status": "ok",
  "timestamp": "2026-01-19T08:30:00.000Z",
  "cachedTransactions": 1
}
```

## 💬 Telegram Bot Flow

1. **Android app sends transaction** → POST to `/webhook/transaction`
2. **Server caches transaction** and sends Telegram message:

   ```
   🎯 Jagoan! Ada pengeluaran baru sebesar Rp 50.000.

   Untuk keperluan apa?
   ```

3. **You reply** with the transaction description (e.g., "Makan siang")
4. **Bot saves to Notion** and confirms:

   ```
   ✅ Transaksi berhasil disimpan!

   📝 Nama: Makan siang
   💰 Jumlah: Rp 50.000
   📅 Tanggal: 19/01/2026
   ```

## 🔧 Configuration Notes

### NOTION_LIVING_COST_PAGE_ID (Optional)

If you want transactions to be automatically linked to a "Living cost" category:

1. Create a category page in Notion
2. Copy the page ID from the URL
3. Add it to your `.env` file

If not set, transactions will still be saved but without category linking.

### Cache TTL

Transactions are cached for **1 hour** by default. If you don't reply within this time, the transaction will expire and you'll need to resend it.

## 🐛 Troubleshooting

**Bot not responding?**

- Verify `TELEGRAM_BOT_TOKEN` is correct
- Check that you've started a conversation with your bot
- Ensure `MY_CHAT_ID` matches your actual Telegram chat ID

**Notion errors?**

- Verify the integration has access to your database
- Check that database properties match the expected schema
- Ensure `NOTION_DATABASE_ID` is correct

**Webhook not working?**

- Check that the server is running
- Verify the Android app is sending to the correct URL
- Check server logs for error messages

## 📝 Logs

The server provides detailed console logs:

- ✅ Success messages (green checkmark)
- ❌ Error messages (red X)
- ⚠️ Warning messages (yellow warning)
- 🚀 Startup information

## 🔒 Security Notes

- Never commit your `.env` file to version control
- Keep your Telegram bot token and Notion API key secure
- Consider adding authentication to the webhook endpoint for production use
- Use HTTPS in production environments

## 📄 License

ISC

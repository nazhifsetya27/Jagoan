# Test Plan: Jagoan Server

This document outlines the manual testing procedures for the Jagoan server.

## 🎯 Test Objectives

- Verify webhook endpoint functionality
- Validate Telegram bot integration
- Confirm Notion database integration
- Test error handling scenarios

## 📋 Prerequisites

- Server is running (`npm run dev`)
- `.env` file is configured with valid credentials
- Telegram bot is accessible
- Notion database is set up and shared with integration

## 🧪 Test Cases

### 1. Server Startup

**Objective**: Verify server starts correctly with all services initialized.

**Steps**:

1. Run `npm run dev`
2. Observe console output

**Expected Results**:

- ✅ "Telegram bot is running" message appears
- ✅ "Jagoan Server is running" message appears
- ✅ Webhook endpoint URL is displayed
- ✅ Health check URL is displayed
- ✅ Chat ID is displayed
- ✅ No error messages

---

### 2. Health Check Endpoint

**Objective**: Verify health check endpoint returns correct status.

**Steps**:

1. Open browser or use curl: `http://localhost:3000/health`

**Expected Results**:

```json
{
  "status": "ok",
  "timestamp": "2026-01-19T08:30:00.000Z",
  "cachedTransactions": 0
}
```

---

### 3. Valid Transaction Webhook

**Objective**: Test successful transaction submission.

**Steps**:

1. Send POST request to `http://localhost:3000/webhook/transaction`:
   ```bash
   curl -X POST http://localhost:3000/webhook/transaction \
     -H "Content-Type: application/json" \
     -d '{"amount": 50000}'
   ```
2. Check Telegram for bot message
3. Check server console logs

**Expected Results**:

- ✅ HTTP 200 response with transaction ID
- ✅ Telegram message received: "🎯 Jagoan! Ada pengeluaran baru sebesar Rp 50.000. Untuk keperluan apa?"
- ✅ Console log: "Transaction txn_xxx received: Rp 50.000"
- ✅ Health check shows `cachedTransactions: 1`

---

### 4. Invalid Transaction - Missing Amount

**Objective**: Test error handling for missing amount field.

**Steps**:

1. Send POST request without amount:
   ```bash
   curl -X POST http://localhost:3000/webhook/transaction \
     -H "Content-Type: application/json" \
     -d '{}'
   ```

**Expected Results**:

- ✅ HTTP 400 response
- ✅ Error message: "Invalid request. Amount must be a number."
- ✅ No Telegram message sent

---

### 5. Invalid Transaction - Non-numeric Amount

**Objective**: Test error handling for invalid amount type.

**Steps**:

1. Send POST request with string amount:
   ```bash
   curl -X POST http://localhost:3000/webhook/transaction \
     -H "Content-Type: application/json" \
     -d '{"amount": "fifty thousand"}'
   ```

**Expected Results**:

- ✅ HTTP 400 response
- ✅ Error message: "Invalid request. Amount must be a number."
- ✅ No Telegram message sent

---

### 6. Telegram Bot Reply - Success Flow

**Objective**: Test complete flow from webhook to Notion.

**Steps**:

1. Send valid transaction via webhook (amount: 75000)
2. Wait for Telegram message
3. Reply to bot with: "Makan siang"
4. Check Telegram for confirmation
5. Check Notion database

**Expected Results**:

- ✅ Telegram shows processing message: "⏳ Menyimpan transaksi ke Notion..."
- ✅ Telegram shows confirmation:

  ```
  ✅ Transaksi berhasil disimpan!

  📝 Nama: Makan siang
  💰 Jumlah: Rp 75.000
  📅 Tanggal: 19/01/2026
  ```

- ✅ New page created in Notion database with:
  - Name: "Makan siang"
  - Amount: 75000
  - Date: Today's date
  - Category: Linked to Living cost (if configured)
- ✅ Console log: "Transaction txn_xxx saved to Notion: page_id"
- ✅ Health check shows `cachedTransactions: 0` (cleared)

---

### 7. Telegram Bot Reply - No Pending Transaction

**Objective**: Test bot behavior when no transaction is pending.

**Steps**:

1. Ensure cache is empty (restart server or wait for TTL)
2. Send any text message to bot

**Expected Results**:

- ✅ Bot replies: "❌ Tidak ada transaksi yang menunggu konfirmasi."

---

### 8. Telegram Bot - Unauthorized Chat

**Objective**: Test bot ignores messages from other users.

**Steps**:

1. Send message to bot from a different Telegram account

**Expected Results**:

- ✅ Console log: "⚠️ Ignoring message from unauthorized chat: xxx"
- ✅ No response from bot

---

### 9. Cache Expiration

**Objective**: Test transaction cache TTL (1 hour).

**Steps**:

1. Send transaction via webhook
2. Wait for cache to expire (or modify TTL to 10 seconds for testing)
3. Try to reply to bot

**Expected Results**:

- ✅ Bot replies: "❌ Transaksi tidak ditemukan atau sudah kadaluarsa."

---

### 10. Multiple Concurrent Transactions

**Objective**: Test handling of multiple transactions.

**Steps**:

1. Send first transaction (amount: 10000)
2. Send second transaction (amount: 20000)
3. Reply to bot with description

**Expected Results**:

- ✅ Both transactions cached
- ✅ Bot processes the most recent transaction
- ✅ Transaction saved to Notion with correct amount

**Note**: Current implementation processes the first cached transaction. For production, consider implementing a queue system or transaction correlation.

---

### 11. Notion API Error Handling

**Objective**: Test error handling when Notion API fails.

**Steps**:

1. Temporarily set invalid `NOTION_DATABASE_ID` in `.env`
2. Restart server
3. Send transaction and reply to bot

**Expected Results**:

- ✅ Bot replies: "❌ Terjadi kesalahan saat menyimpan transaksi. Silakan coba lagi."
- ✅ Console shows error message
- ✅ Transaction remains in cache

---

### 12. Environment Variables Validation

**Objective**: Test server behavior with missing environment variables.

**Steps**:

1. Remove `TELEGRAM_BOT_TOKEN` from `.env`
2. Try to start server

**Expected Results**:

- ✅ Server exits with error
- ✅ Console shows: "❌ Missing required environment variables: TELEGRAM_BOT_TOKEN"
- ✅ Message: "Please create a .env file based on .env.example"

---

## 📊 Test Results Template

| Test Case                 | Status            | Notes |
| ------------------------- | ----------------- | ----- |
| 1. Server Startup         | ⬜ Pass / ⬜ Fail |       |
| 2. Health Check           | ⬜ Pass / ⬜ Fail |       |
| 3. Valid Transaction      | ⬜ Pass / ⬜ Fail |       |
| 4. Missing Amount         | ⬜ Pass / ⬜ Fail |       |
| 5. Invalid Amount Type    | ⬜ Pass / ⬜ Fail |       |
| 6. Complete Flow          | ⬜ Pass / ⬜ Fail |       |
| 7. No Pending Transaction | ⬜ Pass / ⬜ Fail |       |
| 8. Unauthorized Chat      | ⬜ Pass / ⬜ Fail |       |
| 9. Cache Expiration       | ⬜ Pass / ⬜ Fail |       |
| 10. Multiple Transactions | ⬜ Pass / ⬜ Fail |       |
| 11. Notion Error          | ⬜ Pass / ⬜ Fail |       |
| 12. Env Validation        | ⬜ Pass / ⬜ Fail |       |

## 🔍 Additional Testing Recommendations

### Performance Testing

- Test with high volume of concurrent webhook requests
- Monitor memory usage with many cached transactions
- Test bot responsiveness under load

### Security Testing

- Test webhook endpoint with malformed JSON
- Test SQL injection attempts in transaction names
- Verify environment variables are not exposed

### Integration Testing

- Test with actual Android Notification Listener app
- Verify end-to-end flow in production environment
- Test with different Notion database schemas

## 📝 Notes

- Always test in a development environment first
- Keep test data separate from production data
- Document any issues or unexpected behavior
- Update test cases as features are added or modified

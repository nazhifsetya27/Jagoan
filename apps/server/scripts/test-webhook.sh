#!/bin/bash

# Test script for Jagoan webhook endpoint
# Usage: ./test-webhook.sh [amount] [type]
# Example: ./test-webhook.sh 50000 OUTGOING
# Example: ./test-webhook.sh 100000 INCOMING (will be ignored by server)

BASE_URL="${JAGOAN_URL:-http://localhost:8081}"
AMOUNT="${1:-10000}"
TYPE="${2:-OUTGOING}"

echo "🧪 Testing Jagoan Webhook Endpoint"
echo "=================================="
echo "📍 URL: $BASE_URL/webhook/transaction"
echo "💰 Amount: Rp $AMOUNT"
echo "📋 Type: $TYPE"
echo ""

# Test webhook endpoint
echo "📤 Sending POST request..."
response=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/webhook/transaction" \
  -H "Content-Type: application/json" \
  -d "{\"amount\": $AMOUNT, \"type\": \"$TYPE\"}")

# Extract body and status code
http_code=$(echo "$response" | tail -n1)
body=$(echo "$response" | sed '$d')

echo ""
echo "📥 Response (HTTP $http_code):"
echo "$body" | jq . 2>/dev/null || echo "$body"
echo ""

# Status check
if [ "$http_code" -eq 200 ]; then
  echo "✅ Test PASSED - Transaction received successfully"
  echo "💡 Check your Telegram bot for the confirmation message!"
elif [ "$http_code" -eq 400 ]; then
  echo "❌ Test FAILED - Bad request (400)"
elif [ "$http_code" -eq 500 ]; then
  echo "❌ Test FAILED - Server error (500)"
else
  echo "⚠️  Unexpected response code: $http_code"
fi

echo ""
echo "=================================="
echo "📊 Health Check:"
curl -s "$BASE_URL/health" | jq . 2>/dev/null || curl -s "$BASE_URL/health"

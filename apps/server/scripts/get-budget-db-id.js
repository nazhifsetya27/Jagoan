import "dotenv/config";
import { Client } from "@notionhq/client";

// One-off setup helper for overlay mode.
// Reads the Daily Transactions DB schema (NOTION_DATABASE_ID), finds the relation
// that links to your Budgeting DB, and prints the value to put in
// NOTION_BUDGET_DATABASE_ID. Also verifies the integration can read that DB and
// reports whether a "Date" property exists. Read-only — makes no changes.
//
// Run from apps/server:  node scripts/get-budget-db-id.js

const notion = new Client({ auth: process.env.NOTION_KEY });
// Inspect a specific Daily Transactions DB by passing its id as an argument,
// otherwise default to the configured NOTION_DATABASE_ID.
const NOTION_DATABASE_ID = process.argv[2] || process.env.NOTION_DATABASE_ID;
const NOTION_BUDGET_RELATION_NAME = process.env.NOTION_BUDGET_RELATION_NAME;

(async () => {
  if (!process.env.NOTION_KEY || !NOTION_DATABASE_ID) {
    console.error("❌ NOTION_KEY and NOTION_DATABASE_ID must be set in .env");
    process.exit(1);
  }

  try {
    console.log("🔎 Reading Daily Transactions schema...\n");
    const db = await notion.databases.retrieve({
      database_id: NOTION_DATABASE_ID,
    });

    // List properties + flag the ones we care about
    let hasDate = false;
    const relations = [];
    for (const [name, prop] of Object.entries(db.properties)) {
      console.log(`  - "${name}" (${prop.type})`);
      if (prop.type === "date") hasDate = true;
      if (prop.type === "relation") {
        relations.push({ name, databaseId: prop.relation?.database_id });
      }
    }

    console.log("");
    console.log(hasDate
      ? '✅ A "Date" property exists — the server\'s Date write is fine.'
      : '⚠️  No "Date" property found — the server writes one; tell Claude to drop it or use created_at.');
    console.log("");

    if (relations.length === 0) {
      console.error("❌ No relation property found on Daily Transactions.");
      process.exit(1);
    }

    // Prefer the relation named in NOTION_BUDGET_RELATION_NAME (e.g. "Budgeting")
    const chosen =
      relations.find((r) => r.name === NOTION_BUDGET_RELATION_NAME) ||
      relations[0];

    console.log("🎯 Budgeting relation:");
    console.log(`   relation property : "${chosen.name}"  ->  NOTION_BUDGET_RELATION_NAME=${chosen.name}`);
    console.log(`   target database id: ${chosen.databaseId}  ->  NOTION_BUDGET_DATABASE_ID=${chosen.databaseId}`);
    console.log("");

    // Verify the integration can actually read the Budgeting DB (auto-fetch needs this)
    console.log("🔐 Checking the integration can query the Budgeting DB...");
    try {
      const rows = await notion.databases.query({
        database_id: chosen.databaseId,
        page_size: 100,
      });
      const titleOf = (page) => {
        for (const p of Object.values(page.properties || {})) {
          if (p?.type === "title") {
            return (p.title || []).map((t) => t.plain_text).join("").trim();
          }
        }
        return "(untitled)";
      };
      console.log(`✅ Access OK — ${rows.results.length} categories the overlay will show:\n`);
      rows.results.forEach((page) => console.log(`   • ${titleOf(page)}`));
    } catch (e) {
      console.error(`❌ Cannot query the Budgeting DB: ${e.message}`);
      console.error("   Share that database with your integration:");
      console.error("   open the Budgeting DB → ••• → Connections → add your integration.");
    }
  } catch (error) {
    console.error("❌ Error:", error.message);
    process.exit(1);
  }
})();

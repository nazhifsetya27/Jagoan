import "dotenv/config";
import { Client } from "@notionhq/client";

// Verify a created transaction page, then archive (delete) it. Used to prove the
// end-to-end overlay write without leaving a test row behind.
// Usage: node scripts/check-and-archive-page.js <pageId>
const notion = new Client({ auth: process.env.NOTION_KEY });
const pageId = process.argv[2];

if (!pageId) {
  console.error("Usage: node scripts/check-and-archive-page.js <pageId>");
  process.exit(1);
}

const text = (rich) => (rich || []).map((t) => t.plain_text).join("");

(async () => {
  try {
    const page = await notion.pages.retrieve({ page_id: pageId });
    console.log("📄 Created page:");
    console.log(`   url: ${page.url}`);
    for (const [name, prop] of Object.entries(page.properties)) {
      if (prop.type === "title") console.log(`   ${name}: "${text(prop.title)}"`);
      else if (prop.type === "number") console.log(`   ${name}: ${prop.number}`);
      else if (prop.type === "relation")
        console.log(`   ${name}: relation -> [${prop.relation.map((r) => r.id).join(", ")}]`);
      else if (prop.type === "created_time") console.log(`   ${name}: ${prop.created_time}`);
    }

    await notion.pages.update({ page_id: pageId, archived: true });
    console.log("\n🗑️  Archived (deleted) the test page.");
  } catch (e) {
    console.error("❌ Error:", e.message);
    process.exit(1);
  }
})();

import "dotenv/config";
import { Client } from "@notionhq/client";

// Prints which Notion integration the server's NOTION_KEY authenticates as.
const notion = new Client({ auth: process.env.NOTION_KEY });

(async () => {
  try {
    const me = await notion.users.me();
    console.log("🤖 This server authenticates as integration:");
    console.log(`   name : ${me.name}`);
    console.log(`   id   : ${me.id}`);
    console.log(`   type : ${me.type}`);
  } catch (e) {
    console.error("❌ Error:", e.message);
    process.exit(1);
  }
})();

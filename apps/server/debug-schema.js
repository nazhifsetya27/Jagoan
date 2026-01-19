import "dotenv/config";
import { Client } from "@notionhq/client";

const notion = new Client({ auth: process.env.NOTION_KEY });
const NOTION_DATABASE_ID = process.env.NOTION_DATABASE_ID;

(async () => {
  try {
    console.log("Fetching database schema...\n");
    const db = await notion.databases.retrieve({
      database_id: NOTION_DATABASE_ID,
    });

    console.log("📊 Database Properties:\n");
    Object.keys(db.properties).forEach((propName) => {
      const prop = db.properties[propName];
      console.log(`- "${propName}" (${prop.type})`);
    });

    console.log("\n✅ Done! Look for a relation property above.");
  } catch (error) {
    console.error("❌ Error:", error.message);
  }
})();

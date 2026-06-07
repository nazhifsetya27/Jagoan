import "dotenv/config";
import { Client } from "@notionhq/client";

// List every database the integration can see, grouped by the parent page it
// lives under (e.g. "June"), so we can identify the right month's databases.
const notion = new Client({ auth: process.env.NOTION_KEY });

const pageTitleCache = new Map();

async function parentLabel(parent) {
  if (!parent) return "(unknown)";
  if (parent.type === "page_id") {
    if (pageTitleCache.has(parent.page_id)) return pageTitleCache.get(parent.page_id);
    let label = `page ${parent.page_id}`;
    try {
      const page = await notion.pages.retrieve({ page_id: parent.page_id });
      for (const p of Object.values(page.properties || {})) {
        if (p?.type === "title") {
          label = (p.title || []).map((t) => t.plain_text).join("").trim() || label;
          break;
        }
      }
    } catch {
      /* keep id label */
    }
    pageTitleCache.set(parent.page_id, label);
    return label;
  }
  if (parent.type === "workspace") return "(workspace root)";
  if (parent.type === "data_source_id") return `data source ${parent.data_source_id}`;
  return `(${parent.type})`;
}

(async () => {
  try {
    let cursor = undefined;
    const dbs = [];
    do {
      const resp = await notion.search({
        filter: { property: "object", value: "database" },
        page_size: 100,
        start_cursor: cursor,
      });
      for (const r of resp.results) {
        const title = (r.title || []).map((t) => t.plain_text).join("").trim();
        dbs.push({ id: r.id, title: title || "(untitled)", parent: r.parent });
      }
      cursor = resp.has_more ? resp.next_cursor : undefined;
    } while (cursor);

    // Resolve parent labels and group
    const enriched = [];
    for (const d of dbs) {
      enriched.push({ ...d, parentTitle: await parentLabel(d.parent) });
    }
    enriched.sort((a, b) => a.parentTitle.localeCompare(b.parentTitle) || a.title.localeCompare(b.title));

    console.log(`📚 ${enriched.length} databases, grouped by parent page:\n`);
    let lastParent = null;
    for (const d of enriched) {
      if (d.parentTitle !== lastParent) {
        console.log(`\n📁 ${d.parentTitle}`);
        lastParent = d.parentTitle;
      }
      console.log(`   ${d.id}  —  ${d.title}`);
    }
  } catch (e) {
    console.error("❌ Error:", e.message);
    process.exit(1);
  }
})();

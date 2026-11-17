// Prompt Pro: the full archive. Every card is kept, one file per month
// (archive/2026-11.json), plus archive/index.json listing the months.
// The files live on the repo's "archive" branch, so nothing is lost
// when GitHub Pages republishes the site.
import { mkdir, readFile, writeFile, readdir } from "node:fs/promises";
import { join } from "node:path";

const month = (iso) => String(iso).slice(0, 7);

function compact(card) {
  const { originalTitle, ...rest } = card;
  return rest;
}

async function readJson(path, fallback) {
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch {
    return fallback;
  }
}

/**
 * Adds or updates every card in the current feed. Cards that were merged into
 * another story (still inside the feed's window but no longer in the feed)
 * are removed, so the archive doesn't hold duplicates.
 */
export async function updateArchive(dir, feedCards) {
  if (feedCards.length === 0) return { months: 0, total: 0 };
  await mkdir(dir, { recursive: true });
  const inFeed = new Map(feedCards.map((c) => [c.id, c]));
  const times = feedCards.map((c) => Date.parse(c.publishedAt)).filter(Number.isFinite);
  const oldest = times.length ? Math.min(...times) : Date.now();
  const byMonth = new Map();
  for (const c of feedCards) {
    if (!Number.isFinite(Date.parse(c.publishedAt))) continue;
    const m = month(c.publishedAt);
    if (!byMonth.has(m)) byMonth.set(m, []);
    byMonth.get(m).push(c);
  }
  for (const [m, cards] of byMonth) {
    const path = join(dir, `${m}.json`);
    const existing = (await readJson(path, { cards: [] })).cards ?? [];
    const kept = existing.filter((c) => {
      if (inFeed.has(c.id)) return false; // replaced below with the latest version
      return Date.parse(c.publishedAt) < oldest; // older than the feed: keep as is
    });
    const merged = [...cards.map(compact), ...kept].sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
    await writeFile(path, JSON.stringify({ month: m, cards: merged }));
  }
  // Rebuild the index from whatever month files exist.
  const months = [];
  for (const f of (await readdir(dir)).filter((f) => /^\d{4}-\d{2}\.json$/.test(f)).sort().reverse()) {
    const data = await readJson(join(dir, f), { cards: [] });
    months.push({ month: f.slice(0, 7), count: data.cards?.length ?? 0 });
  }
  const total = months.reduce((n, m) => n + m.count, 0);
  await writeFile(join(dir, "index.json"), JSON.stringify({ updatedAt: new Date().toISOString(), total, months }));
  return { months: months.length, total };
}

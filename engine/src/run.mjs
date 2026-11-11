// Entry point: `node src/run.mjs`
//
// Environment:
//   ANTHROPIC_API_KEY            Claude API key that writes the cards
//   CLAUDE_MODEL                 optional, default claude-haiku-5-5
//   PREV_FEED_URL                the currently published feed.json (keeps earlier cards)
//   PREV_FEED_PATH               or a local file instead
//   OUT_DIR                      where to write feed.json (default ./site)
//   MAX_NEW                      most new cards per run (default 40, controls AI cost)
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { collect, buildFeed } from "./engine.mjs";
import { makeModel } from "./writer.mjs";
import { PRIVACY } from "./privacy.mjs";

const env = process.env;
const outDir = env.OUT_DIR || "site";

async function loadPrevious() {
  try {
    if (env.PREV_FEED_PATH) return JSON.parse(await readFile(env.PREV_FEED_PATH, "utf8"));
    if (env.PREV_FEED_URL) {
      const res = await fetch(env.PREV_FEED_URL, { signal: AbortSignal.timeout(20_000), cache: "no-store" });
      if (res.ok) return await res.json();
      console.log(`previous feed: HTTP ${res.status}, starting fresh`);
    }
  } catch (e) {
    console.log(`previous feed unavailable (${e.message}), starting fresh`);
  }
  return null;
}

const previous = await loadPrevious();
console.log(`previous cards: ${previous?.cards?.length ?? 0}`);

const { items, health } = await collect(fetch);
for (const [id, h] of Object.entries(health)) console.log(`  ${h.ok ? "ok  " : "FAIL"} ${id}${h.ok ? ` (${h.items})` : `: ${h.error}`}`);

const model = makeModel(env);
const { feed, stats } = await buildFeed({
  items,
  previous,
  model,
  fetchImpl: fetch,
  maxNew: Number(env.MAX_NEW) || 40,
  log: (m) => console.log(m),
});
console.log("stats", JSON.stringify(stats));

if (feed.cards.length === 0 && (previous?.cards?.length ?? 0) > 0) {
  console.error("Refusing to publish an empty feed over a non-empty one.");
  process.exit(1);
}

await mkdir(outDir, { recursive: true });
await writeFile(join(outDir, "feed.json"), JSON.stringify(feed));
await writeFile(join(outDir, "status.json"), JSON.stringify({ generatedAt: feed.generatedAt, stats, sources: health }, null, 1));
await writeFile(
  join(outDir, "index.html"),
  `<!doctype html><meta charset="utf-8"><title>PulseCast feed</title><p>PulseCast feed: ${feed.cards.length} cards, updated ${feed.generatedAt}. <a href="feed.json">feed.json</a> · <a href="status.json">status.json</a></p>`,
);
await writeFile(join(outDir, "privacy.html"), PRIVACY);
console.log(`wrote ${feed.cards.length} cards to ${outDir}/feed.json`);

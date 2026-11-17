// Entry point: `node src/run.mjs`
//
// Environment:
//   ANTHROPIC_API_KEY            Claude API key that writes the cards
//   CLAUDE_MODEL                 optional, default claude-haiku-5-5
//   PREV_FEED_URL                the currently published feed.json (keeps earlier cards)
//   PREV_FEED_PATH               or a local file instead
//   OUT_DIR                      where to write feed.json (default ./site)
//   MAX_NEW                      most new cards per run (default 40, controls AI cost)
//   ARCHIVE_DIR                  optional: a checkout of the repo's "archive" branch.
//                                Holds the Pro archive and weekly issues between runs.
import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { collect, buildFeed } from "./engine.mjs";
import { makeModel } from "./writer.mjs";
import { PRIVACY } from "./privacy.mjs";
import { updateArchive } from "./archive.mjs";
import { weeklyDue, writeWeekly, addIssue } from "./weekly.mjs";

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
  `<!doctype html><meta charset="utf-8"><title>Prompt feed</title><p>Prompt: AI News feed: ${feed.cards.length} cards, updated ${feed.generatedAt}. <a href="feed.json">feed.json</a> · <a href="status.json">status.json</a></p>`,
);
await writeFile(join(outDir, "privacy.html"), PRIVACY);

// Prompt Pro extras. Failures here never stop the feed from publishing.
if (env.ARCHIVE_DIR) {
  const dir = env.ARCHIVE_DIR;
  try {
    const a = await updateArchive(join(dir, "archive"), feed.cards);
    console.log(`archive: ${a.total} cards in ${a.months} months`);
  } catch (e) {
    console.log(`archive update failed: ${e.message}`);
  }
  const weeklyPath = join(dir, "weekly.json");
  let weekly = { issues: [] };
  try {
    weekly = JSON.parse(await readFile(weeklyPath, "utf8"));
  } catch {}
  if (weeklyDue(weekly.issues, Date.now()) || env.FORCE_WEEKLY === "1") {
    try {
      const issue = await writeWeekly(feed.cards, model, Date.now());
      if (issue) {
        weekly = { updatedAt: new Date().toISOString(), issues: addIssue(weekly.issues, issue) };
        await writeFile(weeklyPath, JSON.stringify(weekly));
        console.log(`weekly: wrote "${issue.title}"`);
      } else {
        console.log("weekly: not enough news or reply failed checks, will retry next run");
      }
    } catch (e) {
      console.log(`weekly failed (will retry next run): ${e.message}`);
    }
  }
  // Publish both alongside the feed.
  try {
    await cp(join(dir, "archive"), join(outDir, "archive"), { recursive: true });
  } catch {}
  await writeFile(join(outDir, "weekly.json"), JSON.stringify(weekly));
}
console.log(`wrote ${feed.cards.length} cards to ${outDir}/feed.json`);

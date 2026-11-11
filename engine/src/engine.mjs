// Collect -> curate -> write -> merge with the previous feed.
import { createHash } from "node:crypto";
import { SOURCES } from "./sources.mjs";
import { parseFeed, parseHn, parseHfPapers, parseGithub, extractArticle } from "./parse.mjs";
import { canonicalUrl, looksAboutAi, cluster, heat, cardType, similar } from "./curate.mjs";
import { writeCard } from "./writer.mjs";

export const FEED_VERSION = 1;
const MAX_AGE_DAYS = 10;
const MAX_CARDS = 400;
const FRESH_HOURS = 72; // new stories must be newer than this
const UA = "PulseCast/1.0 (AI news reader; +https://github.com)";

async function getText(fetchImpl, url, timeoutMs = 20_000, accept = "*/*") {
  const res = await fetchImpl(url, {
    headers: { "user-agent": UA, accept },
    redirect: "follow",
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.text();
}

export async function fetchSource(source, fetchImpl, now = Date.now()) {
  const since = new Date(now - 7 * 86_400_000).toISOString().slice(0, 10);
  const url = source.url.replace("{SINCE}", since);
  const body = await getText(fetchImpl, url, 20_000, source.kind === "rss" ? "application/rss+xml, application/atom+xml, application/xml, text/xml" : "application/json");
  let items;
  switch (source.kind) {
    case "rss": items = parseFeed(body); break;
    case "hn": items = parseHn(JSON.parse(body)); break;
    case "hfpapers": items = parseHfPapers(JSON.parse(body)); break;
    case "github": items = parseGithub(JSON.parse(body)); break;
    default: items = [];
  }
  return items.map((i) => ({ ...i, source }));
}

export async function collect(fetchImpl, sources = SOURCES, now = Date.now()) {
  const health = {};
  const all = [];
  await mapLimit(sources, 6, async (s) => {
    try {
      const items = await fetchSource(s, fetchImpl, now);
      health[s.id] = { ok: true, items: items.length };
      all.push(...items);
    } catch (e) {
      health[s.id] = { ok: false, error: String(e.message ?? e).slice(0, 120) };
    }
  });
  return { items: all, health };
}

async function mapLimit(list, limit, fn) {
  const out = new Array(list.length);
  let next = 0;
  async function worker() {
    while (next < list.length) {
      const i = next++;
      out[i] = await fn(list[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, list.length) }, worker));
  return out;
}

export function cardId(url) {
  return createHash("sha1").update(canonicalUrl(url)).digest("hex").slice(0, 12);
}

function matchesCard(card, story) {
  const urls = new Set([card.source.url, ...(card.alsoCoveredBy ?? []).map((o) => o.url)].map(canonicalUrl));
  return story.members.some((m) => urls.has(canonicalUrl(m.url)) || similar(m.title, card.originalTitle ?? card.headline));
}

/**
 * Pure-ish core: given raw items and the previous feed, returns the next feed.
 * `model(system, user)` writes cards; `fetchImpl` is only used for article text.
 */
export async function buildFeed({ items, previous, model, fetchImpl, now = Date.now(), maxNew = 40, log = () => {} }) {
  const prevCards = Array.isArray(previous?.cards) ? previous.cards.map((c) => ({ ...c, alsoCoveredBy: [...(c.alsoCoveredBy ?? [])] })) : [];
  const fresh = items.filter((i) => {
    if (!i.source.aiOnly && !looksAboutAi(i)) return false;
    const t = Date.parse(i.publishedAt ?? "");
    return Number.isNaN(t) ? true : now - t < FRESH_HOURS * 3_600_000 && t <= now + 3_600_000;
  });
  const stories = cluster(fresh).map((s) => ({ ...s, heat: heat(s, now) }));

  // Stories already in the feed: add new outlets and raise heat, don't rewrite.
  const newStories = [];
  for (const story of stories) {
    const existing = prevCards.find((c) => matchesCard(c, story));
    if (!existing) {
      newStories.push(story);
      continue;
    }
    const known = new Set([existing.source.url, ...existing.alsoCoveredBy.map((o) => o.url)].map(canonicalUrl));
    for (const m of story.members) {
      if (m.source.kind === "hn" && m.discussionUrl && !existing.discussionUrl) existing.discussionUrl = m.discussionUrl;
      if (known.has(canonicalUrl(m.url)) || m.source.kind === "hn") continue;
      existing.alsoCoveredBy.push({ name: m.source.name, url: m.url });
      known.add(canonicalUrl(m.url));
    }
    existing.alsoCoveredBy = existing.alsoCoveredBy.slice(0, 8);
    existing.heat = Math.max(existing.heat ?? 0, story.heat);
  }

  // Write the hottest new stories first, within the per-run budget.
  newStories.sort((a, b) => b.heat - a.heat);
  const toWrite = newStories.slice(0, maxNew);
  log(`stories: ${stories.length}, new: ${newStories.length}, writing: ${toWrite.length}`);

  const written = await mapLimit(toWrite, 4, async (story) => {
    const p = story.primary;
    if (p.source.kind === "rss" || p.source.kind === "hn") {
      try {
        const html = await getText(fetchImpl, p.url, 15_000, "text/html");
        story.articleText = extractArticle(html.slice(0, 2_000_000));
      } catch {
        story.articleText = "";
      }
    }
    if (!story.articleText && !p.snippet) return null; // nothing to write from
    try {
      const body = await writeCard(story, model);
      if (!body) return null;
      const hn = story.members.find((m) => m.source.kind === "hn" && m.discussionUrl);
      const seen = new Set([canonicalUrl(p.url)]);
      const also = [];
      for (const o of story.others) {
        if (o.source.kind === "hn" || seen.has(canonicalUrl(o.url))) continue;
        seen.add(canonicalUrl(o.url));
        also.push({ name: o.source.name, url: o.url });
      }
      return {
        id: cardId(p.url),
        type: cardType(story),
        ...body,
        source: { name: p.source.name, url: p.url },
        alsoCoveredBy: also.slice(0, 8),
        ...(hn ? { discussionUrl: hn.discussionUrl } : {}),
        publishedAt: p.publishedAt ?? new Date(now).toISOString(),
        addedAt: new Date(now).toISOString(),
        heat: story.heat,
        originalTitle: p.title,
      };
    } catch (e) {
      log(`write failed for ${p.url}: ${e.message}`);
      return null;
    }
  });

  const newCards = written.filter(Boolean);
  const ids = new Set();
  const cutoff = now - MAX_AGE_DAYS * 86_400_000;
  const cards = [...newCards, ...prevCards]
    .filter((c) => {
      if (ids.has(c.id)) return false;
      ids.add(c.id);
      return (Date.parse(c.publishedAt) || now) >= cutoff;
    })
    .sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt))
    .slice(0, MAX_CARDS);

  return {
    feed: { version: FEED_VERSION, generatedAt: new Date(now).toISOString(), cards },
    stats: { items: items.length, fresh: fresh.length, stories: stories.length, newStories: newStories.length, written: newCards.length, declined: toWrite.length - newCards.length, total: cards.length },
  };
}

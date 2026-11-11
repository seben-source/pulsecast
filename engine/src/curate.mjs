// Filtering, de-duplication and "heat" scoring. No network, fully testable.
import { AI_TERMS } from "./sources.mjs";

const TRACKING = /^(utm_|fbclid$|gclid$|mc_|ref$|ref_src$|cmpid$|ocid$|taid$|smid$)/i;

export function canonicalUrl(raw) {
  try {
    const u = new URL(raw);
    u.hash = "";
    u.hostname = u.hostname.toLowerCase().replace(/^www\./, "").replace(/^m\./, "");
    for (const key of [...u.searchParams.keys()]) if (TRACKING.test(key)) u.searchParams.delete(key);
    let s = `${u.hostname}${u.pathname.replace(/\/+$/, "")}`;
    const q = u.searchParams.toString();
    if (q) s += `?${q}`;
    return s.toLowerCase();
  } catch {
    return raw.trim().toLowerCase();
  }
}

const AI_RE = new RegExp(
  `(^|[^a-z0-9])(${AI_TERMS.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})([^a-z0-9]|$)`,
  "i",
);

export function looksAboutAi(item) {
  return AI_RE.test(`${item.title} ${item.snippet ?? ""}`);
}

const STOP = new Set("a an the and or of to in on for with by from at as is are was were be its it this that new how why what who will can about into after over says say just more than vs".split(" "));

export function titleTokens(title) {
  return new Set(
    title
      .toLowerCase()
      .replace(/[’']/g, "")
      .split(/[^a-z0-9.\-]+/) // keeps names like gpt-6 or llama-4 whole
      .map((w) => w.replace(/^[.\-]+|[.\-]+$/g, ""))
      .filter((w) => w.length > 1 && !STOP.has(w)),
  );
}

export function similar(a, b) {
  return similarTokens(titleTokens(a), titleTokens(b));
}

function similarTokens(ta, tb) {
  if (ta.size === 0 || tb.size === 0) return false;
  let shared = 0;
  for (const w of ta) if (tb.has(w)) shared++;
  const jaccard = shared / (ta.size + tb.size - shared);
  return shared >= 3 && jaccard >= 0.5;
}

/**
 * Groups items that are the same story (same link, or near-identical titles).
 * Returns stories: { primary, others[], sources[] }.
 * The primary is the official source if there is one, else the earliest.
 */
export function cluster(items) {
  // Union-find: any two items with the same link or near-identical titles end
  // up in one story, whatever order they arrive in.
  const parent = items.map((_, i) => i);
  const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])));
  const union = (a, b) => {
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent[rb] = ra;
  };
  const firstByUrl = new Map();
  const tokens = items.map((it) => titleTokens(it.title));
  items.forEach((item, i) => {
    const key = canonicalUrl(item.url);
    if (firstByUrl.has(key)) union(firstByUrl.get(key), i);
    else firstByUrl.set(key, i);
    for (let j = 0; j < i; j++) if (similarTokens(tokens[i], tokens[j])) union(j, i);
  });
  const groups = new Map();
  items.forEach((item, i) => {
    const r = find(i);
    if (!groups.has(r)) groups.set(r, []);
    groups.get(r).push(item);
  });
  return [...groups.values()].map((members) => {
    const sorted = [...members].sort((a, b) => {
      if (!!b.source.official !== !!a.source.official) return b.source.official ? 1 : -1;
      if (!!b.source.aiOnly !== !!a.source.aiOnly && a.source.kind !== "hn" && b.source.kind !== "hn") return b.source.aiOnly ? 1 : -1;
      return (Date.parse(a.publishedAt ?? 0) || 0) - (Date.parse(b.publishedAt ?? 0) || 0);
    });
    // Hacker News points a story at the original article: prefer a non-HN primary.
    const primary = sorted.find((m) => m.source.kind !== "hn") ?? sorted[0];
    return { primary, others: members.filter((m) => m !== primary), members };
  });
}

/**
 * 0–100: how much attention a story is getting.
 * More outlets covering it, Hacker News points, paper upvotes, GitHub stars
 * and source weight push it up; age pulls it down.
 */
export function heat(story, now = Date.now()) {
  const outlets = new Set(story.members.map((m) => m.source.id)).size;
  let score = 18 * Math.log2(1 + outlets);
  for (const m of story.members) {
    score += 6 * (m.source.weight ?? 1);
    if (m.source.kind === "hn") score += Math.min(30, 7 * Math.log2(1 + m.points / 50));
    if (m.source.kind === "hfpapers") score += Math.min(20, 5 * Math.log2(1 + m.points / 10));
    if (m.source.kind === "github") score += Math.min(20, 5 * Math.log2(1 + m.points / 200));
  }
  const published = Date.parse(story.primary.publishedAt ?? "") || now;
  const ageHours = Math.max(0, (now - published) / 3_600_000);
  score *= Math.pow(0.5, ageHours / 36); // halves every 36 hours
  return Math.round(Math.max(1, Math.min(100, score)));
}

/** Type of card, from the source kind. */
export function cardType(story) {
  const kinds = new Set(story.members.map((m) => m.source.kind));
  if (story.primary.source.kind === "hfpapers") return "research";
  if (story.primary.source.kind === "github") return "tool";
  if (kinds.has("hfpapers")) return "research";
  if (story.primary.source.official) return "release";
  return "news";
}

/**
 * Names in a card headline: capitalised words after the first one (Claude
 * writes headlines in sentence case, so these are products, companies, people).
 */
export function headlineNames(headline) {
  return headline
    .split(/\s+/)
    .slice(1)
    .map((w) => w.replace(/[’']s$/, "").replace(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/g, ""))
    .filter((w) => w.length >= 3 && (/^[A-Z]/.test(w) || (/\d/.test(w) && /[a-z]/i.test(w))))
    .map((w) => w.toLowerCase());
}

/**
 * Second pass over finished cards: merges cards about the same story whose
 * original headlines were worded too differently to match up front, e.g.
 * "Try Tabletop: collaborative spaces..." (Google) and "Google's Tabletop AI
 * experiment..." (The Verge). Two cards merge when, within 48 hours, they share
 * a rare name (in at most 3 cards) and at least two other headline words.
 * The company's own post, or else the earlier card, is kept.
 */
export function mergeDuplicateCards(cards) {
  const names = cards.map((c) => new Set(headlineNames(c.headline)));
  const df = new Map();
  for (const set of names) for (const n of set) df.set(n, (df.get(n) ?? 0) + 1);
  const tokens = cards.map((c) => titleTokens(c.headline));
  const removed = new Set();
  for (let i = 0; i < cards.length; i++) {
    if (removed.has(i)) continue;
    for (let j = i + 1; j < cards.length; j++) {
      if (removed.has(j)) continue;
      const gap = Math.abs(Date.parse(cards[i].publishedAt) - Date.parse(cards[j].publishedAt));
      if (!(gap <= 48 * 3_600_000)) continue;
      const rare = [...names[i]].filter((n) => names[j].has(n) && df.get(n) <= 3);
      if (rare.length === 0) continue;
      // Besides the name, at least two more headline words in common.
      const rareSet = new Set(rare);
      let shared = 0;
      for (const t of tokens[i]) if (tokens[j].has(t) && !rareSet.has(t)) shared++;
      if (shared < 2) continue;
      const iWins = cards[i].type === "release" && cards[j].type !== "release"
        ? true
        : cards[j].type === "release" && cards[i].type !== "release"
          ? false
          : Date.parse(cards[i].publishedAt) <= Date.parse(cards[j].publishedAt);
      const [keep, drop] = iWins ? [i, j] : [j, i];
      const k = cards[keep];
      const d = cards[drop];
      const known = new Set([k.source.url, ...k.alsoCoveredBy.map((o) => o.url)].map(canonicalUrl));
      for (const link of [d.source, ...(d.alsoCoveredBy ?? [])]) {
        if (known.has(canonicalUrl(link.url))) continue;
        known.add(canonicalUrl(link.url));
        k.alsoCoveredBy.push({ name: link.name, url: link.url });
      }
      k.alsoCoveredBy = k.alsoCoveredBy.slice(0, 8);
      k.heat = Math.max(k.heat ?? 0, d.heat ?? 0) + 4; // more outlets: a bit hotter
      if (!k.discussionUrl && d.discussionUrl) k.discussionUrl = d.discussionUrl;
      removed.add(drop);
      if (drop === i) break;
    }
  }
  return cards.filter((_, i) => !removed.has(i));
}

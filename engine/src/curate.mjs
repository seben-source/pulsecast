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

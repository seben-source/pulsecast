// Prompt Pro: the weekly deep-dive, written every Sunday (Brisbane time)
// from the week's own cards. Claude only rearranges and explains what the
// cards already say; it gets no outside facts.
import { extractJson } from "./writer.mjs";

const TZ_OFFSET_HOURS = 10; // Australia/Brisbane, no daylight saving
const KEEP_ISSUES = 26; // about six months of issues

/** Brisbane calendar date (YYYY-MM-DD) and weekday (0 = Sunday) for a time. */
export function localDay(now) {
  const d = new Date(now + TZ_OFFSET_HOURS * 3_600_000);
  return { date: d.toISOString().slice(0, 10), weekday: d.getUTCDay() };
}

/** True on a Sunday that doesn't have an issue yet. */
export function weeklyDue(issues, now) {
  const { date, weekday } = localDay(now);
  return weekday === 0 && !(issues ?? []).some((i) => i.weekOf === date);
}

export const WEEKLY_SYSTEM = `You write "The week in AI", a Sunday deep-dive for Prompt, an app that explains AI news to curious non-experts.
You get this week's biggest stories as cards: id, headline, summary, why it matters and key points.
Use ONLY those cards. Never add facts, numbers, names, quotes or dates that aren't in them.

Pick the 3 to 5 threads that mattered most this week. A thread can join several related cards (for example several companies releasing models, or a policy fight across a few stories). Explain what happened, how the pieces connect, and why it matters, in plain English with short sentences. No hype words (revolutionary, game-changing, groundbreaking), no emoji, no hashtags.

Return JSON only, no markdown:
{
  "title": string,      // max 70 characters, names the week's biggest theme
  "intro": string,      // 2 sentences, max 320 characters
  "sections": [         // 3 to 5
    { "heading": string,  // max 60 characters
      "body": string,     // 3 to 5 sentences, max 700 characters
      "cardIds": [string] // the ids of the cards this section draws on
    }
  ],
  "takeaway": string    // 1 to 2 sentences, max 260 characters: the one thing to remember from this week
}`;

const str = (v, max) => (typeof v === "string" ? v.trim() : "").slice(0, max);

export function validateIssue(d, cardIds) {
  if (!d || typeof d !== "object") return null;
  const sections = (Array.isArray(d.sections) ? d.sections : [])
    .map((s) => ({
      heading: str(s?.heading, 80),
      body: str(s?.body, 900),
      cardIds: (Array.isArray(s?.cardIds) ? s.cardIds : []).filter((id) => typeof id === "string" && cardIds.has(id)).slice(0, 8),
    }))
    .filter((s) => s.heading.length >= 4 && s.body.length >= 80 && s.cardIds.length > 0)
    .slice(0, 5);
  const title = str(d.title, 90);
  const intro = str(d.intro, 420);
  if (title.length < 8 || intro.length < 40 || sections.length < 2) return null;
  if (/[#]|game-?changing|revolutionary|groundbreaking/i.test(`${title} ${intro} ${sections.map((s) => s.body).join(" ")}`)) return null;
  return { title, intro, sections, takeaway: str(d.takeaway, 320) };
}

/** Writes this Sunday's issue from the last 7 days of cards. Returns null if there isn't enough news. */
export async function writeWeekly(cards, model, now) {
  const weekAgo = now - 7 * 86_400_000;
  const pool = cards
    .filter((c) => Date.parse(c.publishedAt) >= weekAgo)
    .sort((a, b) => (b.heat ?? 0) - (a.heat ?? 0))
    .slice(0, 30);
  if (pool.length < 6) return null;
  const list = pool
    .map((c) => [`id: ${c.id}`, `headline: ${c.headline}`, `summary: ${c.summary}`, `why it matters: ${c.whyItMatters}`, `key points: ${(c.keyPoints ?? []).join(" / ")}`].join("\n"))
    .join("\n\n");
  const reply = await model(WEEKLY_SYSTEM, list, { maxTokens: 3000 });
  const issue = validateIssue(extractJson(reply), new Set(pool.map((c) => c.id)));
  if (!issue) return null;
  const used = new Set(issue.sections.flatMap((s) => s.cardIds));
  return {
    weekOf: localDay(now).date,
    publishedAt: new Date(now).toISOString(),
    ...issue,
    // The cards it draws on, kept with the issue so it still reads after they leave the feed.
    cards: pool
      .filter((c) => used.has(c.id))
      .map((c) => ({ id: c.id, headline: c.headline, source: c.source, publishedAt: c.publishedAt, image: c.image ?? null })),
  };
}

export function addIssue(issues, issue) {
  return [issue, ...(issues ?? []).filter((i) => i.weekOf !== issue.weekOf)]
    .sort((a, b) => b.weekOf.localeCompare(a.weekOf))
    .slice(0, KEEP_ISSUES);
}

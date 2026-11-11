// Writes a card for one story with Claude, from the story's own text.

export const TOPICS = ["Models", "Tools", "Research", "Business", "Policy", "Robotics", "Chips", "Open source", "Creative"];

export const SYSTEM = `You write cards for PulseCast, a feed that explains AI news to curious non-experts.
You are given one story: its headline, source and the article text. Write ONLY from that text.
Never add facts, numbers, names, quotes, dates or claims that are not in the text. If the text is too thin to say anything concrete, set "keep" to false.
Set "keep" to false as well if the story is not mainly about AI, is an advert or job post, a tutorial or how-to guide, a customer case study, a webinar or event promotion, or a minor product tweak few people would care about.

Style: plain English, short sentences, no hype words (revolutionary, game-changing, groundbreaking), no emoji, no hashtags. Explain jargon in a few words the first time it appears.

Return JSON only, no markdown, with exactly these fields:
{
  "keep": boolean,
  "headline": string,       // max 90 characters, factual, says what happened
  "summary": string,        // exactly 2 sentences, max 320 characters
  "why_it_matters": string, // 1 sentence, max 200 characters: why a normal person or AI user should care
  "key_points": [string, string, string], // 3 short facts from the text, max 140 characters each
  "topic": one of ${JSON.stringify(TOPICS)},
  "context": string,        // max 2 sentences: background from the text that helps understand it
  "what_next": string       // max 2 sentences: what the text says happens next; "" if it doesn't say
}`;

function userPrompt(story) {
  const p = story.primary;
  const body = story.articleText?.length > 200 ? story.articleText : p.snippet || "(no article text available)";
  const others = story.others.map((o) => o.source.name);
  return [
    `Headline: ${p.title}`,
    `Source: ${p.source.name}${p.source.official ? " (the company's own announcement)" : ""}`,
    others.length ? `Also covered by: ${[...new Set(others)].join(", ")}` : "",
    p.source.kind === "github" ? "This is a GitHub project, not a news article. Describe what the tool does and who it is for." : "",
    p.source.kind === "hfpapers" ? "This is a research paper abstract. Explain the finding simply and do not overstate it." : "",
    "",
    "Article text:",
    body,
  ]
    .filter((l) => l !== "")
    .join("\n");
}

function extractJson(text) {
  const cleaned = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  if (start < 0 || end <= start) throw new Error("no JSON object in reply");
  return JSON.parse(cleaned.slice(start, end + 1));
}

const str = (v, max) => (typeof v === "string" ? v.trim() : "").slice(0, max);

/** Checks the model's reply; returns a clean card body or null. */
export function validateDraft(d) {
  if (!d || d.keep !== true) return null;
  const headline = str(d.headline, 120);
  const summary = str(d.summary, 420);
  const why = str(d.why_it_matters, 260);
  const points = Array.isArray(d.key_points) ? d.key_points.map((p) => str(p, 180)).filter((p) => p.length >= 8) : [];
  if (headline.length < 12 || summary.length < 60 || why.length < 20 || points.length < 2) return null;
  if (/[#]|game-?changing|revolutionary|groundbreaking/i.test(`${headline} ${summary}`)) return null;
  return {
    headline,
    summary,
    whyItMatters: why,
    keyPoints: points.slice(0, 3),
    topic: TOPICS.includes(d.topic) ? d.topic : "Business",
    context: str(d.context, 360),
    whatNext: str(d.what_next, 360),
  };
}

export function makeModel(rawEnv, fetchImpl = fetch) {
  // CI systems pass unset settings as empty strings; treat those as unset.
  const env = Object.fromEntries(Object.entries(rawEnv).filter(([, v]) => v !== undefined && v !== ""));
  const key = env.ANTHROPIC_API_KEY;
  if (!key) throw new Error("ANTHROPIC_API_KEY is not set");
  const model = env.CLAUDE_MODEL || "claude-haiku-5-5";
  return async (system, user) => {
    const res = await fetchImpl("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model, max_tokens: 1500, system, messages: [{ role: "user", content: user }] }),
      signal: AbortSignal.timeout(60_000),
    });
    if (!res.ok) throw new Error(`Claude API ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const data = await res.json();
    return (data.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("");
  };
}

/** One story -> card body, or null if the model declines or the reply is unusable. */
export async function writeCard(story, model) {
  const reply = await model(SYSTEM, userPrompt(story));
  let draft;
  try {
    draft = extractJson(reply);
  } catch {
    return { card: null, reason: "unreadable reply" };
  }
  if (draft?.keep !== true) return { card: null, reason: "Claude skipped it" };
  const card = validateDraft(draft);
  return card ? { card, reason: null } : { card: null, reason: "failed quality checks" };
}

export const DEDUPE_SYSTEM = `You check a news feed for duplicate stories. You get a list of AI news cards as "id | source | headline".
Group together cards that report the SAME news event, including follow-ups about that same event from the following days (for example "X announces Y", "Y launches today" and "Shares jump after Y" are one group).
Do NOT group cards that merely share a company or topic but are about different events (for example a company's new model and the same company's lawsuit are different).
Return JSON only, no markdown: {"groups": [["id1","id2"], ...]}. Only include groups of 2 or more. If there are no duplicates return {"groups": []}.`;

/** One request: which recent cards are the same story? Returns arrays of ids. */
export async function findDuplicateGroups(cards, model) {
  if (cards.length < 2) return [];
  const list = cards.map((c) => `${c.id} | ${c.source.name} | ${c.headline}`).join("\n");
  const reply = await model(DEDUPE_SYSTEM, list);
  const parsed = extractJson(reply);
  const ids = new Set(cards.map((c) => c.id));
  return (Array.isArray(parsed?.groups) ? parsed.groups : [])
    .filter(Array.isArray)
    .map((g) => g.filter((id) => typeof id === "string" && ids.has(id)))
    .filter((g) => g.length >= 2 && g.length <= 8);
}

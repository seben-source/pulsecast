import { test } from "node:test";
import assert from "node:assert/strict";
import { parseFeed, parseHn, parseHfPapers, parseGithub, extractArticle, htmlToText } from "../src/parse.mjs";
import { canonicalUrl, looksAboutAi, similar, cluster, heat } from "../src/curate.mjs";
import { validateDraft, makeModel } from "../src/writer.mjs";
import { buildFeed, collect, cardId } from "../src/engine.mjs";

const NOW = Date.parse("2026-11-12T00:00:00Z");
const h = (hours) => new Date(NOW - hours * 3_600_000).toUTCString();
const iso = (hours) => new Date(NOW - hours * 3_600_000).toISOString();

const RSS = `<?xml version="1.0"?><rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>TC</title>
<item><title><![CDATA[OpenAI launches GPT-6 with longer memory &amp; tools]]></title>
<link>https://techcrunch.com/2026/11/11/openai-gpt-6/?utm_source=rss&amp;utm_medium=feed</link>
<pubDate>${h(5)}</pubDate><description><![CDATA[<p>OpenAI on Tuesday released <b>GPT-6</b>.</p>]]></description></item>
<item><title>Old story from last month</title><link>https://techcrunch.com/old</link><pubDate>${h(24 * 30)}</pubDate><description>x</description></item>
<item><title>No link item</title><pubDate>${h(1)}</pubDate></item>
</channel></rss>`;

const ATOM = `<?xml version="1.0" encoding="utf-8"?><feed xmlns="http://www.w3.org/2005/Atom"><title>Simon</title>
<entry><title>Trying out the new GPT-6 model</title><link href="https://simonwillison.net/2026/Nov/11/gpt-6/" rel="alternate"/>
<published>${iso(3)}</published><summary type="html">&lt;p&gt;Notes on GPT-6 and its tool use.&lt;/p&gt;</summary></entry>
<entry><title>My sourdough recipe</title><link href="https://simonwillison.net/2026/Nov/10/bread/"/><updated>${iso(10)}</updated><summary>Flour and water.</summary></entry>
</feed>`;

const OFFICIAL = `<rss><channel><item><title>Introducing GPT-6</title><link>https://openai.com/index/introducing-gpt-6/</link><pubDate>${h(6)}</pubDate><description>Today we are releasing GPT-6.</description></item></channel></rss>`;

const HN = { hits: [
  { title: "OpenAI launches GPT-6", url: "https://openai.com/index/introducing-gpt-6/", points: 900, num_comments: 400, created_at_i: Math.floor((NOW - 4 * 3_600_000) / 1000), objectID: "111" },
  { title: "Show HN: A faster SQLite", url: "https://example.com/sqlite", points: 300, created_at_i: Math.floor((NOW - 2 * 3_600_000) / 1000), objectID: "222" },
  { title: "Ask HN: no url", points: 200, created_at_i: Math.floor(NOW / 1000), objectID: "333" },
] };

const PAPERS = [
  { paper: { id: "2611.01234", title: "Scaling Laws for\n Reasoning Agents", summary: "We study how reasoning agents scale with compute and find predictable gains.", upvotes: 120 }, publishedAt: iso(20) },
  { paper: { title: "missing id" } },
];

const GITHUB = { items: [{ full_name: "acme/tiny-llm", html_url: "https://github.com/acme/tiny-llm", description: "Run a small LLM on your laptop", stargazers_count: 2400, language: "Rust", topics: ["llm"], created_at: iso(30) }] };

const ARTICLE = `<html><head><script>var x=1</script></head><body><nav><p>Menu menu menu menu menu menu menu menu menu</p></nav>
<article><h1>OpenAI launches GPT-6</h1><p>OpenAI released GPT-6 on Tuesday, a model that can remember long conversations and use tools.</p>
<p>The company said it would be available to paying ChatGPT users first, with free users getting access later this month.</p><p>short</p></article>
<footer><p>Copyright footer text that should not be included in the article body at all.</p></footer></body></html>`;

test("RSS: CDATA, entities, tracking links, missing links", () => {
  const items = parseFeed(RSS);
  assert.equal(items.length, 2);
  assert.equal(items[0].title, "OpenAI launches GPT-6 with longer memory & tools");
  assert.equal(items[0].snippet, "OpenAI on Tuesday released GPT-6 .");
  assert.ok(items[0].publishedAt.startsWith("2026-11-11"));
});

test("Atom: alternate links, published/updated, escaped HTML summary", () => {
  const items = parseFeed(ATOM);
  assert.equal(items.length, 2);
  assert.equal(items[0].url, "https://simonwillison.net/2026/Nov/11/gpt-6/");
  assert.equal(items[0].snippet, "Notes on GPT-6 and its tool use.");
});

test("HN, papers and GitHub parsers", () => {
  const hn = parseHn(HN);
  assert.equal(hn.length, 2);
  assert.equal(hn[0].discussionUrl, "https://news.ycombinator.com/item?id=111");
  const papers = parseHfPapers(PAPERS);
  assert.equal(papers.length, 1);
  assert.equal(papers[0].title, "Scaling Laws for Reasoning Agents");
  assert.equal(papers[0].url, "https://huggingface.co/papers/2611.01234");
  assert.equal(papers[0].points, 120);
  assert.equal(parseGithub(GITHUB)[0].points, 2400);
});

test("article extraction keeps the article body only", () => {
  const text = extractArticle(ARTICLE);
  assert.match(text, /remember long conversations/);
  assert.match(text, /paying ChatGPT users/);
  assert.doesNotMatch(text, /Menu|Copyright|var x/);
  assert.equal(htmlToText("a &#8217; b &#x2014; c"), "a ’ b — c");
});

test("canonical URLs ignore tracking, www, trailing slash", () => {
  assert.equal(canonicalUrl("https://www.Example.com/a/b/?utm_source=x&id=2#top"), canonicalUrl("https://example.com/a/b?id=2"));
});

test("AI filter and story matching", () => {
  assert.ok(looksAboutAi({ title: "Nvidia earnings beat on data-center GPUs" }));
  assert.ok(looksAboutAi({ title: "Anthropic ships Claude update" }));
  assert.ok(!looksAboutAi({ title: "My sourdough recipe", snippet: "Flour and water." }));
  assert.ok(!looksAboutAi({ title: "Said the man in Spain", snippet: "maintain" }), "no substring matches like 'ai' in 'Spain'");
  assert.ok(similar("OpenAI launches GPT-6 with longer memory", "OpenAI launches GPT-6"));
  assert.ok(!similar("OpenAI launches GPT-6", "Google launches Gemini 4"));
});

const SRC = {
  tc: { id: "techcrunch", name: "TechCrunch", kind: "rss", aiOnly: true, weight: 1.1 },
  oa: { id: "openai", name: "OpenAI", kind: "rss", aiOnly: true, weight: 1.4, official: true },
  sw: { id: "simonw", name: "Simon Willison", kind: "rss", aiOnly: false, weight: 0.9 },
  hn: { id: "hn", name: "Hacker News", kind: "hn", aiOnly: false, weight: 1 },
  hf: { id: "hfpapers", name: "Hugging Face Papers", kind: "hfpapers", aiOnly: true, weight: 0.8 },
  gh: { id: "github", name: "GitHub", kind: "github", aiOnly: true, weight: 0.8 },
};

function allItems() {
  return [
    ...parseFeed(RSS).map((i) => ({ ...i, source: SRC.tc })),
    ...parseFeed(OFFICIAL).map((i) => ({ ...i, source: SRC.oa })),
    ...parseFeed(ATOM).map((i) => ({ ...i, source: SRC.sw })),
    ...parseHn(HN).map((i) => ({ ...i, source: SRC.hn })),
    ...parseHfPapers(PAPERS).map((i) => ({ ...i, source: SRC.hf })),
    ...parseGithub(GITHUB).map((i) => ({ ...i, source: SRC.gh })),
  ];
}

test("clustering merges one story across outlets and picks the official source", () => {
  // Simon's hands-on post is a different piece, so it stays its own story.
  const items = allItems().filter((i) => /gpt-6/i.test(i.title) && i.source.id !== "simonw");
  const stories = cluster(items);
  assert.equal(stories.length, 1, stories.map((s) => s.members.map((m) => m.title)).join(" | "));
  assert.equal(stories[0].primary.source.id, "openai");
  assert.ok(heat(stories[0], NOW) > 60);
});

const fakeArticleFetch = async (url) => {
  if (url.includes("openai.com")) return new Response(ARTICLE, { status: 200 });
  return new Response("blocked", { status: 403 });
};

function fakeModel(calls) {
  return async (system, user) => {
    calls.push(user);
    const head = user.match(/^Headline: (.*)$/m)[1];
    if (/sourdough|SQLite/i.test(head)) return JSON.stringify({ keep: false });
    return "```json\n" + JSON.stringify({
      keep: true,
      headline: `Card: ${head}`.slice(0, 90),
      summary: "This is the first sentence of a summary written from the text. This is the second sentence of it.",
      why_it_matters: "It changes what everyday AI users can do.",
      key_points: ["First fact from the text", "Second fact from the text", "Third fact from the text"],
      topic: "Models",
      context: "Some context.",
      what_next: "",
    }) + "\n```";
  };
}

test("buildFeed: filters, merges, writes, and is grounded on article text", async () => {
  const calls = [];
  const { feed, stats } = await buildFeed({ items: allItems(), previous: null, model: fakeModel(calls), fetchImpl: fakeArticleFetch, now: NOW });
  const heads = feed.cards.map((c) => c.originalTitle);
  assert.deepEqual(new Set(heads), new Set(["Introducing GPT-6", "Trying out the new GPT-6 model", "Scaling Laws for Reasoning Agents", "acme/tiny-llm: Run a small LLM on your laptop"]), heads.join(" | "));
  const gpt = feed.cards.find((c) => c.originalTitle === "Introducing GPT-6");
  assert.equal(gpt.type, "release");
  assert.equal(gpt.source.url, "https://openai.com/index/introducing-gpt-6/");
  assert.deepEqual(gpt.alsoCoveredBy.map((o) => o.name), ["TechCrunch"]);
  assert.equal(gpt.discussionUrl, "https://news.ycombinator.com/item?id=111");
  assert.equal(gpt.id, cardId(gpt.source.url));
  assert.equal(feed.cards.find((c) => c.type === "research").originalTitle, "Scaling Laws for Reasoning Agents");
  assert.equal(feed.cards.find((c) => c.type === "tool").source.name, "GitHub");
  // The writer saw the real article, not the menu or footer.
  const gptCall = calls.find((c) => c.includes("Headline: Introducing GPT-6"));
  assert.match(gptCall, /paying ChatGPT users/);
  assert.match(gptCall, /Also covered by: .*TechCrunch/);
  // The old story and non-AI posts never reach the writer (no AI cost spent on them).
  assert.ok(!calls.some((c) => /Old story|sourdough|SQLite/.test(c)));
  assert.equal(stats.declined, 0);
  // Newest first.
  const times = feed.cards.map((c) => Date.parse(c.publishedAt));
  assert.deepEqual(times, [...times].sort((a, b) => b - a));
});

test("next run: existing stories aren't rewritten, new outlets are added, old cards expire", async () => {
  const calls = [];
  const first = await buildFeed({ items: allItems(), previous: null, model: fakeModel(calls), fetchImpl: fakeArticleFetch, now: NOW });
  const ancient = { ...first.feed.cards[0], id: "old1", source: { name: "X", url: "https://x.com/a" }, originalTitle: "Ancient", publishedAt: iso(24 * 30) };
  const previous = { ...first.feed, cards: [...first.feed.cards, ancient] };
  const wired = { title: "OpenAI's GPT-6 launches with longer memory and tools", url: "https://wired.com/gpt-6", publishedAt: iso(1), snippet: "", source: { id: "wired", name: "Wired", kind: "rss", aiOnly: true, weight: 1 } };
  const calls2 = [];
  const second = await buildFeed({ items: [...allItems(), wired], previous, model: fakeModel(calls2), fetchImpl: fakeArticleFetch, now: NOW + 3_600_000 });
  assert.equal(calls2.filter((c) => /GPT-6|Scaling|tiny-llm/.test(c)).length, 0, "nothing rewritten");
  const gpt = second.feed.cards.find((c) => c.originalTitle === "Introducing GPT-6");
  assert.ok(gpt.alsoCoveredBy.some((o) => o.name === "Wired"));
  assert.ok(!second.feed.cards.some((c) => c.id === "old1"), "expired after 10 days");
  assert.equal(second.feed.cards.length, first.feed.cards.length);
});

test("budget: only the hottest new stories are written", async () => {
  const many = Array.from({ length: 60 }, (_, i) => ({
    title: `AI story ${i}: alpha${i} beta${i} gamma${i} delta${i}`,
    url: `https://news.example.com/ai-${i}`,
    publishedAt: iso(i % 48),
    snippet: "An AI company raised money for its agent product.",
    source: SRC.tc,
  }));
  const calls = [];
  const { stats } = await buildFeed({ items: many, previous: null, model: fakeModel(calls), fetchImpl: fakeArticleFetch, now: NOW, maxNew: 10 });
  assert.equal(calls.length, 10);
  assert.equal(stats.written, 10);
});

test("draft validation rejects junk and hype", () => {
  const ok = { keep: true, headline: "Google releases Gemini 4", summary: "Google released Gemini 4 today for developers and consumers. It is faster than the last version.", why_it_matters: "It competes directly with ChatGPT.", key_points: ["Released today", "Faster than before"], topic: "Nonsense", context: "", what_next: "" };
  assert.equal(validateDraft(ok).topic, "Business", "unknown topic falls back");
  assert.equal(validateDraft({ ...ok, keep: false }), null);
  assert.equal(validateDraft({ ...ok, summary: "Too short." }), null);
  assert.equal(validateDraft({ ...ok, headline: "A game-changing Gemini 4" }), null);
  assert.equal(validateDraft({ ...ok, key_points: ["one"] }), null);
});

test("collect: a failing source doesn't stop the others", async () => {
  const sources = [
    { id: "good", name: "Good", kind: "rss", url: "https://good/feed", aiOnly: true },
    { id: "bad", name: "Bad", kind: "rss", url: "https://bad/feed", aiOnly: true },
    { id: "gh", name: "GitHub", kind: "github", url: "https://api.github.com/search?q=created:>{SINCE}", aiOnly: true },
  ];
  const seen = [];
  const f = async (url) => {
    seen.push(url);
    if (url.includes("bad")) throw new Error("timeout");
    if (url.includes("github")) return new Response(JSON.stringify(GITHUB));
    return new Response(RSS);
  };
  const { items, health } = await collect(f, sources, NOW);
  assert.equal(health.bad.ok, false);
  assert.equal(health.good.items, 2);
  assert.ok(seen.some((u) => u.includes("created:>2026-11-05")), "GitHub search covers the last week");
  assert.equal(items.length, 3);
});

test("Claude adapter sends the right request", async () => {
  const reqs = [];
  const f = async (url, init) => {
    reqs.push({ url, init });
    return new Response(JSON.stringify({ content: [{ type: "text", text: "hi" }] }));
  };
  assert.equal(await makeModel({ ANTHROPIC_API_KEY: "k" }, f)("s", "u"), "hi");
  assert.equal(reqs[0].url, "https://api.anthropic.com/v1/messages");
  assert.equal(JSON.parse(reqs[0].init.body).model, "claude-haiku-5-5");
  assert.equal(reqs[0].init.headers["x-api-key"], "k");
  assert.throws(() => makeModel({}, f));
  // Empty settings from GitHub Actions don't override the default model.
  await makeModel({ ANTHROPIC_API_KEY: "k", CLAUDE_MODEL: "" }, f)("s", "u");
  assert.equal(JSON.parse(reqs[1].init.body).model, "claude-haiku-5-5");
});

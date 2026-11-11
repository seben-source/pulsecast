// Turns raw feed responses into plain items:
// { title, url, publishedAt (ISO), snippet, points?, comments? }

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", hellip: "…", mdash: "—", ndash: "–", rsquo: "’", lsquo: "‘", rdquo: "”", ldquo: "“" };

export function decodeEntities(s) {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, code) => {
    if (code[0] === "#") {
      const n = code[1].toLowerCase() === "x" ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
      return Number.isFinite(n) && n > 0 && n < 0x110000 ? String.fromCodePoint(n) : m;
    }
    return ENTITIES[code.toLowerCase()] ?? m;
  });
}

function unCdata(s) {
  return s.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1");
}

/** HTML or XML fragment to readable plain text. */
export function htmlToText(html) {
  const once = stripTags(html);
  // Some feeds escape their HTML (&lt;p&gt;); strip again after decoding.
  return /<\/?[a-z][^>]*>/i.test(once) ? stripTags(once) : once;
}

function stripTags(html) {
  return decodeEntities(
    unCdata(html)
      .replace(/<(script|style|noscript|svg|figure|figcaption)[\s\S]*?<\/\1>/gi, " ")
      .replace(/<br\s*\/?>/gi, "\n")
      .replace(/<\/(p|div|li|h[1-6])>/gi, "\n")
      .replace(/<[^>]+>/g, " "),
  )
    .replace(/[ \t\f\v ]+/g, " ")
    .replace(/\s*\n\s*/g, "\n")
    .trim();
}

function tag(block, name) {
  const re = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`, "i");
  const m = block.match(re);
  return m ? m[1] : null;
}

function atomLink(block) {
  // Prefer rel="alternate" (or no rel); fall back to the first href.
  const links = [...block.matchAll(/<link\b([^>]*)\/?>/gi)].map((m) => m[1]);
  const attr = (a, n) => (a.match(new RegExp(`${n}\\s*=\\s*["']([^"']+)["']`, "i")) || [])[1];
  const alt = links.find((a) => !attr(a, "rel") || attr(a, "rel") === "alternate");
  return attr(alt ?? links[0] ?? "", "href") ?? null;
}

function toIso(s) {
  if (!s) return null;
  const d = new Date(s.trim());
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

function clip(text, max) {
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  const end = cut.lastIndexOf(" ");
  return (end > max * 0.6 ? cut.slice(0, end) : cut) + "…";
}

/** RSS 2.0 or Atom. */
export function parseFeed(xml) {
  const items = [];
  const isAtom = /<feed[\s>]/i.test(xml) && !/<rss[\s>]/i.test(xml);
  const blocks = isAtom
    ? [...xml.matchAll(/<entry\b[\s\S]*?<\/entry>/gi)].map((m) => m[0])
    : [...xml.matchAll(/<item\b[\s\S]*?<\/item>/gi)].map((m) => m[0]);
  for (const b of blocks) {
    const title = htmlToText(tag(b, "title") ?? "");
    let url;
    if (isAtom) url = atomLink(b);
    else {
      const link = tag(b, "link");
      url = link ? htmlToText(link) : null;
      if (!url) {
        const guid = tag(b, "guid");
        if (guid && /^https?:\/\//.test(htmlToText(guid))) url = htmlToText(guid);
      }
    }
    const date = toIso(
      tag(b, "pubDate") ?? tag(b, "published") ?? tag(b, "updated") ?? tag(b, "dc:date") ?? "",
    );
    const body = tag(b, "description") ?? tag(b, "summary") ?? tag(b, "content:encoded") ?? tag(b, "content") ?? "";
    if (!title || !url || !/^https?:\/\//.test(url)) continue;
    items.push({ title, url: url.trim(), publishedAt: date, snippet: clip(htmlToText(body), 700) });
  }
  return items;
}

/** Hacker News via Algolia. Uses the linked article, not the HN thread. */
export function parseHn(json) {
  const hits = Array.isArray(json?.hits) ? json.hits : [];
  return hits
    .filter((h) => h && typeof h.title === "string" && typeof h.url === "string" && /^https?:\/\//.test(h.url))
    .map((h) => ({
      title: h.title.trim(),
      url: h.url,
      publishedAt: h.created_at_i ? new Date(h.created_at_i * 1000).toISOString() : toIso(h.created_at),
      snippet: "",
      points: Number(h.points) || 0,
      comments: Number(h.num_comments) || 0,
      discussionUrl: h.objectID ? `https://news.ycombinator.com/item?id=${h.objectID}` : undefined,
    }));
}

/** Hugging Face daily papers. The field layout is read defensively. */
export function parseHfPapers(json) {
  const list = Array.isArray(json) ? json : Array.isArray(json?.papers) ? json.papers : [];
  return list
    .map((e) => {
      const p = e?.paper ?? e ?? {};
      const id = p.id ?? e?.id;
      const title = (p.title ?? e?.title ?? "").replace(/\s+/g, " ").trim();
      if (!id || !title) return null;
      return {
        title,
        url: `https://huggingface.co/papers/${id}`,
        publishedAt: toIso(e?.publishedAt ?? p.publishedAt ?? ""),
        snippet: clip(String(p.summary ?? e?.summary ?? "").replace(/\s+/g, " ").trim(), 1500),
        points: Number(p.upvotes ?? e?.upvotes) || 0,
      };
    })
    .filter(Boolean);
}

/** GitHub repository search. */
export function parseGithub(json) {
  const items = Array.isArray(json?.items) ? json.items : [];
  return items
    .filter((r) => r?.html_url && r?.full_name)
    .map((r) => ({
      title: `${r.full_name}${r.description ? `: ${r.description}` : ""}`.slice(0, 200),
      url: r.html_url,
      publishedAt: toIso(r.created_at ?? ""),
      snippet: clip(`${r.description ?? ""} Language: ${r.language ?? "n/a"}. Stars: ${r.stargazers_count ?? 0}. Topics: ${(r.topics ?? []).join(", ")}.`, 600),
      points: Number(r.stargazers_count) || 0,
    }));
}

/**
 * Pulls the main text out of an article page, for the writer to summarise.
 * Prefers <article>/<main>, then falls back to all paragraphs.
 */
export function extractArticle(html) {
  const cleaned = html.replace(/<(script|style|noscript|svg|nav|header|footer|aside|form)[\s\S]*?<\/\1>/gi, " ");
  const scope = cleaned.match(/<article\b[\s\S]*?<\/article>/i)?.[0] ?? cleaned.match(/<main\b[\s\S]*?<\/main>/i)?.[0] ?? cleaned;
  const paras = [...scope.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)]
    .map((m) => htmlToText(m[1]).replace(/\n/g, " ").trim())
    .filter((t) => t.length > 40);
  return clip(paras.join("\n"), 7000);
}

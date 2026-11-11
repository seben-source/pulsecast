// Where Prompt's news comes from. Every card links back to one of these.
//
// kind:
//   rss      RSS or Atom feed
//   hn       Hacker News stories via the free Algolia API
//   hfpapers Hugging Face "Daily Papers" (trending AI research)
//   github   New GitHub repositories gaining stars fast
//
// aiOnly: true means everything in the feed is about AI. false means the feed
// covers more than AI, so items are kept only if they look AI-related.
// weight: how much a story from this source counts towards "heat".

export const SOURCES = [
  // AI companies and labs
  { id: "openai", name: "OpenAI", kind: "rss", url: "https://openai.com/news/rss.xml", aiOnly: true, weight: 1.4, official: true },
  { id: "deepmind", name: "Google DeepMind", kind: "rss", url: "https://deepmind.google/blog/rss.xml", aiOnly: true, weight: 1.3, official: true },
  { id: "google-ai", name: "Google", kind: "rss", url: "https://blog.google/innovation-and-ai/technology/ai/rss/", aiOnly: true, weight: 1.2, official: true },
  { id: "google-research", name: "Google Research", kind: "rss", url: "https://research.google/blog/rss/", aiOnly: false, weight: 1.0, official: true },
  { id: "mistral", name: "Mistral AI", kind: "rss", url: "https://mistral.ai/news/rss", aiOnly: true, weight: 1.1, official: true },
  { id: "huggingface", name: "Hugging Face", kind: "rss", url: "https://huggingface.co/blog/feed.xml", aiOnly: true, weight: 1.0, official: true },
  { id: "nvidia", name: "NVIDIA", kind: "rss", url: "https://blogs.nvidia.com/feed/", aiOnly: false, weight: 1.0, official: true },
  { id: "bair", name: "Berkeley AI Research", kind: "rss", url: "https://bair.berkeley.edu/blog/feed.xml", aiOnly: true, weight: 0.9, official: true },

  // Journalism
  { id: "techcrunch", name: "TechCrunch", kind: "rss", url: "https://techcrunch.com/category/artificial-intelligence/feed/", aiOnly: true, weight: 1.1 },
  { id: "verge", name: "The Verge", kind: "rss", url: "https://www.theverge.com/rss/ai-artificial-intelligence/index.xml", aiOnly: true, weight: 1.1 },
  { id: "ars", name: "Ars Technica", kind: "rss", url: "https://arstechnica.com/ai/feed/", aiOnly: true, weight: 1.1 },
  { id: "wired", name: "Wired", kind: "rss", url: "https://www.wired.com/feed/tag/ai/latest/rss", aiOnly: true, weight: 1.0 },
  { id: "mit-tr", name: "MIT Technology Review", kind: "rss", url: "https://www.technologyreview.com/topic/artificial-intelligence/feed", aiOnly: true, weight: 1.1 },
  { id: "decoder", name: "The Decoder", kind: "rss", url: "https://the-decoder.com/feed/", aiOnly: true, weight: 0.9 },
  { id: "simonw", name: "Simon Willison", kind: "rss", url: "https://simonwillison.net/atom/everything/", aiOnly: false, weight: 0.9 },

  // Community, research and tools
  { id: "hn", name: "Hacker News", kind: "hn", url: "https://hn.algolia.com/api/v1/search_by_date?tags=story&numericFilters=points%3E60&hitsPerPage=100", aiOnly: false, weight: 1.0 },
  { id: "hfpapers", name: "Hugging Face Papers", kind: "hfpapers", url: "https://huggingface.co/api/daily_papers?limit=30&sort=trending", aiOnly: true, weight: 0.8 },
  { id: "github", name: "GitHub", kind: "github", url: "https://api.github.com/search/repositories?q=topic:llm+created:>{SINCE}&sort=stars&order=desc&per_page=20", aiOnly: true, weight: 0.8 },
];

// Words that mark a story from a general feed as being about AI.
export const AI_TERMS = [
  "ai", "a.i.", "artificial intelligence", "machine learning", "deep learning", "neural",
  "llm", "llms", "language model", "foundation model", "frontier model", "transformer",
  "gpt", "chatgpt", "openai", "anthropic", "claude", "gemini", "deepmind", "llama", "mistral",
  "grok", "xai", "copilot", "hugging face", "huggingface", "stable diffusion", "midjourney",
  "sora", "veo", "diffusion model", "generative", "genai", "chatbot", "agent", "agents", "agentic",
  "inference", "fine-tune", "fine-tuning", "rag", "embedding", "embeddings", "multimodal",
  "gpu", "gpus", "nvidia", "tpu", "reasoning model", "deepseek", "qwen", "perplexity", "cursor",
  "robotics", "humanoid", "self-driving", "autonomous", "superintelligence", "agi", "alignment",
];

# Ask server (Prompt Pro)

`ask.js` is a Cloudflare Worker that answers "Ask about this story" questions.
It keeps your Claude API key off the phone, only answers about stories that are
in Prompt, and answers only from the article's text.

## Set it up (about 10 minutes, free plan is enough)

1. Sign in at https://dash.cloudflare.com (create a free account if needed).
2. **Workers & Pages → Create → Create Worker.** Name it `prompt-ask`, then **Deploy**.
3. **Edit code**: replace everything with the contents of `ask.js`, then **Deploy**.
4. **Settings → Variables and Secrets → Add**, type *Secret*:
   - `ANTHROPIC_API_KEY`: your Claude API key (a separate key from the engine's
     makes spending easy to tell apart).
   - `APP_KEY`: any long random string, e.g. 40 random letters and numbers.
5. Optional, recommended: a per-phone daily limit.
   **Storage & Databases → KV → Create** a namespace called `prompt-ask-limits`.
   Then on the Worker: **Settings → Bindings → Add → KV namespace**, variable
   name `LIMITS`, pick that namespace. Default is 40 questions per phone per day
   (change with a `DAILY_LIMIT` variable).
6. In the Claude Console, set a monthly spend limit on the key as a safety net.
7. Check it works: open `https://prompt-ask.<your-subdomain>.workers.dev/health`;
   it should say `{"ok":true}`.
8. In `android/gradle.properties` set:
   ```
   pulsecast.askUrl=https://prompt-ask.<your-subdomain>.workers.dev
   pulsecast.askKey=<the APP_KEY from step 4>
   ```
   then Sync Project with Gradle Files and Run.

## Notes

- Cost: each question is one Claude Haiku call with the article (a few thousand
  tokens in, up to 700 out).
- `APP_KEY` ships inside the app, so it only keeps out casual misuse. The real
  protections are that it only answers about Prompt's own stories, the daily
  limit, and your spend limit. Once Play billing is live, a next step is to
  have the app send its Play purchase token and verify it here.
- Optional variables: `CLAUDE_MODEL` (default `claude-haiku-5-5`), `FEED_URL`.

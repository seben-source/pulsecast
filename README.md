# PulseCast: AI news, in 30 seconds a story

PulseCast has two parts:

- **`engine/`** is the news engine. Every 30 minutes it collects AI news from 19 trusted sources. An AI model writes a short card from each real article, and the engine publishes the cards as `feed.json`.
- **`android/`** is the Android app. It's an Instagram-style feed of those cards, with sponsored posts (AdMob native ads) shown in the feed as regular-looking posts.

## How the news engine works

1. **It collects** from:
   - AI company blogs: OpenAI, Google DeepMind, Google, Google Research, Mistral, Hugging Face, NVIDIA, AWS, Berkeley AI Research
   - AI journalism: TechCrunch, The Verge, Ars Technica, Wired, MIT Technology Review, The Decoder, Simon Willison
   - Hacker News, Hugging Face trending papers, and fast-rising new GitHub AI projects
2. **It filters** out anything that isn't about AI or is more than 3 days old.
3. **It merges** reports of the same story from several outlets into one card. The company's own announcement is preferred as the main source, and the other outlets are listed under "also covered by".
4. **It ranks** each story by "heat": how many outlets cover it, Hacker News points, paper upvotes and GitHub stars. Heat fades as the story gets older.
5. **It writes** cards for the 40 hottest new stories per run, using Claude. Claude is given the real article text and told to use only that. Replies that are thin, off-topic or hype-filled are rejected.
6. **It keeps** existing stories rather than rewriting them, adding new outlets to them as they cover the story. Cards older than 10 days are dropped.

## One-time setup (free)

1. **Create a public GitHub repository**, for example `pulsecast`, and upload this whole folder to it.
   - A public repository gets unlimited free GitHub Actions minutes. Your AI key stays secret either way.
2. **Turn on GitHub Pages:** Settings → Pages → Source: **GitHub Actions**.
3. **Add your Claude API key:** Settings → Secrets and variables → Actions → New repository secret, named `ANTHROPIC_API_KEY`. The engine uses Claude Haiku, which is fast and cheap. To use a different Claude model, add a variable named `CLAUDE_MODEL`.
4. **Run it once:** Actions → **PulseCast feed** → Run workflow.
   - After that it runs every 30 minutes on its own.
   - The feed appears at `https://<your-github-name>.github.io/pulsecast/feed.json`.
   - `status.json` at the same address shows which sources worked on the last run.
5. **Point the app at your feed:** in `android/gradle.properties`, set `pulsecast.feedUrl` to that address.

**Cost control:** the `MAX_NEW` variable sets how many new cards each run writes. It defaults to 40, which keeps AI costs small.

**Keeping the schedule alive:** GitHub pauses scheduled jobs in a repository that has had no activity for 60 days. Making any small commit every month or so keeps it running.

## The app

- **Feed tabs:**
  - **For you:** ranked by heat and freshness, nudged toward the topics you read most.
  - **Latest:** newest first, with a "You're caught up" line where your last visit ended.
  - **Saved:** stories you starred.
- **Each post shows:** topic, source and age, a headline, a 2-sentence summary, and "Why it matters". Tap a post for key points, context, what's next and the other outlets. **Read at …** opens the original article.
- **Share** turns the post into a portrait image with the PulseCast name on it, plus a link.
- **Notifications:**
  - A morning briefing with the day's top stories.
  - Breaking alerts for the very biggest stories only, never at night.
  - Both can be turned off in Settings.
- **Ads:** a sponsored post after every 6 stories, styled like the other posts and clearly labelled "Sponsored". There are no full-screen or pop-up ads. EU and UK users see Google's consent form first, as the law requires.

### Crash reports and usage stats (Firebase)

These turn on once you add Firebase's config file:

1. Go to console.firebase.google.com and create a project. Google Analytics can be on.
2. Add an **Android app** with the package name `app.pulsecast.ai`, then download **google-services.json**.
3. Upload that file to `android/app/` in this repository (on GitHub: open the folder → Add file → Upload files).
4. Rebuild the app. Crashes then appear under **Crashlytics**, and usage under **Analytics → Events**:
   - `card_open`, `card_save`, `card_share`, `read_source`
   - `tab_view`, `search`
   - `session_depth` (how far people scroll)

Without the file the app still builds and runs, and stats are simply off.

### Before publishing

1. Create an AdMob account and add the app. Then replace both test IDs in `android/gradle.properties`:
   - `pulsecast.admobAppId` with your app ID
   - `pulsecast.nativeAdUnitId` with a **Native advanced** ad unit ID

   Until you do, the app shows Google's test ads, which are safe to tap.
2. In AdMob, set up the EU consent message (Privacy & messaging → GDPR).
3. Create a Google Play developer account (one-off US$25 fee). New personal accounts need a closed test with at least 12 testers who stay opted in for 14 days before the app can go public.
4. Use the privacy policy at `https://<your-github-name>.github.io/pulsecast/privacy.html` for the Play listing.

## Development

- Engine tests: `cd engine && npm test`. They need Node 20 or newer and no installs.
- App: open `android/` in Android Studio and press **Run**.

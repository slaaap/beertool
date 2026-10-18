# beertool 🍺

A web app for writing beer recipes, watching the numbers as you go, and keeping a log of every brew,
including a phone-first **Brew Day** mode for using it at the kettle.

> Built entirely with [Claude Code](https://claude.com/claude-code): fully vibe-coded, no hand-written lines.

## Features

- **Recipes** - fermentables, hops, yeasts, extras and a step mash, with live estimates as you type:
  OG, FG, ABV, IBU (Tinseth), colour (Morey, in EBC), and a mash-temperature-driven attenuation shift.
- **Water chemistry** (optional per recipe) - pick one of your saved waters or mix two, add brewing salts
  and an acid (lactic, phosphoric, or AMS/CRS for the whole brewing water), and see the resulting minerals, the chloride-to-sulphate balance and
  an estimated mash pH, with a one-tap acid amount to reach pH 5.4. Campden tablets for chlorinated tap
  water are part of the plan too.
- **Full-text search** across recipe and ingredient names ("citra" finds the beer it's dry-hopped with).
- **Brew Day mode** (phone-first) - a mash-water guide (strike volume + temperature, sparge volume), the
  salts and acid split between strike and sparge water, a wall-clock boil timer that survives the phone
  locking and beeps/vibrates when each hop addition is due, and a live readings form.
- **Brew log** - every batch numbered per recipe, with measured OG/FG/volumes, a stamped ABV and mash
  efficiency, and the water it was actually made with (all recorded at the time, so later edits to the
  recipe or your settings never rewrite a brew's history). The water can be changed on brew day until the
  OG is in.
- **Per-user settings** - your kit volumes, efficiency, default mash, mash-water ratios and water sources
  (mineral figures from your water report or bottle label); new recipes open pre-filled from them.
- **Telegram bot** (optional) - browse and search recipes, share recipe cards inline in any chat, get the
  brew day plan, a boil timer that messages you at each addition, and log readings by just typing `og 1.052`.
- **AI assistant access** (optional) - an MCP endpoint, so an assistant such as Claude or ChatGPT can read your
  recipes and brew log, estimate drafts with beertool's own maths, and save recipes you agree on.
- **Two modes** - multi-user with accounts, or a **single-user public-showcase mode** where visitors browse
  one brewer's recipes read-only.

## Requirements

- **JDK 21**
- **Docker** - for a local Postgres, and for the tests (which spin up a throwaway Postgres via Testcontainers)

## Quick start

```bash
docker compose up -d     # start a local Postgres (creds match docker-compose.yml)
./gradlew run            # migrations run on boot; app at http://localhost:8080
```

Open <http://localhost:8080>, register an account, and start writing recipes. The app connects to the
database given by the env vars below and **never provisions its own**: bring your own Postgres (the
`docker compose` one, or any other).

## Configuration

All configuration is via environment variables:

| Variable            | Default                                          | Purpose                                                        |
|---------------------|--------------------------------------------------|---------------------------------------------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/beertool`      | JDBC URL of the Postgres to use                               |
| `DATABASE_USER`     | `beertool`                                        | Database user                                                 |
| `DATABASE_PASSWORD` | `beertool`                                        | Database password                                             |
| `SESSION_SECRET`    | *(random per boot)*                               | Signs the session cookie; **set a long random value in production** (otherwise sessions don't survive a restart) |
| `SINGLE_USER_EMAIL` | *(unset → normal multi-user mode)*                | Enables single-user mode for this account (see below)         |
| `PORT`              | `8080`                                            | HTTP port to listen on                                        |
| `HOST`              | `127.0.0.1`                                       | Address to listen on; `0.0.0.0` exposes it beyond this machine |
| `TELEGRAM_BOT_TOKEN`| *(unset → bot off)*                               | Turns on the Telegram bot (see below)                         |
| `MCP_ENABLED`       | *(unset → off)*                                   | `true` turns on the MCP endpoint at `/mcp` (see below)        |
| `MCP_OAUTH_ENABLED` | `true`                                            | `false` turns off connecting by OAuth (Claude, ChatGPT)       |
| `MCP_TOKENS_ENABLED`| `true`                                            | `false` turns off personal access tokens                      |
| `MCP_OAUTH_CLIENT_HOSTS` | `claude.ai,chatgpt.com`                      | Comma-separated hosts whose apps may connect through OAuth (see below) |
| `PUBLIC_URL`        | *(unset → no links)*                              | The app's public base URL, e.g. `https://beer.example.com`; the bot links its recipe and batch titles to the site, and the MCP endpoint accepts requests for this host |

Example, pointing at a real database:

```bash
export DATABASE_URL="jdbc:postgresql://db.example.com:5432/beertool"
export DATABASE_USER=beertool
export DATABASE_PASSWORD=secret
export SESSION_SECRET="$(openssl rand -hex 32)"
./gradlew run
```

## Single-user (public showcase) mode

Set `SINGLE_USER_EMAIL` to an existing account's email to run a public, read-only showcase of that
brewer's data:

```bash
export SINGLE_USER_EMAIL=you@example.com
./gradlew run
```

Anonymous visitors can then browse that user's recipes and brew log, but every write action is hidden and
blocked (new/edit/settings and all form submissions redirect to login), and registration is disabled. Log
in as that account to edit.

## Telegram bot (optional)

The bot is off unless `TELEGRAM_BOT_TOKEN` is set. It long-polls Telegram, so it needs outbound HTTPS only:
no webhook, no open port, and it works the same on a laptop as on a server.

1. In Telegram, talk to [@BotFather](https://t.me/BotFather): `/newbot`, pick a name and a username, and
   copy the token it gives you.
2. Optionally, send BotFather `/setinline` for your bot, so you can type `@yourbot citra` in any chat to
   share a recipe card.
3. Start beertool with the token (and `PUBLIC_URL`, if the app is reachable on the internet):

   ```bash
   export TELEGRAM_BOT_TOKEN=123456789:AA...
   export PUBLIC_URL=https://beer.example.com
   ./gradlew run
   ```

4. In beertool, open **Settings → Connections → Connect Telegram**. That opens the bot with a one-time link
   (valid for 15 minutes); tap **Start** and your Telegram account is linked.

What it does:

| Command / action              | What happens                                                                    |
|-------------------------------|---------------------------------------------------------------------------------|
| `/recipes`, `/find <text>`    | Browse or search recipes; tap one for the full recipe card                      |
| `/log`                        | The brew log; tap a batch for its readings against the plan                     |
| **Start brew day** (button)   | Starts today's batch (or resumes it) and sends water, mash and boil plan        |
| `/brew`                       | Shows the brew in progress, or lets you pick one when several are going         |
| **Start boil timer** (button) | Messages you as each addition falls due and at flame-out; survives restarts     |
| Plain text, e.g. `og 1.052 post 23` | Logs readings to the current brew (`og`, `fg`, `pre`, `post`, `fermenter`, `note …`); gravity as `1.052` or `1052` |
| **Mark packaged** (button)    | Once the FG is in                                                               |
| `@yourbot <text>` in any chat | Shares a recipe card (needs inline mode, step 2)                                |
| `/unlink`                     | Disconnects the Telegram account (also possible from Settings)                  |

Notes:

- The bot only answers in private chats, so a linked account can't be used by a whole group.
- In single-user mode, anyone can browse the showcase recipes through the bot read-only, just like the
  website; only the linked owner can log brews.
- A bot token can be polled by **one** running app at a time. Use a separate bot for development, or
  Telegram will answer the second instance with "Conflict" errors.

## AI assistant (MCP, optional)

With `MCP_ENABLED=true`, beertool serves the [Model Context Protocol](https://modelcontextprotocol.io) at
`/mcp`, so an AI assistant such as Claude or ChatGPT can work with your recipes: ask it to *"design a 5%
citra pale ale for my kit"* or *"why did my last stout finish high?"*, and save the recipe you settle on.
Every connection is tied to one brewer and only ever reaches that brewer's data.

### Connect Claude or ChatGPT

With `PUBLIC_URL` set to the app's HTTPS address, beertool is also an OAuth authorization server, so these
connect with just the URL `https://beer.example.com/mcp` and no token:

- **Claude** (claude.ai on the web, desktop and mobile): **Settings → Connectors → Add custom connector**.
- **ChatGPT**: add a custom connector (MCP app) with the URL and choose OAuth. If asked how ChatGPT should
  identify itself, pick the client metadata document. Depending on your ChatGPT plan, custom connectors may
  need developer mode switched on.
- **Claude Code**: `claude mcp add --transport http beertool https://beer.example.com/mcp`

Each sends you to beertool to log in and approve. Connected apps are listed under **Settings → Connections**,
where you can disconnect them.

Apps identify themselves with an OAuth Client ID Metadata Document, which beertool fetches to check the app's
name and where it may send you back. Only apps on the hosts in `MCP_OAUTH_CLIENT_HOSTS` (by default
`claude.ai` and `chatgpt.com`) are accepted, so the server never fetches addresses a stranger supplies.

### Connect with an access token

For other MCP clients, or without a `PUBLIC_URL`, create a personal token under **Settings → Connections →
AI assistant**. Settings shows it once, together with the command to connect Claude Code:

```bash
claude mcp add --transport http beertool https://beer.example.com/mcp --header "Authorization: Bearer bt_…"
```

### Tools

| Tool                 | What it does                                                                 |
|----------------------|------------------------------------------------------------------------------|
| `get_brewer_profile` | Your kit volumes, efficiency, boil, default mash and water sources, so drafts fit your setup |
| `search_recipes`     | Find recipes by name or ingredient                                           |
| `get_recipe`         | A whole recipe with its estimate, water and every brew of it                 |
| `list_brews`         | The brew log with measured figures, notes and the water each brew used       |
| `find_styles`        | BJCP 2021 style names                                                        |
| `estimate_recipe`    | OG, FG, ABV, IBU, colour, minerals and mash pH for a draft, without saving it |
| `create_recipe`      | Save a new recipe                                                            |
| `update_recipe`      | Replace an existing recipe                                                   |

Both ways of connecting are on by default. On a public server where everyone uses Claude or ChatGPT, consider
`MCP_TOKENS_ENABLED=false`: OAuth access expires within the hour and renews itself, while a personal token
works until it is revoked. Tokens created earlier then stop working but stay listed in Settings, so they can
still be revoked.

Disconnect an app or revoke a token in Settings and it loses access at once. Set `PUBLIC_URL` in production:
requests addressed to any other host are refused, which protects the endpoint against DNS rebinding.

## Build a standalone jar

```bash
./gradlew buildFatJar          # → build/libs/beertool-all.jar
java -jar build/libs/beertool-all.jar
```

The jar is self-contained and reads the same environment variables as `./gradlew run`. Deploy it behind
an HTTPS-terminating reverse proxy in production, and set `SESSION_SECRET`.

## Tests

```bash
./gradlew test
```

Tests start their own throwaway Postgres via Testcontainers, so no `docker compose` is needed.

## Tech

Kotlin · [Ktor](https://ktor.io) · server-rendered HTML ([kotlinx.html](https://github.com/Kotlin/kotlinx.html))
with small vanilla-JS enhancements · Postgres via [Exposed](https://github.com/JetBrains/Exposed) +
[Flyway](https://flywaydb.org) + HikariCP · BCrypt auth · JUnit 5 + [Kotest](https://kotest.io) +
Testcontainers.

## License

Released into the public domain under [The Unlicense](LICENSE): do whatever you like with it.

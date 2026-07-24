# beertool 🍺

A web app for writing beer recipes, watching the numbers as you go, and keeping a log of every brew —
including a phone-first **Brew Day** mode for using it at the kettle.

> Built entirely with [Claude Code](https://claude.com/claude-code) — fully vibe-coded, no hand-written lines.

## Features

- **Recipes** — fermentables, hops, yeasts, extras and a step mash, with live estimates as you type:
  OG, FG, ABV, IBU (Tinseth), colour (Morey, in EBC), and a mash-temperature-driven attenuation shift.
- **Full-text search** across recipe and ingredient names ("citra" finds the beer it's dry-hopped with).
- **Brew Day mode** (phone-first) — a mash-water guide (strike volume + temperature, sparge volume), a
  wall-clock boil timer that survives the phone locking and beeps/vibrates when each hop addition is due,
  and a live readings form.
- **Brew log** — every batch numbered per recipe, with measured OG/FG/volumes and a stamped ABV and mash
  efficiency (recorded at the time, so later recipe edits never rewrite a brew's history).
- **Per-user settings** — your kit volumes, efficiency, default mash, and mash-water ratios; new recipes
  open pre-filled from them.
- **Two modes** — multi-user with accounts, or a **single-user public-showcase mode** where visitors browse
  one brewer's recipes read-only.

## Requirements

- **JDK 21**
- **Docker** — for a local Postgres, and for the tests (which spin up a throwaway Postgres via Testcontainers)

## Quick start

```bash
docker compose up -d     # start a local Postgres (creds match docker-compose.yml)
./gradlew run            # migrations run on boot; app at http://localhost:8080
```

Open <http://localhost:8080>, register an account, and start writing recipes. The app connects to the
database given by the env vars below and **never provisions its own** — bring your own Postgres (the
`docker compose` one, or any other).

## Configuration

All configuration is via environment variables:

| Variable            | Default                                          | Purpose                                                        |
|---------------------|--------------------------------------------------|---------------------------------------------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/beertool`      | JDBC URL of the Postgres to use                               |
| `DATABASE_USER`     | `beertool`                                        | Database user                                                 |
| `DATABASE_PASSWORD` | `beertool`                                        | Database password                                             |
| `SESSION_SECRET`    | *(random per boot)*                               | Signs the session cookie — **set a long random value in production** (otherwise sessions don't survive a restart) |
| `SINGLE_USER_EMAIL` | *(unset → normal multi-user mode)*                | Enables single-user mode for this account (see below)         |
| `PORT`              | `8080`                                            | HTTP port to listen on                                        |

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

Tests start their own throwaway Postgres via Testcontainers — no `docker compose` needed.

## Tech

Kotlin · [Ktor](https://ktor.io) · server-rendered HTML ([kotlinx.html](https://github.com/Kotlin/kotlinx.html))
with small vanilla-JS enhancements · Postgres via [Exposed](https://github.com/JetBrains/Exposed) +
[Flyway](https://flywaydb.org) + HikariCP · BCrypt auth · JUnit 5 + [Kotest](https://kotest.io) +
Testcontainers.

## License

Released into the public domain under [The Unlicense](LICENSE) — do whatever you like with it.

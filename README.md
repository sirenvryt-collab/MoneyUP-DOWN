# Donut Balance Tracker

A client-side Fabric mod for Minecraft. Type **`/moneycheck`** in chat and it
tells you your current **DonutSMP** balance and how much it's up or down
**since midnight**, using the real
[DonutSMP public API](https://api.donutsmp.net/v1/player/index.html).

⚠️ **This project targets Minecraft 1.21.1 / Fabric.** If DonutSMP is on a
different version by the time you read this, check the versions at
https://fabricmc.net/develop and update `gradle.properties` to match — that's
the only file you should need to touch for a version bump.

## For players: install a pre-built jar

1. Go to this repo's [Releases](../../releases) page and download the latest
   `donut-balance-*.jar`.
2. Install [Fabric Loader](https://fabricmc.net/use/installer/) and
   [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) for
   the matching Minecraft version.
3. Put the jar in your `.minecraft/mods` folder.
4. Launch the game once — it creates `config/donutbalance.json`.
5. In-game on DonutSMP, run `/api` to get a personal API key.
6. Close the game, open `config/donutbalance.json`, and paste your key:
   ```json
   {
     "apiKey": "your-key-here",
     "refreshIntervalSeconds": 60
   }
   ```
7. Relaunch, join DonutSMP, and type `/moneycheck` in chat.

**Never commit `config/donutbalance.json` to git or share it — it holds a
personal API key.** It's already listed in `.gitignore`.

## For you: turning this into a jar via GitHub (no local build needed)

This repo is set up so GitHub itself compiles the jar — you don't need
Gradle, Java, or a Minecraft dev environment on your own machine.

1. Create a new empty repo on GitHub (don't add a README there).
2. Push this folder to it:
   ```bash
   cd donut-balance-mod
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/donut-balance-mod.git
   git push -u origin main
   ```
3. Go to the **Actions** tab on GitHub — a "Build" workflow runs automatically
   and produces a downloadable jar as a build artifact.
4. To get a proper **Release** with the jar attached (what the download
   button on the website points to), tag a version and push the tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   The "Release" workflow builds the jar and attaches it to a new GitHub
   Release automatically.

Before pushing, do a find-and-replace of `YOUR_USERNAME` (in `README.md`,
`docs/index.html`, `fabric.mod.json`) with your actual GitHub username.

## Turning on the website

The `docs/` folder is a ready-to-go GitHub Pages site.

1. On GitHub, go to **Settings → Pages**.
2. Under "Build and deployment", set **Source** to "Deploy from a branch".
3. Set branch to `main` and folder to `/docs`, then save.
4. Your site will be live at `https://YOUR_USERNAME.github.io/donut-balance-mod/`
   within a minute or two.

## Local development (optional)

If you do want to build locally, you need a JDK 21 and Gradle installed:

```bash
gradle build
```

The jar appears in `build/libs/`.

## How "since midnight" is calculated

DonutSMP's API only ever returns your *current* balance — there's no history
endpoint. So the mod remembers the first balance it sees each day (based on
your computer's local time) as that day's baseline, saves it to
`config/donutbalance_state.json`, and compares every later reading against
it. Restarting the game doesn't reset the baseline until the next real
midnight.

## Project layout

```
src/main/java/com/donutbalance/mod/
  DonutBalanceClient.java   entrypoint, /moneycheck command, background polling
  DonutApiClient.java       talks to api.donutsmp.net
  BalanceTracker.java       midnight baseline + delta logic
  ModConfig.java            loads/saves config/donutbalance.json
.github/workflows/
  build.yml                 builds a jar on every push
  release.yml               builds + attaches a jar when you push a vX.Y.Z tag
docs/index.html             GitHub Pages site
```

## Not affiliated with DonutSMP

This is an independent, unofficial fan tool built against DonutSMP's public
API. It is not endorsed by or affiliated with DonutSMP.

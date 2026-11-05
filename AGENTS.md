# AGENTS.md

## Project Overview

Private Android TV client（爱看电视·原生版）for ikanbot.com: a native
browsing UI plus libVLC playback.
Simplified Chinese is the working language of this repo — UI strings, docs and
commit messages are Chinese-first; code identifiers stay English.

Read `README.md` first for build and layout. This file holds the rules that
are not obvious from the code.

## Build, Test, and Development Commands

There is **no Gradle build**. The Gradle files from the old monorepo were
stale and wrong for this app (wrong applicationId/version, Media3 deps the
player does not use) and were deliberately not carried over.

- `bash build.sh` — the only build. Manual chain: aapt2 → javac → d8 →
  zipalign → apksigner. Output `build-manual/ikantv.apk`.
  Needs JDK 17 and `ANDROID_HOME` (platform 34, build-tools 34.0.0).
- `bash tools/fetch-deps.sh` — downloads libVLC-all 3.5.1 + Jsoup 1.18.3 from
  Maven Central with pinned SHA-256 into `build-deps/` (run automatically
  by `build.sh`).
- CI is the same `bash build.sh` on GitHub Actions
  (`.github/workflows/build.yml`); every push to `main` must stay green.
- No automated test suite exists. Verification is: build passes, then
  install on the real box and drive it with the remote. Claims about
  playback or focus behavior require that on-device check — do not assert
  them from code reading alone.

Version bumps: `VERSION_CODE` / `VERSION_NAME` at the top of `build.sh`
are the single source of truth. Never reuse a versionCode; installed boxes
upgrade in place only when versionCode increases and the signature matches.

## Architecture Notes

- **One name everywhere: `com.lev1s.ikantv`** is both the code package
  and the applicationId (since 2.8.2). R is generated into the manifest
  package; Manifest activities use relative names (`.ui.NativeHomeActivity`).
  Before 2.8.2 the code package was `com.lev1s.ikanbottv` and the
  applicationId `com.lev1s.ikanbottv.native` (`native` is a Java keyword) —
  that split is history, do not resurrect it. The string
  `com.lev1s.ikanbottv` must not appear in this repo's code at all.
- **Player is libVLC 3.5.1** (`PlayerActivity`), not Media3/ExoPlayer.
  All libVLC calls (stop/setMedia/play) must stay serialized on the
  player's background executor: libVLC 3.x `stop()` is synchronous and
  blocks until its input thread exits — calling it on the main thread
  caused the historical "switch source → app restarts" ANR. Every play
  carries a generation number; late callbacks from an old line are
  discarded. Manual source switching takes priority over automatic
  switching (1.5s throttle). See `docs/research/source-switch-crash-review.md`.
- **libVLC resources are load-bearing.** `thirdparty/vlc-res/` must be
  compiled in with `--extra-packages org.videolan`, or `VLCVideoLayout`
  crashes the player on open. Do not substitute the full AAR `res/` —
  the manual chain links only these files.
- **Data layer** (`data/`): plain HTTPS fetches + Jsoup parsing of the
  site's server-rendered HTML; playback lines come from a natively
  computed token (WebView harvesting is only a legacy fallback inside
  `PlayerActivity`). Parser changes are fragile by nature — the site
  structure findings live in `docs/research/site-probe-report.md`;
  check it before touching `IkanHtmlParser`.
- **Fully independent apps.** This app never references, launches or
  shares state with the sibling WebView app （爱看电视）: no cross-app
  intents, no package-name literals of the other app, no shared storage.
  When the site's challenge page blocks the native path or parsing
  fails, the browsing UI says so in plain words and offers in-app
  retry (see `onLoadError` in the browsing activities). Do not
  reintroduce a cross-app escape hatch. (Cut clean in 2.8.3; before
  that, errors and the Menu key used to jump to the WebView app.)

## TV UI Rules (settled — do not relitigate)

The target box renders a 720p UI on a 65" 4K TV. These numbers were tuned
on the real TV and are final:

- Poster grid: 6 columns; card title 16sp with room for two full lines;
  section titles 20sp; body text floor 16sp, secondary floor 14sp.
- Focus: amber outline, scale 1.05. **No rounded corners anywhere** on
  poster pages — posters, search box and focus frames are all square.
- Page margins 48dp; bottom navigation labels 14sp.
- Navigation must be fully drivable by D-pad alone; every interactive
  element focusable, focus always visible, Menu key returns home.

## Assets & Dependencies Policy

- The repo is **text-only**. Binary assets (launcher icons, TV banner,
  glyphicons fonts) live as base64 in `ci-assets/` with SHA-256 in
  `ci-assets/MANIFEST.txt`; `build.sh` restores and verifies them, and
  `.gitignore` keeps the restored files out of the tree. Adding a binary
  asset means adding its `.b64` + manifest line, never committing the raw
  binary (the upload path for this repo rejects non-UTF-8 files).
- Dependencies are **fetched, never vendored**: only libVLC-all and Jsoup,
  pinned by version + SHA-256 in `tools/fetch-deps.sh`. Prefer libraries
  already in use over new ones; no AndroidX in the manual chain.
- Keep the tree clean: no build output, no APKs, no page snapshots, no
  placeholder files "for structure". Follow Occam's razor — small
  single-purpose files that only hold fields are merged, not kept.

## Security & Signing

- The plain signing keystore (`*.jks`) **never** enters git — `.gitignore`
  enforces it. Signing material reaches builds only as described in
  `keystore/README.md` (GitHub Secret `SIGNING_KEYSTORE_B64`, or the
  base64 file in this private repo). The signature is the app's identity:
  a differently-signed APK cannot upgrade the installed app, so never
  generate a fresh keystore.
- Never print keystore passwords or secret values into logs, commits or
  docs. Do not add analytics, trackers or new network endpoints; the app
  talks only to the content site and its image hosts.

## Commit Guidelines

History uses Chinese, descriptive subjects, often prefixed with a version
or scope, e.g. `2.8.1 片名两行整字 + 行距收紧` or
`fix(native): 历史迁移桥永不触发的缺陷`. Keep commits small and scoped;
one UI tune or one fix per commit, with the reason in the body when it
isn't obvious from the subject.

**Commit identity (do not improvise)**: author/committer is the repo owner
`Leev1s <83260380+Leev1s@users.noreply.github.com>` — GitHub links commits
by email, and the bare `username@users.noreply.github.com` form without
the numeric ID once mis-attributed commits to a stranger's account.
Commits substantially authored by an AI agent add a trailer line
`Co-authored-by: Muse <muse@localhost>` after a blank line.

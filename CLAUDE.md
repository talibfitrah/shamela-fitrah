# Shamela (المكتبة الشاملة) — Claude Developer Guide

Android reader for the Shamela library (shamela.ws). **Kotlin + Jetpack Compose** app under `android/` (package `org.murabbie.shamela`, v1.2.1 / versionCode 12, minSdk per `android/app/build.gradle.kts`, targetSdk 36), plus the data pipeline that feeds it: **Python** scripts in `tools/`, a **Java / Lucene** extractor in `extractor/`, and a JVM connectivity harness in `jvmtest/`. Repo: `git@github-fitrah:talibfitrah/shamela-fitrah.git` (branch `main`).

`README-DEVELOPER.md` (Arabic) is the handoff document and the source of truth for architecture, the code map, the book-DB schema, the data pipeline and the Play release steps. **Read it before any non-trivial change.** This file does not repeat it; it records the rules.

---

## How the app works — the 30-second version

The APK carries **no books**. It ships an index (`assets/master.gzdb`, ~6 MB) and fetches everything else from a Synology NAS (`files.murabbie.org`) via public share links listed in `manifest.json`:

- **Books** — one 8.9 GB `books.pack`; each book is a gzipped SQLite DB fetched by **HTTP Range** using `book_file` offset/length from the index.
- **Services** — `services.db` (70 MB gzipped): narrators, takhrij, isnads, Quran/tafsir, roots. Downloaded once on demand.
- **New books** — scraped page-by-page from shamela.ws (`ShamelaWeb.kt`) into the same DB format.

Two flavors (dimension `dist`): **`nas`** (direct APK, self-update, `REQUEST_INSTALL_PACKAGES`) and **`play`** (Google Play AAB, no self-update, `BuildConfig.PLAY_STORE = true`).

---

## Invariants — do not "fix" these (MANDATORY)

Each of these looks like a bug or a smell to a cold reader. Each is deliberate, and most were learned from a production failure.

1. **`NasHttp`: the `/sharing/<id>` cookie request and the `/fsdownload/...` request must travel on the same TCP connection.** DSM runs multiple workers; splitting the two yields intermittent 404s. That is why `NasHttp.kt` builds a dedicated OkHttp client with an in-memory cookie jar per operation. Do not introduce a shared client, a connection pool tweak, or a "simpler" singleton.
2. **`NasHttp.fetchText` rejects HTML responses for share links ONLY.** An HTML body there is a DSM error page. shamela.ws pages are legitimately HTML. Generalizing this check was a past bug — do not repeat it.
3. **Arabic normalization lives in three places that must stay byte-for-byte equivalent:** `android/.../data/ArabicText.kt`, `tools/shamela_common.py`, `extractor/src/main/java/shamela/Norm.java`. Change one → change all three **and** rebuild the FTS indexes. A mismatch silently breaks search; nothing crashes.
4. **No credentials in the app, ever.** Everything on the NAS is a public share link. There is no account, no token, no password in the client.
5. **Manifest selection:** the app picks the newer of stored vs bundled manifest by the `generated` field. When the server manifest changes, copy it into `android/app/src/main/assets/manifest.json` before building a release.
6. **Only the `play` flavor goes to Google Play.** The `nas` flavor's install-packages permission gets the app rejected.
7. **Bump `versionCode` and `versionName`** in `android/app/build.gradle.kts` on every release.
8. **One `books.pack`.** Adding books means a new/extended pack + updated `book_file` rows + a new `data.version` — not per-book files.

---

## Secrets and production (MANDATORY)

- `signing/` (release keystore + `keystore.properties`) is gitignored and **must stay that way**. Never `git add -f` it, never print its contents, never copy the passwords into any tracked file, log, commit message, or subagent brief. Losing or leaking that key is unrecoverable for the Play listing.
- The DSM **upload** account used by `tools/publish.py` is not in this repo and is not to be stored in it. It comes from the project owner per session (env var or prompt), and is never written to disk here.
- **`tools/publish.py` writes to the production NAS that every installed app reads.** Never run it (`data`, `services`, or `app`) without an explicit go-ahead from the user *for that specific run*. A bad manifest breaks every install at once. Same for anything that uploads to Play Console.
- `jvmtest/` and `tools/update_from_web.py` hit the **live** NAS and shamela.ws. Read-only, so allowed — but be economical: never pull the full `books.pack` (8.9 GB) or crawl all 40 shamela.ws categories just to test something. Range-fetch one small book.
- Before deleting or regenerating `master.gzdb`, `tools/manifest.json`, `tools/ents.pkl` or `tools/s1_table.json`, look at what is there — they are the products of a multi-hour pipeline over a 13.3 GB source archive.

---

## Build and verify

Requirements: JDK 17, Android SDK 36 (AGP 8.9.3), Gradle 8.14. **The project has no Gradle wrapper.** As of 2026-09-19 nothing is on PATH, but the toolchain exists: JDK 17 at `/usr/local/opt/openjdk@17` (Homebrew), Gradle 8.14.3 at `~/.gradle/manual/gradle-8.14.3/bin/gradle`, Android SDK at `~/Library/Android/sdk`; still **no `mvn`**, so the extractor build is *blocked*, not passed — say so. Export `JAVA_HOME`, `ANDROID_HOME` and put `$JAVA_HOME/bin` on PATH before any Gradle command. Run `bundle` and `assemble` tasks in **separate** invocations (ABI splits switch off when a bundle task is requested). With a phone plugged in, pin adb to the emulator (`adb -s emulator-5554`). Re-check with `ls` rather than trusting this line.

| Goal | Command (run from) |
|---|---|
| Compile the app (fastest real check) | `gradle :app:compileNasDebugKotlin` (`android/`) |
| Direct-distribution APK | `gradle assembleNasRelease` (`android/`) → `app/build/outputs/apk/nas/release/` |
| Google Play bundle | `gradle bundlePlayRelease` (`android/`) → `app/build/outputs/bundle/playRelease/` |
| Connectivity check against live NAS / shamela.ws | `gradle run` (`jvmtest/`), after setting `mainClass` in `jvmtest/build.gradle.kts` to `NasTestKt`, `WebTestKt` or `TestMainKt` |
| Extractor | `mvn package` (`extractor/`) |
| Python tools syntax | `python3 -m py_compile tools/*.py` |

Release builds need `signing/shamela-release.keystore` and `signing/keystore.properties` copied next to `android/` per README §3 — copies are gitignored; keep them so.

**There is no unit-test suite.** `jvmtest/` is the only automated check, and it compiles the app's real `NasHttp` / `ShamelaWeb` sources on a plain JVM. For new non-trivial pure logic (normalization, parsing, search query building) leave one small runnable check in `jvmtest/` rather than standing up a test framework. UI changes are verified on a device or emulator — say plainly when that was not done.

---

## Anti-sycophancy (MANDATORY)

These rules apply to every session, including compacted, resumed, and handed-off sessions. Treat them as a session-level invariant, not optional style guidance.

**Banned openers**: "You're absolutely right!", "Great point!", "Excellent!", "Brilliant!", "Love this!", "I understand your concern, however…", "That's a valid approach, but…".

**On pushback**: don't capitulate by reflex. If the user is right, say what you got wrong and own it. If the user is wrong, defend the answer with evidence, not politeness. If uncertain, say so and lay out both sides without hedging.

**Output shapes**:
- "Review this" → 3 worst issues first, then minor issues, then what works.
- "Is this a good idea?" → lead with failure modes, then strengths, then a verdict with confidence %.
- "What do you think of my plan?" → name the weakest link first.
- "Should I do X or Y?" → pick one with reasoning. No "both have merit" cop-outs.

**Anchoring bias**: answer the question in isolation first, ignoring how the user framed it. Then compare to what the user seems to want and surface any gap.

**Self-correction**: if you catch yourself being sycophantic mid-response, stop and restart. If the user calls it out, acknowledge it, fix the response, and ask whether this rule should be strengthened here.

---

## Coding rules (MANDATORY)

Ponytail is enforced separately by its own SessionStart hook and governs solution SIZE; these govern assumptions, blast radius and verification, which ponytail does not cover. This section is the canonical copy for this repo (there is no `~/.claude/coding-rules.txt` hook on this machine).

1. **Before coding** — state assumptions; present both readings rather than silently picking one; say so when a simpler approach exists. Reversing a commitment already made to the user is a decision to surface, not to explain afterwards.
2. **Simplicity** — minimum code, nothing speculative, no abstraction for single-use code. **This applies to checks too**: an assertion coupled to source formatting breaks on a reformat and the tempting fix is to loosen it, which silently removes the guard. Assert the invariant.
3. **Surgical changes** — every changed line traces to the request. Remove orphans your change created; mention pre-existing dead code, do not delete it (the same rule the Bloat Audit in stages 1 and 8 follows). This codebase is a handoff from another developer — match its style (single-file screens, plain SQLite, no DI framework, no Room) instead of modernizing it in passing.
4. **Verify, never assert** — turn the task into a checkable goal first; never claim done without running the check in the same turn and reading the output; a guard you have not watched fail is not verified. Prefer a live probe or the real artifact (a compiled APK, a `jvmtest` run, a row in a real book DB) over reasoning about what the code should do. **A gate that only checks what must be ABSENT lets a capability vanish silently — assert presence too.**

Route to the deeper skill when it fits: `superpowers:systematic-debugging` before proposing any bug fix, `superpowers:test-driven-development` before writing implementation code, `superpowers:verification-before-completion` before claiming anything passes.

---

## Mandatory 9-stage review pipeline

After **any coding work that changes repository files** (app, `tools/`, `extractor/`, `jvmtest/`, Gradle/Maven config, manifests, ProGuard rules), run the 9-stage review pipeline before treating the work as complete, and before committing or shipping. Docs-only changes (`*.md`, `play-store/` texts) are exempt.

Stages 1 and 8 use the **Bloat Audit** from the user-level **`code-upgrade`** skill (`~/.claude/skills/code-upgrade/bloat-audit.md`). It hunts what AI coders over-produce: dead code, helpers used once, safety checks for impossible problems, re-validation of already-validated data, middleman functions, "just in case" leftovers, and settings nobody changes. Its rules apply as written: plain language, investigate before flagging (watch for dynamic calls and reflection — R8 is on, check `proguard-rules.pro`), and **never delete pre-existing code without an explicit yes from the user** — bloat introduced by the current change may be removed directly.

1. **Bloat audit (pre)** — run the `code-upgrade` Bloat Audit scoped to the diff and the files just touched, before any other review. Strip self-introduced bloat now; queue pre-existing findings as questions for the user.
2. **Baseline** — inspect `git status`, identify the changed files, and run the relevant check from *Build and verify* for the touched area: Kotlin → `gradle :app:compileNasDebugKotlin` (and the `play` variant if flavor-specific code or manifests changed); network code → `jvmtest`; `tools/` → `py_compile`; `extractor/` → `mvn package`. Confirm `git status` shows nothing from `signing/` staged.
3. **Code reviewer** — a cold code-review pass over the diff against the base branch, prioritizing bugs / security / correctness (gstack `/review` or a code-review subagent). The reviewer must be given the *Invariants* section above, or it will "fix" them.
4. **Security review** — run `/cso` when the change touches: network input or `NasHttp` / `ShamelaWeb`, HTML parsing or `PageRenderer`, SQLite queries built from user text (`Search.kt`, FTS `MATCH` strings), downloaded-file handling and sha256 verification, the `nas` self-update / APK install path, `FileProvider` / export (`Exporter.kt`, `file_paths.xml`), permissions or manifests, signing config, or `tools/publish.py`.
5. **Adversarial challenge** — a second-opinion challenge pass, preferably **`codex`** (`/codex` challenge) or the closest available agent fallback.
6. **Consolidate findings** — merge findings, remove duplicates, classify severity, and decide what must be fixed before completion. Discard findings that contradict an *Invariant*; note that you did.
7. **Patch + re-review** — fix all Critical / Important / actionable findings and re-run targeted checks/review until they are closed.
8. **Bloat audit (post)** — re-run the `code-upgrade` Bloat Audit over the final cumulative diff. Fix rounds breed bloat too (orphaned helpers, dead branches from reworked fixes); clean it here so the final gate reviews lean code. Same rules as stage 1.
9. **gstack `/review` + Cubic** — run gstack `/review`, then **`cubic review --base <base-sha> --json`** (`cubic review -b <base-sha> -j`) from the repo root. Cubic is stochastic — re-run after each fix batch until **two consecutive rounds show no new P0/P1 findings**. P0/P1 findings block completion unless fixed or documented as a pre-existing / architectural deferral with a concrete follow-up.

If a stage cannot run (tool, credential, PR, network, or reviewer unavailable — **including the missing Gradle/JDK noted above**), **do not silently skip it** — state the blocked stage, the reason, and the best local substitute you ran.

---

## gstack

Use the gstack **`/browse`** skill for all web browsing (including inspecting shamela.ws endpoints); never use `mcp__claude-in-chrome__*` tools.

## Skill routing
- Product ideas / brainstorming → `/office-hours`
- Strategy / scope → `/plan-ceo-review`
- Architecture → `/plan-eng-review`
- Full review pipeline → `/autoplan` (or the 9-stage pipeline above)
- Bloat / dead code / simplify / dedupe → `code-upgrade` skill (Bloat Audit also runs as pipeline stages 1 and 8)
- Bugs / errors → `/investigate`
- Code review / diff check → `/review`
- Security → `/cso`
- Ship / PR → `/ship`

`/qa`, `/design-review`, `/canary`, `/land-and-deploy` target web apps — they do not apply to this Android app. Release is manual: README §6.

## graphify

No graph has been built for this repo, and it does not need one yet: 38 Kotlin files, 4 Java, 7 Python. **Grep is the correct tool here.** If the codebase grows enough that blast-radius questions get expensive, run `graphify update .` (AST-only, no API key) and use `graphify path "<SymbolA>" "<SymbolB>"` (add `--undirected` when directed returns nothing). Do not use `graphify query` / `explain` for natural-language questions. Verify graphify actually extracts Kotlin before relying on it — that has not been checked.

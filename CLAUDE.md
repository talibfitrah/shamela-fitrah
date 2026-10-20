# المكتبة الشاملة (Shamela) — Claude Developer Guide

Android port of al-Maktaba al-Shamela (shamela.ws, official database 1448): a reader and search tool for 8,591 books / 7.6 M pages that ships only the catalogue and fetches each book on demand from a Synology NAS. **Kotlin + Jetpack Compose** app under `android/` (package `org.murabbie.shamela`, v1.5.1 / versionCode 21, minSdk 24, compile/targetSdk 36), a JVM check harness in `jvmtest/`, a **Java + Lucene** extractor in `extractor/`, and **Python** data/NAS scripts in `tools/`. Repo: `git@github.com:talibfitrah/shamela-fitrah.git` (**public**; branch `main`). History: `85b6fac` 1.2.0 handoff → `5b663f1` the previous developer's 1.5.0 source imported from the NAS archive → 1.5.1 = 1.5.0 + the 1.2.1 Google Play fixes. Google Play has carried the app since 2026-09-28 (1.2.1 / 12).

This repo is a **handover package** from another developer (handed over 2026-09-14). The Arabic docs are the source of truth — **read them before any non-trivial change.** This file does not repeat them; it records the rules. Bare `§` numbers below refer to `README-DEVELOPER.md`.

| Doc | What it holds |
|---|---|
| `README-DEVELOPER.md` | package contents (§١), architecture, `manifest.json` and NAS (§٢), build and flavors (§٣), **code map** and book-DB format (§٤), data pipeline (§٥), Play release steps and compliance notes (§٦), decisions and lessons (§٧), owner (§٨) |
| `docs/README-NAS.md` | NAS folder contents, how the data was built, the DSM same-connection fix, update-from-web and rebuild commands. Its «بناء التطبيق» section is stale (`gradle assembleRelease`, `apk/release` — does not match the flavored build) |
| `docs/README-DEVELOPER.docx` | Word copy of the developer guide (§١; not compared) |
| `play-store/listing.md`, `privacy-policy.md`, `data-safety.md` | store texts, privacy policy (must be hosted at a public URL, §٦), Data-safety answers |

---

## How the app works — the 30-second version

The APK carries the catalogue but **no books**. On first run it installs `assets/master.gzdb` (`master.db`: categories, authors, books, and each book's position in the pack) with no network. `manifest.json` from the NAS (`files.murabbie.org`, permanent DSM share link) later offers a newer catalogue («تحديث الفهرس») and app version. No account and no credentials in the app (§٧).

- **Books** — one `books.pack` (8.9 GB) on the NAS; each book is a gzipped SQLite DB at `book_file.offset/length`. It is fetched with one HTTP Range request on first open (`Downloads.ensureInstalled`) or through the 2-worker queue (kept alive by the `dataSync` foreground service), sha256-checked, gunzipped to `books/<id>.db`, then read and searched offline.
- **Search** — FTS5 over normalized text, only in **installed** books (`Search.kt`); book/author-name search uses `book_norm` / `author_norm` in `master.db`; search by root needs `services.db`.
- **Services** — `services.db.gz` (70 MB → 225 MB: narrators, takhrij, asanid, Qur'an/tafsir, roots), downloaded on demand.
- **New books** — the app scans shamela.ws's category pages (`NewBooks.kt`) and scrapes books that are not in the pack yet, page by page, into the same DB format (`ShamelaWeb.kt`). Server-side, `tools/update_from_web.py` does the same and publishes a `books-upd-YYYYMMDD-HHMMSS.pack`.
- **Data build** (only for a new official release, §٥): `zipfetch.py` (Range-fetches the needed ~5.7 GB of the 13.3 GB official zip) → `extractor/` (Lucene stored fields → one SQLite per book → pack + `index.json`) → `make_master.py` → `decode_s1.py` + `build_services.py` → `publish.py`.

Two flavors (dimension `dist`, read via `BuildConfig.PLAY_STORE`): **`nas`** (APKs published on the NAS; `REQUEST_INSTALL_PACKAGES`; a «تنزيل الإصدار الجديد» button that opens the APK page in the browser) and **`play`** (AAB; `src/play/AndroidManifest.xml` removes that permission and `UpdatesScreen` in `MoreScreens.kt` hides the button). Both share the same `applicationId`, targetSdk and `dataSync` service. Since 1.2.1 the update icon, the «يتوفر إصدار جديد» subtitle and the server version line are also hidden when `PLAY_STORE` (`LibraryScreens.kt`, `MoreScreens.kt`); the `play` flavor is HTTPS-only (`src/play/AndroidManifest.xml` sets `usesCleartextTraffic="false"`, `Library.fetchManifest` ignores a non-https custom NAS base) and has a «سياسة الخصوصية» row in «المزيد» (Play requires the policy inside the app).

---

## Invariants — do not "fix" these (MANDATORY)

Each of these looks like a bug or a smell to a cold reader. Each is deliberate.

1. **The `play` flavor never gets `REQUEST_INSTALL_PACKAGES` or the APK-download button.** `src/play/AndroidManifest.xml` (`tools:node="remove"`) and the `!BuildConfig.PLAY_STORE` check at `UpdatesScreen` in `MoreScreens.kt` are Play-policy requirements (§٣, §٦, `play-store/data-safety.md`). Only `play` goes to Google Play — never upload a `nas` build. In `nas` the permission is declared but no code installs packages (the button only opens `apk_page` with `ACTION_VIEW`). That is pre-existing; ask before removing it.
2. **Arabic normalization is mirrored three ways:** `data/ArabicText.kt` `normalize()`, `extractor/src/main/java/shamela/Norm.java` and `tools/shamela_common.py` `normalize()` must match character for character. The book FTS indexes, `master.db` `book_norm`/`author_norm` and `services.db` `rawi_fts`/`aya_fts` were all built on that output. Change one → change all three **and** rebuild and republish the data (§٤, §٥). A mismatch silently breaks search; nothing crashes. `ArabicText.queryWords` (`:83-84`) uses a literal U+0001 character as a placeholder for `*`. It is invisible in most editors; do not "clean" it.
3. **The book-DB format is written in three places** — `Extract.java`, `update_from_web.py` `import_book()` and `ShamelaWeb.kt` `importBook()` — with identical tables and contentless FTS5 (`tokenize='unicode61 remove_diacritics 2'`), and the same `meta` `format_version` 1 / `normalizer` `arabic-v1`. The shamela.ws scraping (item regex, `convertNass`) is duplicated in `ShamelaWeb.kt` / `NewBooks.kt` and `update_from_web.py`: a site-markup change must be fixed on both sides.
4. **`NasHttp` quirks are DSM facts.** The `/sharing/<id>` priming request and the `/fsdownload` request must share **one TCP connection**, or nginx returns intermittent 404s (two DSM workers). Hence a new `OkHttpClient` with its own pool and in-memory cookie jar for each operation (`NasHttp.kt:22-86`; §٢ "لا تغيّر هذا السلوك"). `fetchText` rejects HTML/JSON-error bodies **only for share links**. shamela.ws pages are HTML, and generalizing the check was an earlier bug (§٧).
5. **The share links are load-bearing.** `FnGtHqpG9` (manifest) is compiled in as `DEFAULT_MANIFEST_URL` (`app/build.gradle.kts:26`, override `-PmanifestUrl`; also hard-coded in `jvmtest`), so every build talks to production by default. The bundled `assets/manifest.json` carries the master / `books.pack` / services links (`9yfNvjjrR`, `e4hWIOI7a`, `qRj62kwSq`). Installed apps keep using them until their next manifest fetch, e.g. the daily background check (`App.kt:49-52`, skipped when the user turned off `autoUpdateCheck`), opening «المستجدات والتحديثات» (`MoreScreens.kt:171`), «فحص الآن», or a catalogue reinstall. `publish.py` reuses existing links by path. Never revoke, rotate or recreate a share. User overrides sit under pref key `manifestUrlCustom` on purpose (`Settings.kt:40`).
6. **`assets/master.gzdb` and `assets/manifest.json` are a pair.** The offline install takes its `dataVersion` from the bundled manifest (`Library.kt:94`). The app keeps whichever manifest has the newer `generated` string (`Library.kt:59-67`), a lexical compare that relies on `publish.py`'s `%Y-%m-%dT%H:%M:%SZ` format. §٧: copy the live manifest into `assets/` before every release. In 1.5.1 `master.gzdb` matches the bundled manifest's sha256 (`cf940681…`), and the bundled manifest is a copy of the live one.
7. **An empty `BookFile.pack` means "not on the NAS yet — fetch from shamela.ws".** `UserDb.extraBook()` builds books with `pack = ""`, and `Downloads.kt:90/146/262` route on it. It is not a placeholder to clean up.
8. **Cleartext HTTP only in `nas`:** `usesCleartextTraffic="true"` in `src/main` serves a user-entered LAN address (`NasHttp.setCustomBase`, `Settings.kt`); the old `http://nas.fitrahmedia.nl:5000` fallback was removed in 1.2.1, and `src/play/AndroidManifest.xml` overrides cleartext to `false`. `play-store/data-safety.md` answers "encrypted in transit: yes" for the play build only.
9. **Every release is signed with the same key** (`shamela-release.keystore`; alias `shamela` in `keystore.properties.example`) and bumps `versionCode` in `app/build.gradle.kts` (§٣). The same key is the Play upload key (§٦). A different key = no NAS-installed user can update. `publish.py app <dir> <code> <name>` takes the version code by hand. It must equal the build's `versionCode`, because the update prompt compares the two (`MoreScreens.kt:179`).
10. **Bundled SQLite (`androidx.sqlite:sqlite-bundled`), not the platform one** — it carries FTS5 (`Db.kt:13`), and `proguard-rules.pro` keeps it. 16 KB page alignment of its native libs has not been checked for this app (the ahlhadeeth guide checks the same library with `zipalign -c -P 16 -v 4`).
11. **`jvmtest` compiles the app's real `data/` sources**, minus an exclude list (`jvmtest/build.gradle.kts:17`) and with a stub `BuildConfig` (`jvmtest/src/main/kotlin/org/murabbie/shamela/BuildConfig.kt`). A `data/` file that imports `android.*` must go on the list, and a new `BuildConfig` field used in `data/` must go in the stub. `Exporter.kt` and the 1.4–1.5 files that import `android.*` (`Backup.kt`, `FileImport.kt`, `Speaker.kt`) or depend on excluded classes (`Tashkeel.kt`) are on the list; `TestMainKt`, `NasTestKt` and `WebTestKt` ran green on 2026-10-20.

---

## Single source of truth — GitHub (MANDATORY)

The previous developer (DSM account `manus`) kept building from a private copy and published 1.4.2–1.5.0 to the NAS, while a parallel "1.4.2" was rebuilt on GitHub; the code forked and had to be merged by hand (2026-10-20). Rules since then:
- **GitHub `main` is the only source.** `shamela-android-project.tar.gz` on the NAS is a read-only snapshot that `tools/publish.py app` regenerates from the published commit (`git archive HEAD:android`; commit recorded as `app.commit`). Never build, edit or publish from it.
- **`publish.py app` refuses** unless the tree is clean, HEAD is in `origin/main` after a `git fetch`, `<code>` equals the `versionCode` at HEAD and in every APK (`aapt2`), it is newer than the local state, and the local state's `app` equals the NAS copy of `publish_state.json` (read before any upload). Offline check: `python3 tools/check_publish_guard.py`.
- **A build on the NAS or Play that is not on GitHub** (manifest `app.version_code` higher than any commit's `versionCode`, or no `app.commit`) means someone published outside git: import that source as a commit first (as `5b663f1` did), never overwrite it blind.
- `android/AGENTS.md` (also at the NAS folder root, and inside every regenerated archive) tells other developers and AI agents the same.

## Secrets and production (MANDATORY)

- **Gitignored and staying that way:** `signing/`, `keystore.properties`, `*.keystore`, `*.jks`, `local.properties`, `*.apk`, `*.aab` (`.gitignore`). **None of them is in this clone** (checked 2026-10-13; no `*shamela*.keystore` elsewhere under `daa3iyah-apps/` either). §١ ships `signing/`, `apk/`, `aab/` outside git; §٨ says the owner holds the key. Never `git add -f` them; never print, quote, or paste their contents into a tracked file, log, commit message or subagent brief.
- **NAS upload credentials** (`NAS_USER` / `NAS_PASS` env vars, `tools/shamela_common.py:6-9`) come from the owner per session; they are in neither the app nor the repo (§٢, §٨). Never write them to disk; never pass them on a command line that gets echoed back into the transcript. `Nas.login()` itself hands the password to `curl` as an argument (`shamela_common.py:68-70`), so it shows in the process list while it runs.
- **These write to the production NAS every installed app reads — never run them without an explicit go-ahead *for that specific run*:** every `tools/publish.py` subcommand (all but `file` re-upload the live `manifest.json`) and `tools/update_from_web.py`. That script logs into DSM even with `--dry-run` (line 166), and without `--no-upload` it runs `publish.py data`. **`publish.py` rebuilds the whole manifest from `tools/publish_state.json`, which is not in this repo** (`docs/README-NAS.md` lists it on the NAS). Without the current copy it starts from an empty state: `publish.py app` would push a manifest with no `master`, `packs` or `services`, and every install loses book and services downloads at its next manifest fetch (READ ONLY — traced in the code, not run). Treat anything uploaded to Play Console the same way.
- **Read-only live checks are allowed but be economical:** `jvmtest` `NasTestKt` (6 manifest fetches, `master.db.gz` ≈ 2 MB, 3 random books by Range) and `WebTestKt` (shamela.ws home, category 40, live manifest). Never pull `books.pack` (8.9 GB) or the official zip (13.3 GB) whole — one Range request, one book. shamela.ws is a third-party site, and the web importers fetch it once per page.
- **No paid or quota-limited API** is used anywhere (a grep for keys and tokens finds only the NAS and keystore passwords).
- Content rights: book texts come from al-Maktaba al-Shamela; §٦ requires crediting it in the store listing and in «عن التطبيق». Do not add sources or scrapers on your own initiative.

---

## Build and verify

Requirements: JDK 17 for the app (`jvmTarget 17`). JDK 17 for `jvmtest` too (`jvmToolchain(17)` since 1.2.1). **JDK 21 for the extractor** (`pom.xml` source/target 21, built with **Maven**). Gradle 8.14 (§٣; the tracked `gradle-wrapper.properties` says 8.14.3), AGP 8.9.3, Kotlin 2.0.21, compile/targetSdk 36, Android Studio Ladybug or newer. Play has required targetSdk 36 for updates since 2026-08-31; 1.5.1 targets 36. **No usable Gradle wrapper:** `android/gradle/wrapper/gradle-wrapper.properties` is tracked, but there is no `gradlew` and no `gradle-wrapper.jar`.

As of 2026-10-13 on this machine (Linux), `java` on PATH is OpenJDK 11.0.32.1 with no `javac` (`/usr/lib/jvm` holds JDKs 8 and 11 only), and `gradle`, `adb`, `mvn` and `sdkmanager` are not on PATH. The toolchain exists off PATH: JDK 17 (Temurin 17.0.20.1) at `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`, Gradle 8.14.3 at `~/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle`, and the Android SDK at `~/.local/android-sdk` (platforms 35 + 36, build-tools 35.0.0 + 36.0.0, `platform-tools/adb`, NDK 27.1.12297006). Python 3.10.12 and `curl` are on PATH. **There is no JDK 21 and no Maven** (`~/.m2` absent), so the extractor is BLOCKED until both are installed; report it as BLOCKED, never as passed. `jvmtest` needs only JDK 17 since 1.2.1. Export `JAVA_HOME` (JDK 17) and `ANDROID_HOME`, and put `$JAVA_HOME/bin` on PATH before any Gradle command; `android/local.properties` (absent, gitignored) can carry `sdk.dir` instead. `gradle.properties` gives the daemon `-Xmx3g` with parallel builds on, so run one Gradle build at a time and prefer `--max-workers` (user-level RAM rule). Re-check with `ls` rather than trusting this paragraph. **None of the commands below was run while writing this file.**

| Goal | Command (run from) |
|---|---|
| Compile the app (fastest real check) | `gradle :app:compileNasDebugKotlin` (`android/`) |
| Same for the store flavor — **required when flavor code, manifests or `PLAY_STORE` checks change** | `gradle :app:compilePlayDebugKotlin` (`android/`) |
| Pure-logic checks (normalization, FTS query, manifest parsing, `convertNass`, optional real book DB) | set `mainClass` to `TestMainKt` in `jvmtest/build.gradle.kts` (default `WebTestKt`; restore it after), then `gradle run --args="/path/to/30198.db"` (`jvmtest/`). Read `/tmp/jvmtest.out`. The DB checks are hard-wired to book 30198, and `ALL PASSED` is written even when they were skipped (stdout says `SKIP`) |
| Live checks (read-only) | `mainClass` `NasTestKt` → `/tmp/nastest.out` (`OKHTTP END-TO-END: PASS`); `WebTestKt` → stdout (`WEB TEST: PASS`). **No jvmtest program sets an exit code — exit 0 proves nothing; read the output** |
| Extractor | `mvn package` (`extractor/`) → `target/shamela-extractor-1.0.jar`; run it as in `docs/README-NAS.md` (needs the official data from `zipfetch.py`) |
| Direct-distribution APKs | `gradle :app:assembleNasRelease` → `app/build/outputs/apk/nas/release/` (arm64-v8a, armeabi-v7a, universal) |
| Google Play bundle | `gradle :app:bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab` |
| Install permission only in `nas` | `~/.local/android-sdk/build-tools/35.0.0/aapt dump permissions <apk>`: a `play` APK must not list `REQUEST_INSTALL_PACKAGES`, a `nas` APK must |
| Python scripts syntax | `python3 -m py_compile tools/*.py` |

Release signing comes from `android/keystore.properties` (see `keystore.properties.example`). **Without that file a release build still succeeds but is unsigned** (`app/build.gradle.kts:37-53`). §٣ step 2 puts the keystore in `android/`, but `storeFile` resolves through `rootProject.file()`, i.e. relative to `android/`. The example `../shamela-release.keystore` therefore points at the repo root; check the path before the first release. The NAS copies are named `shamela-<ver>-{universal,arm64,arm32}.apk` (`tools/manifest.json`), while `app/build.gradle.kts` sets no output file name, so the rename before `publish.py app` is an undocumented manual step.

**There is no unit-test framework and no instrumented tests** (no `test/` or `androidTest/` dirs). `jvmtest/src/main/kotlin/TestMain.kt` is the only offline automated check: one `check(name, ok)` helper writing to `/tmp/jvmtest.out`. For new non-trivial pure logic in `data/`, add a few `check(...)` lines there rather than standing up JUnit. UI, the download service, Word/PDF export and the store build's behaviour are verified on a device or emulator. Say plainly when that was not done.

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

**Strict mode (owner asked for "stricter" on 2026-09-20 — these bind every report, status update and summary):**
- **Bad news first, always.** A report opens with what is broken, blocked, unverified or still owed — before anything that passed. "All green" is never the first line unless nothing else is true.
- **Your own mistakes lead, unprompted.** If you broke something during the work (a bad edit, a check that silently did not run, a wrong claim you later retracted), say so at the top with what it cost, not in a footnote.
- **No reassurance adjectives.** Banned in reports: "clean", "lean", "solid", "in good shape", "ready", "better than most", "routine", "comfortable", "safe" (as a verdict), "should be fine". State the measurement instead: what ran, the count, the exit code, what was not run.
- **"Verified" means watched.** Only write verified/confirmed for something you ran and read in this session. Everything else is labelled exactly one of: NOT RUN, READ ONLY (code reading, no execution), or REPORTED BY AGENT (a subagent said so; you did not re-check). An agent's "both flavors compile" is REPORTED until you compile.
- **Never round a risk down to make the owner comfortable.** Give likelihood as H/M/L with the evidence, and if the honest answer is "this will probably be rejected / removed", say that sentence.
- **No approval-seeking closers.** Do not end with a question about whether the tone, rule or work was satisfactory. End on the next required action or the open decision.
- **Disagree in the first sentence.** If the owner's instruction is likely to cause a rejection, data loss or a security problem, say so before doing it — then do it if they still want it. Doing it silently and mentioning the downside afterwards is the failure mode.
- **Do not pad a decision list to look thorough, and do not shrink it to look finished.** Every open decision is listed once, with your pick and the cost of being wrong.

---

## Coding rules (MANDATORY)

Ponytail is enforced separately by its own SessionStart hook and governs solution SIZE; these govern assumptions, blast radius and verification, which ponytail does not cover. This section is the canonical copy for this repo (there is no `~/.claude/coding-rules.txt` hook on this machine).

1. **Before coding** — state assumptions; present both readings rather than silently picking one; say so when a simpler approach exists. Reversing a commitment already made to the user is a decision to surface, not to explain afterwards.
2. **Simplicity** — minimum code, nothing speculative, no abstraction for single-use code. **This applies to checks too**: an assertion coupled to source formatting breaks on a reformat and the tempting fix is to loosen it, which silently removes the guard. Assert the invariant.
3. **Surgical changes** — every changed line traces to the request. Remove orphans your change created; mention pre-existing dead code, do not delete it (the same rule the Bloat Audit in stages 1 and 8 follows). This codebase is a handoff from another developer — match its style instead of modernizing it in passing. That style is: one module `:app` split into `data/` and `ui/`; `data/` classes built once in `App.onCreate()` plus `object` singletons (`NasHttp`, `BookStore`, `Search`, `ShamelaWeb`, `ArabicText`, `Exporter`); Compose screens that take `app: App` and a `NavHostController`, several per file (`LibraryScreens.kt`, `MoreScreens.kt`, `ServicesScreens.kt`); `StateFlow` state; raw SQL through the small `Db` wrapper; `org.json`; Arabic KDoc; dense one-line functions. There is no DI framework, no Room, no repository layer and no ViewModel (`lifecycle-viewmodel-compose` is declared but nothing uses it). §٧ «مقترحات للتطوير» lists improvements the previous developer suggested; they are suggestions, not a mandate.
4. **Verify, never assert** — turn the task into a checkable goal first; never claim done without running the check in the same turn and reading the output; a guard you have not watched fail is not verified. Prefer a live probe or the real artifact over reasoning about what the code should do: a Kotlin compile of **both** flavors, a `jvmtest` run read from `/tmp/jvmtest.out`, a query against a real book DB, `aapt dump permissions` on the built APKs. **A gate that only checks what must be ABSENT lets a capability vanish silently — assert presence too.**

Route to the deeper skill when it fits: `superpowers:systematic-debugging` before proposing any bug fix, `superpowers:test-driven-development` before writing implementation code, `superpowers:verification-before-completion` before claiming anything passes.

---

## Mandatory 9-stage review pipeline

After **any coding work that changes repository files**, run the 9-stage review pipeline before treating the work as complete, and before committing or shipping. That covers the app under `android/`, `jvmtest/`, `extractor/`, `tools/*.py`, Gradle config, Android manifests, ProGuard rules, and the bundled `assets/manifest.json` / `master.gzdb`. Docs-only changes (`*.md`, `docs/`, `play-store/`) are exempt.

Stages 1 and 8 use the **Bloat Audit** from the user-level **`code-upgrade`** skill (`~/.claude/skills/code-upgrade/bloat-audit.md`). It hunts what AI coders over-produce: dead code, helpers used once, safety checks for impossible problems, re-validation of already-validated data, middleman functions, "just in case" leftovers, and settings nobody changes. Its rules apply as written: plain language, investigate before flagging (watch for dynamic calls, reflection — R8 is on for release and `proguard-rules.pro` keeps all of `org.murabbie.shamela.data.**` — **`jvmtest/` compiling `data/` sources**: a `data/` symbol unused in the app may be used only from `jvmtest/`; **`tools/*.py` importing each other**: `update_from_web.py` imports `publish` and `make_master.build`; flavor differences are only `BuildConfig.PLAY_STORE` checks and `src/play/AndroidManifest.xml`, with no flavor Kotlin source set), and **never delete pre-existing code without an explicit yes from the user** — bloat introduced by the current change may be removed directly.

1. **Bloat audit (pre)** — run the `code-upgrade` Bloat Audit scoped to the diff and the files just touched, before any other review. Strip self-introduced bloat now; queue pre-existing findings as questions for the user.
2. **Baseline** — inspect `git status`, identify the changed files, and run the relevant check from *Build and verify* for the touched area: Kotlin → `gradle :app:compileNasDebugKotlin` **and** `:app:compilePlayDebugKotlin`; anything under `data/` → `jvmtest` `TestMainKt` (read `/tmp/jvmtest.out`), plus `NasTestKt` / `WebTestKt` when `NasHttp` or `ShamelaWeb` change; normalization → diff `ArabicText.kt`, `Norm.java` and `shamela_common.py` side by side; `tools/*.py` → `py_compile`; `extractor/` → `mvn package`. Confirm `git status` shows no `signing/`, keystore, `keystore.properties`, `local.properties` or APK/AAB staged.
3. **Code reviewer** — a cold code-review pass over the diff against the base branch, prioritizing bugs / security / correctness (gstack `/review` or a code-review subagent). The reviewer must be given the *Invariants* section above, or it will "fix" them.
4. **Security review** — run `/cso` when the change touches: `NasHttp.kt` or any network input (share-link priming, Range resume, host rotation and the user-entered base), `Manifest.parse` (`Models.kt`), downloaded-file handling and sha256 checks (`Downloads.kt`, `Library.kt`, `Services.kt`), HTML/JSON scraped from shamela.ws (`ShamelaWeb.kt`, `NewBooks.kt`, `update_from_web.py`), book HTML rendering and `inr://` links (`PageRenderer.kt`), FTS `MATCH` strings or SQL built from user text (`ArabicText.ftsQuery`, `Search.kt`, `BookStore.kt`, `Services.kt`, `Library.kt`), export files and the `FileProvider` (`Exporter.kt`, `ExportDialog.kt`, `res/xml/file_paths.xml`), deep links (`MainActivity.routeFrom`), manifests / permissions / the foreground service, signing config, or any script that talks to the NAS (`shamela_common.py`, `publish.py`, `update_from_web.py`).
5. **Adversarial challenge** — a second-opinion challenge pass, preferably **`codex`** (`/codex` challenge) or the closest available agent fallback.
6. **Consolidate findings** — merge findings, remove duplicates, classify severity, and decide what must be fixed before completion. Discard findings that contradict an *Invariant*; note that you did.
7. **Patch + re-review** — fix all Critical / Important / actionable findings and re-run targeted checks/review until they are closed.
8. **Bloat audit (post)** — re-run the `code-upgrade` Bloat Audit over the final cumulative diff. Fix rounds breed bloat too (orphaned helpers, dead branches from reworked fixes); clean it here so the final gate reviews lean code. Same rules as stage 1.
9. **gstack `/review` + Cubic** — run gstack `/review`, then **`cubic review --base <base-sha> --json`** (`cubic review -b <base-sha> -j`) from the repo root. Cubic is stochastic — re-run after each fix batch until **two consecutive rounds show no new P0/P1 findings**. P0/P1 findings block completion unless fixed or documented as a pre-existing / architectural deferral with a concrete follow-up.

If a stage cannot run (tool, credential, PR, network, or reviewer unavailable — **including the missing JDK 21 and Maven, the off-PATH JDK 17 / Gradle**), **do not silently skip it** — state the blocked stage, the reason, and the best local substitute you ran.

---

## gstack

Use the gstack **`/browse`** skill for all web browsing (including inspecting shamela.ws, DSM share pages or Play Console docs); never use `mcp__claude-in-chrome__*` tools.

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

`/qa`, `/design-review`, `/canary`, `/land-and-deploy` target web apps — they do not apply to this Android app. Release is manual: `README-DEVELOPER.md` §٣ (build) and §٦ (Play).

## graphify

No graph has been built for this repo (`graphify-out/` does not exist), and it does not need one yet. It has 38 Kotlin files (34 app + 4 jvmtest, 5,897 lines), 4 Java and 7 Python files, with a code map in §٤. **Grep is the correct tool here.** If blast-radius questions get expensive, run `graphify update .` (AST-only, no API key) and use `graphify path "<SymbolA>" "<SymbolB>"` (add `--undirected` when directed returns nothing). Do not use `graphify query` / `explain` for natural-language questions. Verify graphify actually extracts Kotlin before relying on it — that has not been checked.

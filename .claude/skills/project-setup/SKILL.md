---
name: project-setup
description: First-time setup of this Audiveris repository. Detects the OS, checks and installs what is missing (JDK 25, JAVA_HOME, OCR language data, optional MuseScore), builds the launcher, runs smoke, end-to-end and unit tests, then reports whether the project is ready. Safe to re-run. Use when the user asks to set up, install, bootstrap, prepare or check the project / its dependencies / environment, or when a build fails because of a missing JDK or tool.
---

# Project setup (Audiveris)

One script does everything and is idempotent: already-present components are detected and
skipped, the build is skipped when the launcher is up to date, and the tests are skipped when
they already passed for the current commit and build.

## Run it

```bash
.claude/skills/project-setup/scripts/setup.sh
```

Options:
- `--check`: report only, change nothing (use it when the user only asks "is it set up?").
- `--force`: rebuild and re-run all verifications.
- `--no-musescore`: do not install MuseScore (only needed for PDF output of the Claude
  vision mode). Use it if the user does not want extra software.
- `--full-tests`: run the whole unit test suite (about 220 tests) instead of the quick subset.

The script runs in Bash (Git Bash on Windows). It prints a few progress lines and a final
report; the full log is `app/build/setup.log`. The first run can take several minutes
(downloads of the JDK, Gradle and dependencies): run it with a timeout of at least 20 minutes.

## What it does

| Step | Details |
| :--- | :--- |
| Environment | OS (Windows / macOS / Linux, WSL), architecture, package manager, passwordless sudo |
| Tools | `git`, `curl`, `tar` |
| JDK 25+ | Looks in `JAVA_HOME`, `PATH` and usual install folders. Installs Eclipse Temurin 25 with winget / Homebrew / apt / dnf, or falls back to the official archive from adoptium.net in `~/.local/jdks` (no admin rights needed) |
| `JAVA_HOME` | Sets it for the current run; persists it if needed (Windows user variable via `setx`, or a marked block in `~/.profile` / `~/.zprofile`) |
| Gradle | Wrapper version (downloaded automatically by the first build) |
| OCR data | Tesseract language files used by the standard engine; downloads English (`eng.traineddata`) from github.com/tesseract-ocr/tessdata if none is installed |
| MuseScore (optional) | Installs MuseScore 4 (winget / Homebrew cask / apt `musescore3` / flatpak) for PDF output |
| Build | `./gradlew :app:installDist` → `app/build/install/app/bin/Audiveris` |
| Verification | Smoke test (`-batch -help`); end-to-end standard OMR on `data/examples/zizi.png`; end-to-end Claude vision pipeline (`prepare` + `finish` of `data/examples/claude/zizi.claude.json`, PDF if MuseScore); unit tests |

Statuses in the report: `present` (already available), `installed`, `configured`, `built`,
`passed`, `skipped`, `missing`, `FAILED`. Optional components never block readiness.
The last line is `RESULT: READY` or `RESULT: NOT READY`.

## After running it

- Give the user the report in a few lines: what was installed or configured, what was already
  there, and the result. Mention the notes (for example: open a new terminal for `JAVA_HOME`).
- If NOT READY: read only the relevant end of `app/build/setup.log` (or the file named in the
  FAILED line, e.g. `app/build/setup-e2e/standard.log`), fix the cause, then re-run the script.
  Typical causes:
  - winget / Homebrew / apt needs elevation or a password: ask the user to run the printed
    install command themselves (e.g. `winget install EclipseAdoptium.Temurin.25.JDK`), then
    re-run the script.
  - No network: downloads (JDK, Gradle, dependencies, OCR data) fail; retry when online.
- Do not install anything else than what the script installs without asking the user.
- Once READY, the project is usable: the GUI with `app/build/install/app/bin/Audiveris`, the
  batch mode with `... -batch -transcribe -export <input>`, and the Claude vision mode with
  the `claude-omr` skill.

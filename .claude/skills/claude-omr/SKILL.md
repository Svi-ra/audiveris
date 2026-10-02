---
name: claude-omr
description: Experimental Audiveris OMR mode using Claude Code's own vision. Transcribe a music score image or PDF into MusicXML (and PDF via MuseScore) by having Claude read pitch-labelled detail tiles prepared by Audiveris, write an audiveris-claude-omr JSON description, and letting Audiveris generate the MusicXML, optionally partitioned into one MusicXML + PDF per instrument or voice part. Use when the user asks to recognize / transcribe / OMR / convert a score "with Claude" or "with Claude vision", and also whenever the user gives one or more score images (or asks about a score image) and asks to "convert to PDF" — run this full pipeline (image → Claude vision → MusicXML → MuseScore PDF), never just wrap the image into a PDF. Also use it to extract / split / partition a score into separate instrument or voice parts.
---

# Claude vision OMR for Audiveris (experimental)

This mode splits the work in two:

- **You (Claude, in this session)** do the visual and semantic recognition only: you look at
  the prepared images with your own vision and write a JSON score description.
- **Audiveris** detects the layout and prepares the images, then converts your JSON description
  into MusicXML (divisions, durations, backups, measure checks, spanner numbering, page layout).
  MuseScore, when installed, engraves the PDF.

Never call the Anthropic API, never ask for or use an API key, never install an SDK.
Everything happens in this session with your built-in image reading (the Read tool on PNG).
The regular Audiveris OMR (`-batch -transcribe ...`) is untouched.

## Parts partitioning: decide before processing

The pipeline can partition a full score into **separate parts, one per instrument or voice**
(`finish --parts`): each part gets its own `<Part>.mxl` and `<Part>.pdf` in `<radix>-parts/`,
next to the full score, with all its musical content and the score metadata (see below).

**Before processing any score** (before `prepare`, before reading any image), know whether the
user wants this partitioning:

- If the request already says so, follow it: "with parts", "extract / split into parts",
  "one PDF per instrument", "parts for the players" → partition; "score only", "no parts",
  "just the full score" → do not.
- Otherwise **ask the user first** (AskUserQuestion, one question), for example
  "Should the score also be partitioned into separate parts, one per instrument or voice?"
  with the options "Full score + separate parts" and "Full score only".
  Wait for the answer, then start; do not ask again later in the same request.
- A score that turns out to have a single part (piano solo, one voice) has nothing to
  partition: `finish --parts` then prints `single-part score`; say so in the report.

What the partitioning keeps in every part: all measures, notes, rests, grace notes, ties,
slurs, tuplets, articulations, dynamics, hairpins, text directions, lyrics, clefs, keys, times,
barlines, repeats and endings, the part name and abbreviation, the number of staves (a piano
grand staff stays one part) and the transposition; title, subtitle, opus, composer, lyricist,
arranger, rights and credits; the part name printed top-left; tempo and metronome marks of the
top staff copied into every part that lacks them. System and page breaks of the full score are
dropped (the part is laid out on its own). Audiveris checks that each part file has the same
content as the part in the full score and fails the run otherwise.

## "Convert to PDF" requests

When the user supplies a score image or a set of images (pasted in chat or as files) and asks
to "convert to PDF" (or similar), this means the **full pipeline**, not embedding the picture
in a PDF:

0. Settle the parts partitioning question first (see above).
1. Locate the image file(s) (pasted images live under the session's temp `images/` folder).
   For several images, combine them into one multi-page input (e.g. a multi-page TIFF or PDF
   built with Pillow) in the scratchpad, ordered as given, so they form one score.
2. Run the workflow below: `prepare` → read tiles → write JSON → `finish` (MuseScore PDF;
   with `--parts` when the user wants separate parts).
   Default the output directory to the working directory unless the user names one.
3. End with the usual report plus a **token report** (see step 9).

## Keep the session cheap

Every step of this session re-reads the whole conversation, and every image costs tokens.
- Use the script below: it prints a few lines. Never dump logs or large tool outputs.
- Read `request.md` once, each overview once, each detail tile once. Do not make extra crops,
  pitch grids or pixel analyses unless a specific note stays ambiguous after reading its tile.
- Write the whole JSON in one go (for long scores, one file write per page or per system),
  preferably with a small generator script when parts repeat the same patterns.
- Do not render or view the preview more than once.

## Workflow

The script `.claude/skills/claude-omr/scripts/claude-omr.sh` (Bash; Git Bash on Windows) builds
the Audiveris launcher of this repository when needed, finds JDK 25 and MuseScore, writes full
logs to files and prints a short summary. Paths may contain spaces or commas.

1. **Prepare** (any format Audiveris reads: PNG, JPG, WebP, TIFF, PDF, multi-page):

   ```bash
   .claude/skills/claude-omr/scripts/claude-omr.sh prepare <input> [-o <out-dir>] [-s "1 3-4"]
   ```

   It writes `<out-dir>/<radix>-claude/` with `request.md`, `page-N.png`,
   `page-N-overview.png` and the detail tiles `page-N-s<sys>-m<a>-<b>-st<i>-<j>.png`
   (strips `page-N-strip-K.png` only for a page whose layout could not be detected).

2. **Read `request.md`**: it lists the images, the layout detected by Audiveris (staves, clefs,
   keys, provisional measure numbers per system) and the authoritative JSON format.

3. **Look at the images**:
   - Each **overview** once: identify the parts (instrument names on the page), which staves
     (`S1`, `S2`…) belong to which part, and check the measure count (`m1`, `m2`…).
     One part per instrument or singer voice, with its printed `name` (and `abbreviation`):
     these names title and name the part files when the score is partitioned. Two players
     sharing one staff ("Flauti", "2 Trombe", "S. A.") stay one part.
   - Each **detail tile** once. Staff lines are labelled with their pitch in the left margin
     (red), spaces in the right margin (blue), for the detected clef; dotted guides mark
     ledger-line positions. Labels ignore key signature and accidentals and follow the clef
     at the start of the system (adapt after a clef change). Verify detected clefs/keys and
     measure numbers: a missed or spurious barline shifts the numbering.

   Reading discipline (and go through the **reading checklist** of `request.md`: header and
   footer texts, tempo and metronome marks, hairpins, second-player rests on shared staves,
   grace-note flags, ties/slurs leaving the last measure, bowings and ornaments, key
   signatures; lines starting with `CHECK` in the layout point at likely detection errors):
   - Clefs, key and time signatures first; they drive every pitch.
   - Measure by measure, staff by staff; every part must have the same number of measures.
   - Durations of every voice must add up to the time signature (except a pickup,
     `"implicit": true`).
   - `alter` is the sounding alteration (key signature + accidentals carried through the
     measure + ties); `accidental` only describes a printed sign. Never invent a printed sign.
   - Transposing instruments (horn in F/E, clarinet in B-flat, double bass…): write pitches as
     printed and add `"transpose"` to the part.
   - Every hairpin (`wedge`) start needs a `stop` on the same staff.

4. **Write** the description to the path given in `request.md`
   (`<out-dir>/<radix>.claude.json`). With a generator script, build every JSON object
   afresh: never reuse one dict/list (e.g. a measure-rest constant) in several places and
   then modify it, or the change lands everywhere. A complete sample is
   `data/examples/claude/zizi.claude.json` (transcription of `data/examples/zizi.png`).

5. **Finish**:

   ```bash
   .claude/skills/claude-omr/scripts/claude-omr.sh finish <out-dir>/<radix>.claude.json [--parts]
   ```

   It writes `<radix>.mxl`, then `<radix>.pdf` and `<radix>-preview-N.png` if MuseScore is
   found (`--no-pdf`, `--no-preview` to skip). With `--parts` (user chose separate parts) it
   also writes `<radix>-parts/<Part>.mxl` and `<Part>.pdf` for every part and lists them
   (`Parts FAILED` means a part lost content: report it). While iterating on errors you may
   leave `--parts` out and add it on the final run. It prints errors (with a JSON path such as
   `$.parts[0].measures[3].voices[0].events[2].type`) or warnings (measure too long / too
   short, wedge never stopped…).

6. **Audit the contents**: `finish` prints a `Contents:` line (counts of tempos, dynamics,
   wedges, graces, ties, slurs, bowings, ornaments, credits...). Compare it with the page: a 0
   (or a clearly low count) for symbols the page shows means they were missed; re-open the
   tiles involved. Look once at the preview next to the page image for header, footer and
   layout problems.

7. **Fix and iterate**: re-open only the tiles of the measures involved, edit the JSON, run
   `finish` again until there are no errors and no unexplained warnings.

8. **Report** to the user: output file paths (`.mxl`, `.pdf`, and the `<radix>-parts/` folder
   with its part files when partitioned), number of parts/measures,
   remaining warnings and passages you were unsure about. Remind them this mode is
   experimental and the result should be proof-read (for example in MuseScore).

9. **Token report**: finish with a short report of the tokens spent on the task. Get the
   numbers from the session usage tool (`mcp__ccd_session_mgmt__get_usage`, load it with
   ToolSearch if deferred); if unavailable, use the `anthropic-skills:explain-usage` skill or
   say that exact figures are not available. Keep it to a few lines: input / output / cache
   tokens and total, plus the number of images read. Do not draw charts.

## Notes

- The equivalent raw commands are `Audiveris -batch -claude [-sheets 1 3-4] -output <dir>
  <input>` and `Audiveris -batch [-parts] -output <dir> <file.claude.json>` (launcher:
  `app/build/install/app/bin/Audiveris`, built by `./gradlew :app:installDist`; JDK 25).
- Environment overrides for the script: `AUDIVERIS` (launcher), `MUSESCORE`, `JAVA_HOME`.
- For a score the standard engine handles well, `Audiveris -batch -transcribe -export <input>`
  costs no tokens at all: suggest it when the user cares about cost.

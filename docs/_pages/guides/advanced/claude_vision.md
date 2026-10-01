---
layout: default
title: Claude vision OMR (experimental)
parent: Advanced features
nav_order: 8
---
# Claude vision OMR (experimental)
{: .no_toc }

This optional mode uses the vision capability of **Claude Code** to recognize the music on
score images, while Audiveris remains in charge of producing the MusicXML file.

{: .warning }
This mode is experimental. Always proof-read the resulting MusicXML,
for example by opening it in MuseScore and comparing it with the original score.

---
Table of contents
{: .no_toc .text-epsilon }
- TOC
{:toc}
---

## Principle

The work is split between Claude and Audiveris:

| Who | Does what |
| :--- | :--- |
| **Audiveris** | Loads the input file (PNG, JPG, WebP, TIFF, PDF, multi-page…), renders each page as a PNG image, runs its first engine steps to detect the layout (systems, staves, barlines, clefs, key signatures) and writes compact, pitch-labelled **detail tiles** plus a `request.md` file that describes the expected result. |
| **Claude Code** | Looks at the tiles with its own vision and writes a JSON score description (`<name>.claude.json`): parts, measures, clefs, keys, times, notes, rests, beams, ties, slurs, tuplets, articulations, dynamics, lyrics… |
| **Audiveris** | Reads the JSON description, computes divisions, durations and voice backups, checks that every measure is rhythmically complete, then writes MusicXML (`<name>.mxl`) with its regular ProxyMusic export. |
| **MuseScore** (optional) | Engraves the MusicXML as a PDF, plus a small preview image for a visual check. |

Key points:

- **No API key.** Audiveris never calls the Anthropic API and never connects to the network.
  Recognition is done by the Claude Code session you are already working in,
  which reads the image files from your disk.
- **The standard Audiveris engine is untouched.** Without the `-claude` option and without a
  `.claude.json` input, Audiveris behaves exactly as before.
  The Claude mode only *uses* the first engine steps (up to `HEADERS`) to find the layout,
  without creating any `.omr` book.
- The Audiveris editor cannot be used to correct the result.
  Make corrections in the JSON description (or ask Claude to make them), then convert again.

## Requirements

- A **build of this fork** of Audiveris (it contains the `-claude` option),
  which requires **JDK 25**, for example Eclipse Temurin 25
  (`winget install EclipseAdoptium.Temurin.25.JDK` on Windows).
  See [Sources](../../tutorials/install/sources.md).
- **Claude Code** (CLI, desktop app or IDE extension) opened on the Audiveris repository folder,
  so that the `claude-omr` skill (`.claude/skills/claude-omr/SKILL.md`) is available.
- Optionally **MuseScore 4** (or 3), to get a PDF. It is found on the `PATH`, in its default
  install location, or through the `MUSESCORE` environment variable.

## Quick start: let Claude do everything

Open Claude Code in the Audiveris repository and ask, for example:

> Transcribe `D:/scores/minuet.pdf` to PDF with Claude vision,
> output in `D:/scores/out`.

Claude then uses the `claude-omr` skill to run the steps below:
it prepares the tiles, reads them, writes the description, converts it,
fixes any reported problem and engraves the PDF.
At the end it reports the paths to the `.mxl` and `.pdf` files, the remaining warnings
and the passages it was unsure about.

The sections below describe each step, if you want to run them yourself.

## Step by step

All steps go through one script, which builds the Audiveris launcher of the repository when
needed (first run, or sources changed), finds JDK 25 and MuseScore, writes full logs to files
and prints only a short summary:

```bash
.claude/skills/claude-omr/scripts/claude-omr.sh prepare <input> [-o <out-dir>] [-s <sheets>]
.claude/skills/claude-omr/scripts/claude-omr.sh finish <out-dir>/<name>.claude.json
```

The script runs in Bash (Git Bash on Windows) and accepts paths with spaces or commas.

### 1. Prepare the page material

```bash
.claude/skills/claude-omr/scripts/claude-omr.sh prepare D:/scores/minuet.pdf -o D:/scores/out
```

This creates the folder `D:/scores/out/minuet-claude/` with:

| File | Content |
| :--- | :--- |
| `page-N.png` | One image per page of the input file |
| `page-N-overview.png` | The page, annotated with system, staff (`S1`, `S2`…) and provisional measure numbers (`m1`, `m2`…) |
| `page-N-s<sys>-m<a>-<b>-st<i>-<j>.png` | Detail tiles: measures *a* to *b* of staves *i* to *j*, enlarged, with the pitch of every staff line (left margin, red) and space (right margin, blue) according to the detected clef, dotted guides at ledger-line positions and measure numbers on top |
| `page-N-strip-K.png` | Only for a page whose layout could not be detected: overlapping horizontal strips |
| `request.md` | The list of images, the detected layout (staves, clefs, keys, measures), the path where the description is expected (`D:/scores/out/minuet.claude.json`) and the full specification of the JSON format |
| `audiveris.log` | The full Audiveris log |

Low-resolution images (for example from the web) are handled: when the engine rejects the
interline as too small, the layout is detected on an upscaled copy.

Options: `-s "1 3-4"` limits the pages to prepare; without `-o`, the material is written next to
the input file.

The equivalent raw command is
`Audiveris -batch -claude [-sheets 1 3-4] -output D:/scores/out D:/scores/minuet.pdf`
(`-sheets` must be followed by another option; `-claude` cannot be combined with `-step`,
`-transcribe`, `-export` or `-print`).

### 2. Have Claude write the description

In Claude Code, ask Claude to follow `D:/scores/out/minuet-claude/request.md`.
Claude looks once at each overview to identify the parts, reads every detail tile once,
and writes `D:/scores/out/minuet.claude.json`.

### 3. Convert, check and engrave

```bash
.claude/skills/claude-omr/scripts/claude-omr.sh finish D:/scores/out/minuet.claude.json
```

This writes `D:/scores/out/minuet.mxl` and, if MuseScore is found, `minuet.pdf` and
`minuet-preview-1.png` (use `--no-pdf` or `--no-preview` to skip them).
The equivalent raw command is
`Audiveris -batch -output D:/scores/out D:/scores/out/minuet.claude.json`:
any input file whose name ends with `.claude.json` is converted.

The script prints the findings of Audiveris, located in the JSON file:

- **Errors** stop the conversion: invalid JSON, unknown note type, staff number out of range,
  etc. For example:
  `$.parts[0].measures[3].voices[0].events[2].type: unknown note type 'crotchet'`
- **Warnings** do not stop it, but usually reveal a recognition mistake. For example:
  `$.parts[0].measures[5]: measure content lasts 3/4 whole note(s), less than the measure capacity 1`
  or `...directions[0]: wedge is never stopped`.
  Consistency checks also report unknown keys (a misspelled field would otherwise be
  ignored), ties and slurs never stopped or stopped without a start, a staff left without
  any content in a measure, a text repeated in many consecutive measures or in every part
  (typically a copy-paste or generator-script mistake), and a part whose concert key differs
  from the other parts (misread key signature or missing transposition).
- A **Contents** line counts what the description holds (tempos, dynamics, wedges, graces,
  ties, slurs, bowings, ornaments, credits...): a 0 where the page shows such symbols means
  they were missed.

In `request.md`, a reading checklist lists the most frequent omissions, and lines starting
with `CHECK` flag likely layout detection errors, such as a staff whose detected key
signature differs from the other staves.

Re-check the indicated measures on their tiles, fix the JSON and run `finish` again,
until no error and no unexplained warning remains.
Then open the `.mxl` file in a score editor to proof-read it.

## Score description format

The authoritative specification is the one written in every `request.md` file
(it comes from `ClaudeRequest.FORMAT_SPEC` in the source code).
In short, a description is one JSON object:

```json
{
  "format": "audiveris-claude-omr/1",
  "title": "Minuet", "composer": "J. S. Bach",
  "parts": [
    {
      "id": "P1", "name": "Piano", "staves": 2,
      "measures": [
        {
          "number": "1",
          "key": { "fifths": 1 },
          "time": { "beats": 3, "beatType": 4 },
          "clefs": [ { "staff": 1, "sign": "G" }, { "staff": 2, "sign": "F" } ],
          "voices": [
            { "voice": 1, "staff": 1, "events": [
              { "type": "quarter", "pitches": [ { "step": "D", "octave": 5 } ],
                "directions": [ { "dynamics": "mf" } ] },
              { "type": "eighth", "pitches": [ { "step": "G", "octave": 4 } ], "beams": ["begin"] },
              { "type": "eighth", "pitches": [ { "step": "A", "octave": 4 } ], "beams": ["continue"] },
              { "type": "eighth", "pitches": [ { "step": "B", "octave": 4 } ], "beams": ["continue"] },
              { "type": "eighth", "pitches": [ { "step": "C", "octave": 5 } ], "beams": ["end"] }
            ] },
            { "voice": 5, "staff": 2, "events": [
              { "type": "half", "dots": 1,
                "pitches": [ { "step": "G", "octave": 3 }, { "step": "B", "octave": 3 } ] }
            ] }
          ]
        }
      ]
    }
  ]
}
```

Main rules:

- A piano grand staff is **one part** with `"staves": 2`. All parts have the same number of measures.
- `key`, `time` and `clefs` are given in the first measure, then only where they change.
- Each voice lists its `events` in time order. An event is a chord (several `pitches`),
  a note or a rest (`"rest": true`). A whole-measure rest is `{ "rest": true, "measureRest": true }`.
- `type` is the graphical value (`whole`, `half`, `quarter`, `eighth`, `16th`, …),
  with `dots` and `tuplet` (`{ "actual": 3, "normal": 2 }`) given separately.
  Audiveris computes the actual durations.
- `alter` is the sounding alteration (taking key signature and previous accidentals into account),
  whereas `accidental` only describes an accidental sign printed on the page.
- Measure layout hints: `"newSystem": true`, `"newPage": true`; pickup measure: `"implicit": true`.
- Transposing instruments: `"transpose"` on the part (written to sounding pitch), for example
  `{ "chromatic": -7 }` for a horn in F, or `{ "chromatic": 0, "octaveChange": -1 }` for a
  double bass. Pitches are always written as printed.
- Page layout: Audiveris writes a staff size such that a whole system fits on an A4 page; an
  optional root `"layout": { "staffHeight": 5, "pageWidth": 210, "pageHeight": 297 }`
  (millimeters) overrides it.

A complete example is provided in the repository: `data/examples/claude/zizi.claude.json`,
the transcription of `data/examples/zizi.png`.

## Supported and unsupported content

Supported:
parts and multi-staff parts, clefs (including octave clefs), key and time signatures
(including common and cut time), notes, chords, rests, measure rests, grace notes,
dots, ties, slurs, tuplets, beams, stems, accidentals, staccato, staccatissimo, accent, tenuto,
marcato, breath mark, caesura, down-bow and up-bow, ornaments (trill mark, mordents,
turns, shake), fermatas, dynamics, hairpins (wedges), text directions,
metronome marks, lyrics, barline styles, repeats, volta endings, system and page breaks,
transposing instruments, page layout, title, subtitle, opus, composer, lyricist, arranger,
rights and any other header or footer text (`credits`, with a position such as
`bottom-left`).

Not supported yet:
octave shifts, wavy trill lines, pedal marks, chord names, figured bass, tablatures,
drum notation instruments, and the precise graphical positions of symbols.

## Tips for better results and lower cost

Most of the Claude usage comes from the number of steps of the session (each one re-reads the
conversation) and from the images it looks at. To keep it low:

- **Try the standard engine first.** `Audiveris -batch -transcribe -export <input>` runs
  locally at no token cost. Keep the Claude mode for pages the standard engine gets wrong.
- **Read each tile once.** The tiles already carry the pitch names and measure numbers;
  avoid making extra crops or re-reading full pages.
- **Use the script.** It prints a few lines instead of full logs, which would otherwise be
  re-read at every later step.
- **Batch pages.** Prepare several pages (or a whole PDF) in one session, so that the fixed
  cost (instructions, skill, setup) is shared.
- **Pick the model for the page.** A clean, simple page can be done with a smaller model or a
  lower effort setting; keep the most capable model for dense or degraded scores.
- Prefer clean scans of at least 300 DPI; crooked or blurred pages degrade recognition.
- When a passage is ambiguous, ask Claude to list its doubts so that you can check them first.

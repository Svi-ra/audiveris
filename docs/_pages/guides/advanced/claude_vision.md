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
| **Audiveris** | Loads the input file (PNG, JPG, TIFF, PDF, multi-page…), renders each page as a PNG image and writes a `request.md` file that describes the expected result. |
| **Claude Code** | Looks at the page images with its own vision and writes a JSON score description (`<name>.claude.json`): parts, measures, clefs, keys, times, notes, rests, beams, ties, slurs, tuplets, articulations, dynamics, lyrics… |
| **Audiveris** | Reads the JSON description, computes divisions, durations and voice backups, checks that every measure is rhythmically complete, then writes MusicXML (`<name>.mxl`) with its regular ProxyMusic export. |

Key points:

- **No API key.** Audiveris never calls the Anthropic API and never connects to the network.
  Recognition is done by the Claude Code session you are already working in,
  which reads the image files from your disk.
- **The standard Audiveris engine is untouched.** Without the `-claude` option and without a
  `.claude.json` input, Audiveris behaves exactly as before.
- The two engines are independent: the Claude mode does not create any `.omr` book,
  so the Audiveris editor cannot be used to correct its result.
  Make corrections in the JSON description (or ask Claude to make them), then convert again.

## Requirements

- A **build of this fork** of Audiveris (it contains the `-claude` option),
  which requires **JDK 25**. See [Sources](../../tutorials/install/sources.md).
- **Claude Code** (CLI, desktop app or IDE extension) opened on the Audiveris repository folder,
  so that the `claude-omr` skill (`.claude/skills/claude-omr/SKILL.md`) is available.

In the commands below, Audiveris is started from the sources with Gradle:

```bash
./gradlew :app:run -PcmdLineArgs="-batch,<arg1>,<arg2>,..."
```

{: .important }
`cmdLineArgs` is a comma-separated list, so a path cannot contain a comma.
Gradle runs Audiveris from the `app` folder, so **always use absolute paths**.

If you use an installed build of this fork, replace this with `audiveris -batch <arg1> <arg2> ...`.

## Quick start: let Claude do everything

Open Claude Code in the Audiveris repository and ask, for example:

> Transcribe `D:/scores/minuet.pdf` to MusicXML with Claude vision,
> output in `D:/scores/out`.

Claude then uses the `claude-omr` skill to run the steps below:
it prepares the images, reads them, writes the description, converts it,
and fixes any reported problem.
At the end it reports the path to the `.mxl` file, the remaining warnings
and the passages it was unsure about.

The sections below describe each step, if you want to run them yourself.

## Step by step

### 1. Prepare the page images

```bash
./gradlew :app:run -PcmdLineArgs="-batch,-claude,-output,D:/scores/out,D:/scores/minuet.pdf"
```

This creates the folder `D:/scores/out/minuet-claude/` with:

| File | Content |
| :--- | :--- |
| `page-1.png`, `page-2.png`, … | One image per page of the input file |
| `page-N-strip-K.png` | For pages taller than 1600 pixels: overlapping horizontal strips at full resolution, so that small symbols (accidentals, dots, ledger lines) stay readable |
| `request.md` | The list of images, the path where the description is expected (`D:/scores/out/minuet.claude.json`) and the full specification of the JSON format |

Options:

- `-sheets 1 3-4` limits the pages to prepare. It must be followed by another option, not
  directly by the input file, for example:
  `-PcmdLineArgs="-batch,-claude,-sheets,1,3-4,-output,D:/scores/out,D:/scores/minuet.pdf"`.
- Without `-output`, the material is written next to the input file.
- `-claude` cannot be combined with `-step`, `-transcribe`, `-export` or `-print`.

### 2. Have Claude write the description

In Claude Code, ask Claude to follow `D:/scores/out/minuet-claude/request.md`.
Claude reads each full page to understand the layout (parts, systems, measures),
then the strips to read the details, and writes `D:/scores/out/minuet.claude.json`.

### 3. Convert the description to MusicXML

```bash
./gradlew :app:run -PcmdLineArgs="-batch,-output,D:/scores/out,D:/scores/out/minuet.claude.json"
```

Any input file whose name ends with `.claude.json` is converted (no `-claude` option is needed).
The result is the compressed MusicXML file `D:/scores/out/minuet.mxl`.
Without `-output`, it is written next to the `.claude.json` file.

### 4. Check and fix

Audiveris logs its findings with a `Claude OMR:` prefix and the location in the JSON file:

- **Errors** stop the conversion: invalid JSON, unknown note type, staff number out of range,
  etc. For example:
  `$.parts[0].measures[3].voices[0].events[2].type: unknown note type 'crotchet'`
- **Warnings** do not stop it, but usually reveal a recognition mistake. For example:
  `$.parts[0].measures[5]: measure content lasts 3/4 whole note(s), less than the measure capacity 1`

Re-check the indicated measures on the images, fix the JSON and convert again,
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

A complete example is provided in the repository: `data/examples/claude/zizi.claude.json`,
the transcription of `data/examples/zizi.png`.

## Supported and unsupported content

Supported:
parts and multi-staff parts, clefs (including octave clefs), key and time signatures
(including common and cut time), notes, chords, rests, measure rests, grace notes,
dots, ties, slurs, tuplets, beams, stems, accidentals, staccato, staccatissimo, accent, tenuto,
marcato, breath mark, caesura, fermatas, dynamics, hairpins (wedges), text directions,
metronome marks, lyrics, barline styles, repeats, volta endings, system and page breaks,
title, composer, lyricist, arranger and rights.

Not supported yet:
ornaments, octave shifts, pedal marks, chord names, figured bass, tablatures,
drum notation instruments, and the precise graphical positions of symbols.

## Tips for better results

- Prefer clean scans of at least 300 DPI; crooked or blurred pages degrade recognition.
- For long scores, ask Claude to work one system (or one page) at a time and
  to check measure counts across parts.
- When a passage is ambiguous, ask Claude to list its doubts so that you can check them first.
- To compare with the standard engine, run the regular Audiveris transcription
  on the same input (`-batch -transcribe -export`) and compare both MusicXML files.

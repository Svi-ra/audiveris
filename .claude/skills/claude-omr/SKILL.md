---
name: claude-omr
description: Experimental Audiveris OMR mode using Claude Code's own vision. Transcribe a music score image or PDF into MusicXML by having Claude read the page images, write an audiveris-claude-omr JSON description, and letting Audiveris generate the MusicXML. Use when the user asks to recognize / transcribe / OMR a score "with Claude" or "with Claude vision".
---

# Claude vision OMR for Audiveris (experimental)

This mode splits the work in two:

- **You (Claude, in this session)** do the visual and semantic recognition only: you look at
  the page images with your own vision and write a JSON score description.
- **Audiveris** prepares the page images, then converts your JSON description into MusicXML
  (divisions, durations, backups, measure checks, ProxyMusic marshalling).

Never call the Anthropic API, never ask for or use an API key, never install an SDK.
Everything happens in this session with your built-in image reading (the Read tool on PNG/JPG).
The regular Audiveris OMR (`-batch -transcribe ...`) is untouched; do not use it in this mode
unless the user asks for a comparison.

## How to run Audiveris

From the repository root (requires JDK 25):

```bash
./gradlew :app:run -PcmdLineArgs="-batch,<arg1>,<arg2>,..."
```

`cmdLineArgs` is split on commas, so paths containing commas are not supported this way.
Gradle runs the application from the `app/` folder: **always pass absolute paths**.
A complete sample description is in `data/examples/claude/zizi.claude.json`
(transcription of `data/examples/zizi.png`).
If an installed Audiveris is available, `audiveris -batch <args...>` works the same.

## Workflow

1. **Prepare** the input (any format Audiveris reads: PNG, JPG, TIFF, PDF, multi-page):

   ```bash
   ./gradlew :app:run -PcmdLineArgs="-batch,-claude,-output,<out-dir>,<input-file>"
   ```

   This writes `<out-dir>/<radix>-claude/` containing `page-N.png`, optional
   `page-N-strip-K.png` detail strips for tall pages, and `request.md`.
   Add `-sheets,1,3-4` to limit pages. For a single PNG/JPG you may skip this step and read the
   image directly, but you then must take the format from
   `app/src/main/java/org/audiveris/omr/claude/ClaudeRequest.java` (`FORMAT_SPEC`).

2. **Read `request.md`** in that folder. It lists the images and contains the authoritative
   JSON format specification. Follow it exactly.

3. **Look at the images** with the Read tool: first each full page (layout: parts, staves per
   part, systems, measure count), then the detail strips to read pitches, accidentals, dots,
   beams, ties, articulations and lyrics.

   Recommended reading discipline:
   - Determine clefs, key signature and time signature first; they drive every pitch.
   - Go system by system, measure by measure, staff by staff; count the measures of each system
     and make sure every part has the same number of measures.
   - For each measure, check that the durations of every voice add up to the time signature
     (except a pickup, marked `"implicit": true`).
   - `alter` is the sounding alteration (key signature + accidentals carried through the
     measure + ties); `accidental` only describes a printed sign.

4. **Write** the description to the path given in `request.md`
   (`<out-dir>/<radix>.claude.json`). For long scores, build it incrementally (one system at a
   time) to stay accurate.

5. **Convert** with Audiveris:

   ```bash
   ./gradlew :app:run -PcmdLineArgs="-batch,-output,<out-dir>,<out-dir>/<radix>.claude.json"
   ```

   This writes `<out-dir>/<radix>.mxl` (compressed MusicXML).

6. **Fix and iterate.** Audiveris logs `Claude OMR:` warnings with a JSON path such as
   `$.parts[0].measures[3].voices[0]` (measure too long / too short) and fails with a precise
   message on invalid values. Re-check those measures in the images, edit the JSON, and convert
   again until there are no errors and no unexplained warnings.

7. **Report** to the user: output file path, number of parts/measures, remaining warnings and
   any passages you were unsure about. Remind them this mode is experimental and the result
   should be proof-read (for example by opening the `.mxl` in MuseScore).

//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                   C l a u d e R e q u e s t                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.claude;

import java.nio.file.Path;
import java.util.List;

/**
 * Class <code>ClaudeRequest</code> writes the <code>request.md</code> file that tells Claude
 * Code which page images to look at and which score description format to produce.
 * <p>
 * The format specification below is the single reference for {@link ClaudeScoreBuilder}.
 *
 * @author Audiveris contributors
 */
public abstract class ClaudeRequest
{
    //~ Static fields/initializers -----------------------------------------------------------------

    /** Specification of the score description format. */
    public static final String FORMAT_SPEC = """
            ## Score description format (`audiveris-claude-omr/1`)

            Write **one JSON object** (UTF-8, no comments, no trailing commas).
            Only describe what is visible: Audiveris computes divisions, durations, backups and
            checks measure capacities; it then generates MusicXML.

            ```json
            {
              "format": "audiveris-claude-omr/1",
              "title": "optional title", "subtitle": "optional", "opus": "optional",
              "composer": "optional", "lyricist": "optional", "arranger": "optional",
              "rights": "optional", "source": "optional input file name",
              "credits": [ { "text": "Edition XYZ", "position": "bottom-left" } ],
              "parts": [
                {
                  "id": "P1", "name": "Piano", "abbreviation": "Pno.",
                  "staves": 2,
                  "transpose": { "chromatic": -2, "diatonic": -1, "octaveChange": 0 },
                  "measures": [
                    {
                      "number": "1",
                      "implicit": false,
                      "newSystem": false, "newPage": false,
                      "key":   { "fifths": -1, "mode": "major" },
                      "time":  { "beats": 3, "beatType": 4, "symbol": "common" },
                      "clefs": [ { "staff": 1, "sign": "G", "line": 2 },
                                 { "staff": 2, "sign": "F", "line": 4 } ],
                      "leftBarline":  { "style": "heavy-light", "repeat": "forward" },
                      "rightBarline": { "style": "light-heavy", "repeat": "backward",
                                        "ending": { "number": "1", "type": "stop" } },
                      "voices": [
                        {
                          "voice": 1, "staff": 1,
                          "events": [
                            { "type": "quarter", "dots": 1,
                              "pitches": [ { "step": "F", "octave": 4, "alter": 1,
                                             "accidental": "sharp", "tie": "start" },
                                           { "step": "A", "octave": 4 } ],
                              "stem": "up", "beams": [], "slurs": ["start"],
                              "articulations": ["staccato"], "bowings": ["down-bow"],
                              "ornaments": ["trill-mark"], "fermata": false,
                              "directions": [ { "dynamics": "mf" } ],
                              "lyrics": [ { "number": 1, "text": "la", "syllabic": "single" } ] },
                            { "type": "eighth", "rest": true },
                            { "type": "eighth", "pitches": [ { "step": "C", "octave": 5 } ],
                              "tuplet": { "actual": 3, "normal": 2, "type": "start" },
                              "beams": ["begin"] }
                          ]
                        },
                        { "voice": 5, "staff": 2,
                          "events": [ { "rest": true, "measureRest": true } ] }
                      ]
                    }
                  ]
                }
              ]
            }
            ```

            ### Rules

            - **header / footer**: `title`, `subtitle`, `opus`, `composer`, `lyricist`,
              `arranger`, `rights` (copyright line) are printed at their usual place. Every other
              text printed in the page header or footer (publisher, plate number, dedication,
              translated title...) goes into `credits`, with `position` top-left, top-center,
              top-right, bottom-left, bottom-center or bottom-right (and optional `fontSize`
              in points). A header field also listed in `credits` is printed only there.
            - **parts**: one entry per instrument, top to bottom. A piano grand staff is ONE part
              with `"staves": 2`. Every part must have the same number of measures.
            - **transpose** (part, or measure for an instrument change): only for transposing
              instruments, from written to sounding pitch. `chromatic`: semitones (negative when
              sounding lower), `diatonic`: steps (computed from `chromatic` if omitted),
              `octaveChange`. Examples: B-flat clarinet -2; horn in F -7; horn in E -8;
              alto sax -9; double bass and guitar `{ "chromatic": 0, "octaveChange": -1 }`.
              Pitches are always written as printed.
            - **layout** (optional, root level): `{ "staffHeight": 5.0, "pageWidth": 210,
              "pageHeight": 297 }` in millimeters. Omit it: the staff size is computed so that a
              whole system fits on an A4 page.
            - **measures**: in reading order across all systems and pages. Set `"newSystem": true`
              on the first measure of each new system and `"newPage": true` on the first measure of
              each new page (not on measure 1). Use `"implicit": true` for a pickup (anacrusis).
            - **key** / **time** / **clefs**: give them in the first measure, then only where they
              change. `fifths`: number of sharps (positive) or flats (negative). Clef `sign`:
              G, F, C, percussion or TAB; `line` defaults to 2 for G, 4 for F, 3 otherwise;
              `octaveChange`: -1 for a treble-8vb clef. `symbol`: "common" or "cut" if drawn so.
            - **voices**: list each voice of the measure separately; each voice's `events` are in
              time order and should fill the measure. Typical numbering: voices 1-4 on staff 1,
              voices 5-8 on staff 2. An event may override its voice staff with `"staff"`
              (cross-staff notes).
            - **events**: a chord (several `pitches`), a single note, or a rest (`"rest": true`).
              - `type`: long, breve, whole, half, quarter, eighth, 16th, 32nd, 64th, 128th, 256th
                (graphic value, without dots or tuplet). `dots`: number of augmentation dots.
              - Whole-measure rest: `{ "rest": true, "measureRest": true }` (no type).
              - Grace note: `"grace": true` (with `"graceSlash": true` for an acciaccatura) and
                a `type`; it takes no time.
              - `tuplet`: `actual` notes in the time of `normal` notes (triplet: 3 / 2) on every
                note of the tuplet; add `"type": "start"` on the first and `"type": "stop"` on the
                last one.
              - `beams`: one entry per beam level: begin, continue, end, forward hook,
                backward hook.
              - `slurs`: list of "start" / "stop" / "continue" (or objects `{type, number}` for
                overlapping slurs).
              - `stem`: up or down. `articulations`: staccato, staccatissimo, accent, tenuto,
                marcato, breath-mark, caesura. `bowings`: down-bow, up-bow. `ornaments`:
                trill-mark, mordent, inverted-mordent, turn, inverted-turn, delayed-turn, shake.
                `fermata`: true.
              - `directions` (printed just before the event): one of `dynamics` (p, pp, ppp, mp,
                mf, f, ff, fff, fp, fz, sf, sfz), `words` (text such as "dolce", "rit."),
                `wedge` (crescendo, diminuendo, stop; every wedge needs a stop on the same
                staff) or `tempo`
                (`{ "beatUnit": "quarter", "dots": 0, "perMinute": 96 }`);
                optional `placement` (above / below) and `staff`.
              - `lyrics`: `number` (verse), `text`, `syllabic` (single, begin, middle, end).
            - **pitches**: `step` (A-G), `octave` (middle C is C4), `alter` = sounding alteration
              (-2..2) **taking the key signature, earlier accidentals in the measure and ties into
              account**; `accidental` only when an accidental sign is printed (sharp, flat,
              natural, double-sharp, double-flat). `tie`: start, stop or continue.
            - **barlines**: `style` regular, dotted, dashed, heavy, light-light, light-heavy,
              heavy-light, heavy-heavy, none; `repeat` forward (left) / backward (right);
              `ending` `{ "number": "1", "type": "start" | "stop" | "discontinue",
              "text": "1." }`. Omit plain single barlines.
            - Unknown keys are reported as warnings and ignored: use only the keys above.
            - Omit any optional field you do not need. Never invent content you cannot see; if a
              symbol is unreadable, choose the most plausible value that keeps the measure
              rhythmically complete.
            """;

    /** What a transcription most often leaves out: to be checked on every page. */
    public static final String CHECKLIST = """
            ## Reading checklist (most frequent omissions)

            Before writing the JSON, check each item on every page:

            - **Header and footer**: title, subtitle, opus, composer, and every other printed
              text (publisher, plate number, copyright) go to header fields or `credits`.
            - **Tempo**: tempo words *and* metronome mark (e.g. dotted quarter = 60 is
              `tempo` with `"dots": 1`); tempo words repeated above a lower section (strings)
              belong to the top part of that section too.
            - **Hairpins**: look below *and* above every staff, also right after a dynamic
              (`p >`); each one is a `wedge` start plus a `stop` where it ends.
            - **Shared staves** (two players on one staff, `a 2`, `1.`, divisi): a second rest
              drawn below or above the first one, or stems in both directions, means a second
              voice; write it, even as a measure rest.
            - **Grace notes**: count their flags or beams (eighth, 16th...), note the slash,
              and their slur to the main note.
            - **Ties and slurs at the right edge** of the last measure of a page or excerpt:
              start them (`"tie": "start"`), they continue on the next page.
            - **Bowings and ornaments**: down-bow, up-bow, trills, mordents, turns.
            - **Key signatures**: count sharps and flats on every staff, especially C clefs where
              they are placed differently; non-transposing instruments share one concert key.

            After the conversion, compare the `Contents` counts printed by the script (tempos,
            wedges, graces, bowings, credits...) with what the page shows: a 0 where the page has
            such symbols means they were missed.

            """;

    //~ Constructors -------------------------------------------------------------------------------

    private ClaudeRequest ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    /**
     * Build the content of the request file.
     *
     * @param input    the original input file
     * @param folder   the folder where page images were written
     * @param pages    the description lines of written page images
     * @param jsonPath the path where the description is expected
     * @return the markdown content
     */
    public static String build (Path input,
                                Path folder,
                                List<String> pages,
                                Path jsonPath)
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("# Audiveris – Claude vision OMR request (experimental)\n\n");
        sb.append("Input file: `").append(input).append("`\n\n");
        sb.append("## Task\n\n");
        sb.append("Images are in folder `").append(folder).append("`.\n\n");
        sb.append("1. Look once at each page overview (or page image) to identify the parts:\n");
        sb.append("   instrument names, which staves belong to which part.\n");
        sb.append("2. Read the music from the **detail tiles**, each one only once. In a tile,\n");
        sb.append("   staff lines are labelled with their pitch in the left margin (red) and\n");
        sb.append("   spaces in the right margin (blue), for the detected clef; dotted guides\n");
        sb.append("   mark ledger-line positions; green `mN` marks measure starts; `Sk` (right\n");
        sb.append("   margin, at middle line) is the staff number.\n");
        sb.append("   Labels ignore the key signature and accidentals, and follow the clef at\n");
        sb.append("   the start of the system: adapt them after a clef change. Measure numbers\n");
        sb.append("   are provisional (a missed or extra barline shifts them): check them.\n");
        sb.append("3. Transcribe the music into the JSON format specified below and write it\n");
        sb.append("   to `").append(jsonPath).append("`.\n");
        sb.append("4. Run Audiveris on that file to obtain MusicXML; fix the description if\n");
        sb.append("   Audiveris reports errors or warnings. Re-open only the tiles involved.\n");
        sb.append("   Lines starting with CHECK in the layout below point at likely detection\n");
        sb.append("   errors: verify them on the tiles.\n\n");
        sb.append("## Page images\n\n");

        for (String line : pages) {
            sb.append(line).append('\n');
        }

        sb.append('\n').append(CHECKLIST).append(FORMAT_SPEC);

        return sb.toString();
    }
}

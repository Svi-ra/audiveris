//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                    C l a u d e C h e c k s                                     //
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Class <code>ClaudeChecks</code> runs consistency checks on a parsed score description,
 * beyond what is needed to generate MusicXML.
 * <p>
 * A vision transcription typically fails by <b>omission</b> (a symbol not transcribed) or by
 * <b>duplication</b> (content copied to the wrong places, for instance by a generator script
 * that shares one object between several measures). These checks catch the patterns that can be
 * detected from the description alone:
 * <ul>
 * <li>unknown keys (a misspelled or unsupported field is otherwise silently ignored),
 * <li>ties and slurs started but never stopped, or stopped without a start,
 * <li>staves of a part with no content at all in a measure,
 * <li>the same text direction repeated in many consecutive measures or in every part,
 * <li>a part whose concert key differs from the other parts (misread key signature or missing
 * transposition),
 * </ul>
 * It also produces a short content summary (counts of dynamics, wedges, slurs, ties, graces...)
 * so that a missing category of symbols is easy to spot.
 *
 * @author Audiveris contributors
 */
public class ClaudeChecks
{
    //~ Static fields/initializers -----------------------------------------------------------------

    /** Allowed keys per kind of object of the description format. */
    private static final Map<String, Set<String>> KEYS = new HashMap<>();

    static {
        keys("root", "format", "title", "subtitle", "opus", "composer", "lyricist", "arranger",
                "rights", "source", "credits", "layout", "parts");
        keys("layout", "staffHeight", "pageWidth", "pageHeight");
        keys("credit", "text", "position", "fontSize");
        keys("part", "id", "name", "abbreviation", "staves", "transpose", "measures");
        keys("transpose", "chromatic", "diatonic", "octaveChange");
        keys("measure", "number", "implicit", "newSystem", "newPage", "key", "time", "clefs",
                "transpose", "leftBarline", "rightBarline", "voices");
        keys("key", "fifths", "mode");
        keys("time", "beats", "beatType", "symbol");
        keys("clef", "staff", "sign", "line", "octaveChange");
        keys("barline", "style", "repeat", "ending");
        keys("ending", "number", "type", "text");
        keys("voice", "voice", "staff", "events");
        keys("event", "type", "dots", "rest", "measureRest", "grace", "graceSlash", "pitches",
                "staff", "stem", "beams", "slurs", "tuplet", "articulations", "bowings",
                "ornaments", "fermata", "directions", "lyrics");
        keys("pitch", "step", "octave", "alter", "accidental", "tie");
        keys("tuplet", "actual", "normal", "type", "number", "bracket");
        keys("slur", "type", "number");
        keys("direction", "dynamics", "words", "wedge", "tempo", "placement", "staff");
        keys("tempo", "beatUnit", "dots", "perMinute");
        keys("lyric", "number", "text", "syllabic");
    }

    /** Minimum run of consecutive measures with the same text to suspect a duplication. */
    private static final int MAX_TEXT_RUN = 3;

    /** Minimum number of parts to suspect a text repeated in every part. */
    private static final int MIN_PARTS_FOR_TEXT_CHECK = 3;

    //~ Instance fields ----------------------------------------------------------------------------

    private final List<String> warnings = new ArrayList<>();

    /** Content counters, by category, in insertion order. */
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    //~ Methods ------------------------------------------------------------------------------------

    /**
     * Run all checks on the parsed description.
     *
     * @param json the parsed JSON root
     * @return this instance, for chaining
     */
    public ClaudeChecks run (Object json)
    {
        if (!(json instanceof Map<?, ?> root)) {
            return this;
        }

        checkKeys(root, "root", "$");

        final List<Map<?, ?>> parts = maps(root.get("parts"));

        for (String k : new String[] { "parts", "measures", "notes", "rests", "graces", "ties",
                "slurs", "dynamics", "wedges", "words", "tempos", "articulations", "bowings",
                "ornaments", "fermatas", "lyrics", "credits" }) {
            counts.put(k, 0);
        }

        counts.put("parts", parts.size());
        counts.put("credits", maps(root.get("credits")).size());

        for (int p = 0; p < parts.size(); p++) {
            checkPart(parts.get(p), "$.parts[" + p + "]");
        }

        if (!parts.isEmpty()) {
            counts.put("measures", maps(parts.get(0).get("measures")).size());
        }

        checkTextsInAllParts(parts);
        checkConcertKeys(parts);

        return this;
    }

    /**
     * @return the warnings found by last run
     */
    public List<String> getWarnings ()
    {
        return Collections.unmodifiableList(warnings);
    }

    /**
     * @return a one-line summary of the description contents
     */
    public String getSummary ()
    {
        final StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }

            sb.append(e.getKey()).append(' ').append(e.getValue());
        }

        return sb.toString();
    }

    //-----------//
    // checkPart //
    //-----------//
    private void checkPart (Map<?, ?> part,
                            String path)
    {
        checkKeys(part, "part", path);
        checkKeys(map(part.get("transpose")), "transpose", path + ".transpose");

        final int staves = integer(part.get("staves"), 1);
        final List<Map<?, ?>> measures = maps(part.get("measures"));
        final int last = measures.size() - 1;

        // Open ties per "staff|pitch", open slurs per "voice|number": path and measure index
        final Map<String, Object[]> ties = new LinkedHashMap<>();
        final Map<String, Object[]> slurs = new LinkedHashMap<>();

        // Current run of identical texts in consecutive measures
        Set<String> runTexts = new LinkedHashSet<>();
        final Map<String, Integer> runLengths = new HashMap<>();
        final Set<String> reported = new LinkedHashSet<>();

        for (int m = 0; m < measures.size(); m++) {
            final Map<?, ?> measure = measures.get(m);
            final String mPath = path + ".measures[" + m + "]";
            checkMeasureKeys(measure, mPath);

            final Set<Integer> covered = new TreeSet<>();
            final Set<String> texts = new LinkedHashSet<>();
            final List<Map<?, ?>> voices = maps(measure.get("voices"));

            for (int v = 0; v < voices.size(); v++) {
                final Map<?, ?> voice = voices.get(v);
                final String vPath = mPath + ".voices[" + v + "]";
                checkKeys(voice, "voice", vPath);

                final int voiceStaff = integer(voice.get("staff"), 1);
                final String voiceId = String.valueOf(integer(voice.get("voice"), v + 1));
                final List<Map<?, ?>> events = maps(voice.get("events"));

                if (!events.isEmpty()) {
                    covered.add(voiceStaff);
                }

                for (int e = 0; e < events.size(); e++) {
                    final Map<?, ?> event = events.get(e);
                    final String ePath = vPath + ".events[" + e + "]";
                    final int staff = integer(event.get("staff"), voiceStaff);
                    covered.add(staff);
                    checkEvent(event, ePath, staff, voiceId, m, ties, slurs, texts);
                }
            }

            for (int s = 1; s <= staves; s++) {
                if (!covered.contains(s)) {
                    warn(mPath, "staff " + s + " has no content: add its notes, or a measure"
                            + " rest (also for a resting player sharing a staff)");
                }
            }

            // Same text in many consecutive measures: probably duplicated by mistake
            for (String text : texts) {
                final int length = runTexts.contains(text) ? runLengths.get(text) + 1 : 1;
                runLengths.put(text, length);

                if ((length >= MAX_TEXT_RUN) && reported.add(text)) {
                    warn(mPath, "text " + text + " is repeated in " + length
                            + " consecutive measures: write it only where it is printed"
                            + " (shared object in a generator script?)");
                }
            }

            runTexts = texts;
        }

        // Spanners still open at the end of the part: legitimate only when started in the last
        // measure (they continue beyond the transcribed excerpt)
        for (Object[] open : ties.values()) {
            if ((int) open[1] < last) {
                warn((String) open[0], "tie is never stopped: the next note of the same pitch"
                        + " needs \"tie\": \"stop\"");
            }
        }

        for (Object[] open : slurs.values()) {
            if ((int) open[1] < last) {
                warn((String) open[0], "slur is never stopped");
            }
        }
    }

    //------------//
    // checkEvent //
    //------------//
    private void checkEvent (Map<?, ?> event,
                             String path,
                             int staff,
                             String voiceId,
                             int measureIndex,
                             Map<String, Object[]> ties,
                             Map<String, Object[]> slurs,
                             Set<String> texts)
    {
        checkKeys(event, "event", path);
        checkKeys(map(event.get("tuplet")), "tuplet", path + ".tuplet");

        final boolean rest = Boolean.TRUE.equals(event.get("rest"));

        if (rest) {
            count("rests", 1);
        } else if (Boolean.TRUE.equals(event.get("grace"))) {
            count("graces", 1);
        } else {
            count("notes", 1);
        }

        count("articulations", list(event.get("articulations")).size());
        count("bowings", list(event.get("bowings")).size());
        count("ornaments", list(event.get("ornaments")).size());
        count("fermatas", Boolean.TRUE.equals(event.get("fermata")) ? 1 : 0);
        count("lyrics", list(event.get("lyrics")).size());

        final List<Object> lyrics = list(event.get("lyrics"));

        for (int i = 0; i < lyrics.size(); i++) {
            checkKeys(map(lyrics.get(i)), "lyric", path + ".lyrics[" + i + "]");
        }

        // Directions
        final List<Object> directions = list(event.get("directions"));

        for (int i = 0; i < directions.size(); i++) {
            final Map<?, ?> d = map(directions.get(i));
            final String dPath = path + ".directions[" + i + "]";
            checkKeys(d, "direction", dPath);
            checkKeys(map(d.get("tempo")), "tempo", dPath + ".tempo");

            if (d.get("dynamics") != null) {
                count("dynamics", 1);
            } else if (d.get("words") != null) {
                count("words", 1);
                texts.add("'" + d.get("words") + "'");
            } else if (d.get("wedge") != null) {
                if (!"stop".equals(d.get("wedge"))) {
                    count("wedges", 1);
                }
            } else if (d.get("tempo") != null) {
                count("tempos", 1);
                texts.add("tempo " + map(d.get("tempo")).values());
            }
        }

        // Slurs, keyed by voice and explicit number
        final List<Object> slurList = list(event.get("slurs"));

        for (int i = 0; i < slurList.size(); i++) {
            final Object slur = slurList.get(i);
            final String sPath = path + ".slurs[" + i + "]";
            final String type;
            Object number = null;

            if (slur instanceof Map<?, ?> sm) {
                checkKeys(sm, "slur", sPath);
                type = String.valueOf(sm.get("type"));
                number = sm.get("number");
            } else {
                type = String.valueOf(slur);
            }

            final String key = voiceId + "|" + ((number != null) ? number : "");

            switch (type) {
            case "start" -> {
                count("slurs", 1);
                slurs.put(key, new Object[] { sPath, measureIndex });
            }
            case "stop" -> {
                if ((slurs.remove(key) == null) && (measureIndex > 0)) {
                    warn(sPath, "slur stop without a start in voice " + voiceId);
                }
            }
            default -> {
            }
            }
        }

        if (rest) {
            return;
        }

        // Ties, keyed by staff and pitch
        final List<Object> pitches = list(event.get("pitches"));

        for (int i = 0; i < pitches.size(); i++) {
            final Map<?, ?> pitch = map(pitches.get(i));
            final String pPath = path + ".pitches[" + i + "]";
            checkKeys(pitch, "pitch", pPath);

            final Object tie = pitch.get("tie");

            if (tie == null) {
                continue;
            }

            final String key = staff + "|" + pitch.get("step") + pitch.get("octave") + "|"
                    + integer(pitch.get("alter"), 0);
            final boolean stops = "stop".equals(tie) || "continue".equals(tie)
                    || "stop-start".equals(tie);
            final boolean starts = "start".equals(tie) || "continue".equals(tie)
                    || "stop-start".equals(tie);

            if (stops && (ties.remove(key) == null) && (measureIndex > 0)) {
                warn(pPath, "tie stop without a tie start on the previous note of same pitch");
            }

            if (starts) {
                count("ties", 1);
                final Object[] previous = ties.put(key, new Object[] { pPath, measureIndex });

                if (previous != null) {
                    warn((String) previous[0], "tie is never stopped before the next tie start"
                            + " on the same pitch");
                }
            }
        }
    }

    //------------------//
    // checkMeasureKeys //
    //------------------//
    private void checkMeasureKeys (Map<?, ?> measure,
                                   String path)
    {
        checkKeys(measure, "measure", path);
        checkKeys(map(measure.get("key")), "key", path + ".key");
        checkKeys(map(measure.get("time")), "time", path + ".time");
        checkKeys(map(measure.get("transpose")), "transpose", path + ".transpose");

        final List<Object> clefs = list(measure.get("clefs"));

        for (int i = 0; i < clefs.size(); i++) {
            checkKeys(map(clefs.get(i)), "clef", path + ".clefs[" + i + "]");
        }

        for (String side : new String[] { "leftBarline", "rightBarline" }) {
            final Map<?, ?> barline = map(measure.get(side));
            checkKeys(barline, "barline", path + "." + side);
            checkKeys(map(barline.get("ending")), "ending", path + "." + side + ".ending");
        }
    }

    //----------------------//
    // checkTextsInAllParts //
    //----------------------//
    /**
     * A text found at the same measure in every part (of a large enough score) was most likely
     * copied there by mistake: score-wide texts are printed once or once per section.
     */
    private void checkTextsInAllParts (List<Map<?, ?>> parts)
    {
        if (parts.size() < MIN_PARTS_FOR_TEXT_CHECK) {
            return;
        }

        final int measureCount = maps(parts.get(0).get("measures")).size();

        for (int m = 0; m < measureCount; m++) {
            Set<Object> common = null;

            for (Map<?, ?> part : parts) {
                final List<Map<?, ?>> measures = maps(part.get("measures"));
                final Set<Object> words = new LinkedHashSet<>();

                if (m < measures.size()) {
                    for (Map<?, ?> voice : maps(measures.get(m).get("voices"))) {
                        for (Map<?, ?> event : maps(voice.get("events"))) {
                            for (Map<?, ?> d : maps(event.get("directions"))) {
                                if (d.get("words") != null) {
                                    words.add(d.get("words"));
                                }
                            }
                        }
                    }
                }

                if (common == null) {
                    common = words;
                } else {
                    common.retainAll(words);
                }
            }

            for (Object text : common) {
                warn("$.parts[*].measures[" + m + "]", "text '" + text + "' is repeated in all "
                        + parts.size() + " parts: keep it only on the staves where it is"
                        + " printed");
            }
        }
    }

    //------------------//
    // checkConcertKeys //
    //------------------//
    /**
     * Compare the concert key of parts (written key corrected by transposition).
     * Parts written without key signature (horns, timpani...) are not checked.
     */
    private void checkConcertKeys (List<Map<?, ?>> parts)
    {
        final Map<Integer, Integer> counts = new HashMap<>();
        final Map<Integer, Integer> concert = new LinkedHashMap<>();

        for (int p = 0; p < parts.size(); p++) {
            final Map<?, ?> part = parts.get(p);
            final List<Map<?, ?>> measures = maps(part.get("measures"));

            if (measures.isEmpty()) {
                continue;
            }

            final Map<?, ?> key = map(measures.get(0).get("key"));
            final int fifths = integer(key.get("fifths"), 0);

            if (fifths == 0) {
                continue;
            }

            final int chromatic = integer(map(part.get("transpose")).get("chromatic"), 0);
            // Transposing by n semitones moves the key by 7n fifths (modulo 12)
            final int value = Math.floorMod(fifths + (7 * chromatic), 12);
            concert.put(p, value);
            counts.merge(value, 1, Integer::sum);
        }

        if (counts.size() < 2) {
            return;
        }

        final int major = Collections.max(counts.entrySet(), Map.Entry.comparingByValue())
                .getKey();

        for (Map.Entry<Integer, Integer> e : concert.entrySet()) {
            if (e.getValue() != major) {
                warn("$.parts[" + e.getKey() + "].measures[0].key", "concert key differs from"
                        + " most parts: check the key signature (count sharps/flats) or the"
                        + " part transposition");
            }
        }
    }

    //-----------//
    // checkKeys //
    //-----------//
    private void checkKeys (Map<?, ?> object,
                            String kind,
                            String path)
    {
        final Set<String> allowed = KEYS.get(kind);

        for (Object key : object.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                warn(path, "unknown key '" + key + "' ignored (supported: " + allowed + ")");
            }
        }

        if (kind.equals("root")) {
            final List<Object> credits = list(object.get("credits"));

            for (int i = 0; i < credits.size(); i++) {
                checkKeys(map(credits.get(i)), "credit", path + ".credits[" + i + "]");
            }

            checkKeys(map(object.get("layout")), "layout", path + ".layout");
        }
    }

    private void count (String key,
                        int delta)
    {
        counts.merge(key, delta, Integer::sum);
    }

    private void warn (String path,
                       String message)
    {
        warnings.add(path + ": " + message);
    }

    //~ Static Methods -----------------------------------------------------------------------------

    private static int integer (Object value,
                                int def)
    {
        if (value instanceof BigDecimal bd) {
            return bd.intValue();
        }

        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        }

        return def;
    }

    private static void keys (String kind,
                              String... names)
    {
        KEYS.put(kind, new LinkedHashSet<>(List.of(names)));
    }

    private static List<Object> list (Object value)
    {
        if (value instanceof List<?> l) {
            return new ArrayList<>(l);
        }

        return Collections.emptyList();
    }

    private static Map<?, ?> map (Object value)
    {
        return (value instanceof Map<?, ?> m) ? m : Collections.emptyMap();
    }

    private static List<Map<?, ?>> maps (Object value)
    {
        final List<Map<?, ?>> maps = new ArrayList<>();

        for (Object o : list(value)) {
            if (o instanceof Map<?, ?> m) {
                maps.add(m);
            }
        }

        return maps;
    }
}

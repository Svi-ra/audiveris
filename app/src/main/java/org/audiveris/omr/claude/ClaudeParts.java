//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     C l a u d e P a r t s                                      //
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

import org.audiveris.omr.claude.Json.JsonException;
import org.audiveris.omr.math.Rational;

import org.audiveris.proxymusic.Attributes;
import org.audiveris.proxymusic.Barline;
import org.audiveris.proxymusic.Direction;
import org.audiveris.proxymusic.Note;
import org.audiveris.proxymusic.Notations;
import org.audiveris.proxymusic.ScorePartwise;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Class <code>ClaudeParts</code> partitions a Claude score description into one standalone
 * description per part (instrument or singer voice), from which a separate MusicXML file is
 * written for each part.
 * <p>
 * The partitioning works on the description itself, so that each part goes through exactly the
 * same {@link ClaudeScoreBuilder} as the full score:
 * <ol>
 * <li>copy the whole description (title, subtitle, opus, composer, lyricist, arranger, rights,
 * source, credits) and keep only the wanted part, with its name, abbreviation, staves,
 * transposition and all its measures;
 * <li>add the part name as a header text (top-left), as printed on an instrumental part;
 * <li>copy the "system" marks of the top part (metronome marks and tempo words such as
 * <i>Allegro</i>, <i>rit.</i>, <i>a tempo</i>), which a full score usually prints only once, above
 * the top staff, into every other part, unless the part already has them;
 * <li>drop the system and page breaks of the full score and its staff size, which do not fit a
 * single part;
 * <li>after the build, check that the part content (notes, rests, graces, ties, lyrics,
 * notations, clefs, keys, times, barlines, directions) is the same as in the full score.
 * </ol>
 * File names come from the part names: <code>"Violin I"</code> gives <code>Violin_I</code>,
 * invalid characters become <code>_</code>, duplicates get <code>_2</code>, <code>_3</code>... and
 * unnamed parts are named <code>Part_&lt;n&gt;</code>.
 *
 * @author Audiveris contributors
 */
public abstract class ClaudeParts
{
    //~ Static fields/initializers -----------------------------------------------------------------

    /** Header position of the part name. */
    public static final String PART_NAME_POSITION = "top-left";

    /** Font size of the part name. */
    private static final int PART_NAME_FONT_SIZE = 12;

    /** Maximum length of a file stem. */
    private static final int MAX_STEM_LENGTH = 100;

    /** Tempo words which, as metronome marks, apply to the whole system. */
    private static final Pattern TEMPO_WORDS = Pattern.compile(
            "^\\s*(a\\s+tempo|tempo\\b|l'istesso|grave|larg|lent|adagi|andant|moderat|allegr|vivac"
                    + "|vivo|prest|rit|rall|accel|string|allarg|calando|morendo|smorz|pi[uù]\\s+mosso"
                    + "|meno\\s+mosso|come\\s+prima)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern INVALID_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]");

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9");

    //~ Constructors -------------------------------------------------------------------------------

    private ClaudeParts ()
    {
    }

    //~ Static Methods -----------------------------------------------------------------------------

    //---------------//
    // compareCounts //
    //---------------//
    /**
     * Compare the content of a part in the full score with the content of its part score.
     *
     * @param source          counts of the part in the full score
     * @param result          counts of the part score
     * @param propagatedMarks number of system marks copied into the part
     * @return the differences, empty if none
     */
    public static List<String> compareCounts (Map<String, Integer> source,
                                              Map<String, Integer> result,
                                              int propagatedMarks)
    {
        final List<String> diffs = new ArrayList<>();
        final Set<String> keys = new TreeSet<>(source.keySet());
        keys.addAll(result.keySet());

        for (String key : keys) {
            final int expected = source.getOrDefault(key, 0)
                    + (key.equals("directions") ? propagatedMarks : 0);
            final int actual = result.getOrDefault(key, 0);

            if (expected != actual) {
                diffs.add(key + " " + actual + " instead of " + expected);
            }
        }

        return diffs;
    }

    //---------------//
    // contentCounts //
    //---------------//
    /**
     * Count the musical content of one MusicXML part (layout prints are not counted).
     *
     * @param part the part
     * @return counts per category (measures, notes, rests, graces, ties, lyrics, notations,
     *         clefs, keys, times, barlines, directions)
     */
    public static Map<String, Integer> contentCounts (ScorePartwise.Part part)
    {
        final Map<String, Integer> counts = new TreeMap<>();
        counts.put("measures", part.getMeasure().size());

        for (ScorePartwise.Part.Measure measure : part.getMeasure()) {
            for (Object item : measure.getNoteOrBackupOrForward()) {
                switch (item) {
                    case Note note -> {
                        final String kind = (note.getGrace() != null) ? "graces"
                                : ((note.getRest() != null) ? "rests" : "notes");
                        counts.merge(kind, 1, Integer::sum);
                        counts.merge("ties", note.getTie().size(), Integer::sum);
                        counts.merge("lyrics", note.getLyric().size(), Integer::sum);

                        for (Notations notations : note.getNotations()) {
                            counts.merge(
                                    "notations",
                                    notations.getTiedOrSlurOrTuplet().size(),
                                    Integer::sum);
                        }
                    }
                    case Attributes attributes -> {
                        counts.merge("clefs", attributes.getClef().size(), Integer::sum);
                        counts.merge("keys", attributes.getKey().size(), Integer::sum);
                        counts.merge("times", attributes.getTime().size(), Integer::sum);
                    }
                    case Barline barline -> counts.merge("barlines", 1, Integer::sum);
                    case Direction direction -> counts.merge("directions", 1, Integer::sum);
                    default -> {
                    }
                }
            }
        }

        return counts;
    }

    //----------//
    // deepCopy //
    //----------//
    @SuppressWarnings("unchecked")
    private static Object deepCopy (Object value)
    {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach( (k, v) -> copy.put((String) k, deepCopy(v)));

            return copy;
        }

        if (value instanceof List<?> list) {
            final List<Object> copy = new ArrayList<>();
            list.forEach(v -> copy.add(deepCopy(v)));

            return copy;
        }

        return value; // String, BigDecimal, Boolean or null are immutable
    }

    //---------//
    // eventAt //
    //---------//
    /**
     * Find in a measure the event of its first voice that starts at (or right after) the given
     * onset, to attach a copied mark to it.
     */
    private static Map<String, Object> eventAt (Map<String, Object> measure,
                                                Rational onset)
    {
        for (Map<String, Object> voice : maps(measure.get("voices"))) {
            final List<Map<String, Object>> events = maps(voice.get("events"));

            if (events.isEmpty()) {
                continue;
            }

            Rational t = Rational.ZERO;
            Map<String, Object> last = null;

            for (Map<String, Object> event : events) {
                if (isGrace(event)) {
                    continue;
                }

                if (t.compareTo(onset) >= 0) {
                    return event;
                }

                last = event;

                if (isMeasureRest(event)) {
                    break;
                }

                t = t.plus(ClaudeScoreBuilder.eventDuration(event));
            }

            return (last != null) ? last : events.get(0);
        }

        return null;
    }

    //---------//
    // isGrace //
    //---------//
    private static boolean isGrace (Map<String, Object> event)
    {
        return Boolean.TRUE.equals(event.get("grace"));
    }

    //---------------//
    // isMeasureRest //
    //---------------//
    private static boolean isMeasureRest (Map<String, Object> event)
    {
        return Boolean.TRUE.equals(event.get("rest")) && Boolean.TRUE.equals(event.get(
                "measureRest"));
    }

    //------//
    // maps //
    //------//
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps (Object value)
    {
        final List<Map<String, Object>> list = new ArrayList<>();

        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    list.add((Map<String, Object>) m);
                }
            }
        }

        return list;
    }

    //---------------//
    // markSignature //
    //---------------//
    /**
     * Identify a tempo or words direction, to avoid copying a mark already present.
     */
    private static String markSignature (Map<String, Object> direction)
    {
        if (direction.get("tempo") instanceof Map<?, ?> tempo) {
            return "tempo " + Objects.toString(tempo.get("beatUnit"), "quarter") + " "
                    + Objects.toString(tempo.get("dots"), "0") + " " + tempo.get("perMinute");
        }

        if (direction.get("words") instanceof String words) {
            return "words " + words.trim().toLowerCase(Locale.ROOT);
        }

        return null;
    }

    //-----------//
    // partition //
    //-----------//
    /**
     * Partition a score description into one standalone description per part.
     *
     * @param json the parsed description (as returned by {@link Json#parse(String)}), which is
     *             not modified
     * @return one entry per part, in score order
     * @throws JsonException if the description has no parts
     */
    public static List<PartDescription> partition (Object json)
    {
        if (!(json instanceof Map<?, ?> rootMap)) {
            throw new JsonException("$: object expected");
        }

        final List<Map<String, Object>> parts = maps(rootMap.get("parts"));

        if (parts.isEmpty()) {
            throw new JsonException("$.parts: at least one part is required");
        }

        final List<String> names = new ArrayList<>();
        parts.forEach(p -> names.add((p.get("name") instanceof String s) ? s.trim() : ""));

        final List<String> stems = uniqueStems(names);
        final List<Mark> marks = systemMarks(parts.get(0));
        final List<PartDescription> result = new ArrayList<>();

        for (int i = 0; i < parts.size(); i++) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> root = (Map<String, Object>) deepCopy(rootMap);
            @SuppressWarnings("unchecked")
            final Map<String, Object> part = (Map<String, Object>) deepCopy(parts.get(i));
            root.put("parts", new ArrayList<>(List.of(part)));

            // Full score layout: keep the page size only, staff size is recomputed for one part
            if (root.remove("layout") instanceof Map<?, ?> layout) {
                final Map<String, Object> pageOnly = new LinkedHashMap<>();

                for (String key : new String[] { "pageWidth", "pageHeight" }) {
                    if (layout.get(key) != null) {
                        pageOnly.put(key, layout.get(key));
                    }
                }

                if (!pageOnly.isEmpty()) {
                    root.put("layout", pageOnly);
                }
            }

            // Full score system and page breaks
            for (Map<String, Object> measure : maps(part.get("measures"))) {
                measure.remove("newSystem");
                measure.remove("newPage");
            }

            // Part name in the header
            final String name = names.get(i);

            if (!name.isEmpty()) {
                final List<Object> credits = (root.get("credits") instanceof List<?> l)
                        ? new ArrayList<>(l)
                        : new ArrayList<>();
                final boolean present = maps(credits).stream().anyMatch(
                        c -> name.equalsIgnoreCase(Objects.toString(c.get("text"), "").trim()));

                if (!present) {
                    final Map<String, Object> credit = new LinkedHashMap<>();
                    credit.put("text", name);
                    credit.put("position", PART_NAME_POSITION);
                    credit.put("fontSize", new BigDecimal(PART_NAME_FONT_SIZE));
                    credits.add(0, credit);
                    root.put("credits", credits);
                }
            }

            final int propagated = (i == 0) ? 0 : propagateMarks(marks, part);
            result.add(new PartDescription(i, name, stems.get(i), root, propagated));
        }

        return result;
    }

    //----------------//
    // propagateMarks //
    //----------------//
    /**
     * Copy the system marks of the top part into a part that does not have them yet.
     *
     * @return the number of marks copied
     */
    private static int propagateMarks (List<Mark> marks,
                                       Map<String, Object> part)
    {
        final List<Map<String, Object>> measures = maps(part.get("measures"));
        int count = 0;

        for (Mark mark : marks) {
            if (mark.measureIndex >= measures.size()) {
                continue;
            }

            final Map<String, Object> measure = measures.get(mark.measureIndex);
            final Set<String> present = new HashSet<>();

            for (Map<String, Object> voice : maps(measure.get("voices"))) {
                for (Map<String, Object> event : maps(voice.get("events"))) {
                    for (Map<String, Object> dir : maps(event.get("directions"))) {
                        present.add(markSignature(dir));
                    }
                }
            }

            if (present.contains(markSignature(mark.direction))) {
                continue;
            }

            final Map<String, Object> event;

            try {
                event = eventAt(measure, mark.onset);
            } catch (JsonException ex) {
                continue; // Invalid durations are reported by the full score build
            }

            if (event == null) {
                continue;
            }

            @SuppressWarnings("unchecked")
            final Map<String, Object> copy = (Map<String, Object>) deepCopy(mark.direction);
            copy.remove("staff"); // Staff of the top part, meaningless here

            final List<Object> directions = (event.get("directions") instanceof List<?> l)
                    ? new ArrayList<>(l)
                    : new ArrayList<>();
            directions.add(copy);
            event.put("directions", directions);
            count++;
        }

        return count;
    }

    //------------------//
    // sanitizeFileName //
    //------------------//
    /**
     * Turn a part name into a safe file stem (without extension).
     * <p>
     * <code>"Violin I"</code> &rarr; <code>Violin_I</code>, <code>"Horn in F 1/2"</code> &rarr;
     * <code>Horn_in_F_1_2</code>; Unicode letters are kept.
     *
     * @param name the part name, perhaps null or empty
     * @param def  the stem to use when nothing is left
     * @return the file stem
     */
    public static String sanitizeFileName (String name,
                                           String def)
    {
        String text = Normalizer.normalize((name != null) ? name : "", Normalizer.Form.NFKC);
        text = INVALID_CHARS.matcher(text).replaceAll("_");
        text = text.replaceAll("\\s+", "_").replaceAll("_+", "_");
        text = text.replaceAll("^[ ._]+|[ ._]+$", "");

        if (text.length() > MAX_STEM_LENGTH) {
            text = text.substring(0, MAX_STEM_LENGTH).replaceAll("[ ._]+$", "");
        }

        if (text.isEmpty()) {
            text = def;
        }

        if (WINDOWS_RESERVED.contains(text.split("\\.")[0].toUpperCase(Locale.ROOT))) {
            text = text + "_";
        }

        return text;
    }

    //-------------//
    // systemMarks //
    //-------------//
    /**
     * Collect the metronome marks and tempo words of the top part, with their position.
     */
    private static List<Mark> systemMarks (Map<String, Object> top)
    {
        final List<Mark> marks = new ArrayList<>();
        final List<Map<String, Object>> measures = maps(top.get("measures"));

        for (int mi = 0; mi < measures.size(); mi++) {
            final Set<String> seen = new HashSet<>();

            for (Map<String, Object> voice : maps(measures.get(mi).get("voices"))) {
                Rational onset = Rational.ZERO;

                for (Map<String, Object> event : maps(voice.get("events"))) {
                    final List<Map<String, Object>> dirs = maps(event.get("directions"));
                    final boolean withTempo = dirs.stream().anyMatch(d -> d.get("tempo") != null);

                    for (Map<String, Object> dir : dirs) {
                        final boolean isMark = (dir.get("tempo") != null)
                                || ((dir.get("words") instanceof String words) && (withTempo
                                        || TEMPO_WORDS.matcher(words).find()));

                        if (isMark && seen.add(markSignature(dir))) {
                            marks.add(new Mark(mi, onset, dir));
                        }
                    }

                    if (isMeasureRest(event)) {
                        break;
                    }

                    if (!isGrace(event)) {
                        try {
                            onset = onset.plus(ClaudeScoreBuilder.eventDuration(event));
                        } catch (JsonException ex) {
                            break; // Reported by the full score build
                        }
                    }
                }
            }
        }

        return marks;
    }

    //-------------//
    // uniqueStems //
    //-------------//
    /**
     * Sanitize part names and make them unique (case-insensitive, Windows-safe).
     * <p>
     * Duplicates get <code>_2</code>, <code>_3</code>... in order of appearance; unnamed parts
     * become <code>Part_&lt;n&gt;</code> where n is their 1-based position.
     *
     * @param names the part names
     * @return the file stems, in the same order
     */
    public static List<String> uniqueStems (List<String> names)
    {
        final Set<String> taken = new HashSet<>();
        final List<String> stems = new ArrayList<>();

        for (int i = 0; i < names.size(); i++) {
            final String base = sanitizeFileName(names.get(i), "Part_" + (i + 1));
            String stem = base;

            for (int n = 2; taken.contains(stem.toLowerCase(Locale.ROOT)); n++) {
                stem = base + "_" + n;
            }

            taken.add(stem.toLowerCase(Locale.ROOT));
            stems.add(stem);
        }

        return stems;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //------//
    // Mark //
    //------//
    /**
     * A system mark (metronome mark or tempo words) of the top part.
     */
    private static record Mark(int measureIndex, Rational onset, Map<String, Object> direction)
    {
    }

    //-----------------//
    // PartDescription //
    //-----------------//
    /**
     * The standalone description of one part.
     *
     * @param index           0-based index of the part in the full score
     * @param name            the part name, perhaps empty
     * @param stem            the file stem for this part
     * @param description     the standalone description (a JSON object tree)
     * @param propagatedMarks number of system marks copied from the top part
     */
    public static record PartDescription(int index,
            String name,
            String stem,
            Map<String, Object> description,
            int propagatedMarks)
    {
    }
}

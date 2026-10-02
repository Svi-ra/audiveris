//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                 C l a u d e P a r t s T e s t                                  //
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

import org.audiveris.omr.claude.ClaudeParts.PartDescription;

import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.util.Marshalling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link ClaudeParts}.
 */
public class ClaudePartsTest
{
    /** Three parts: the tempo marks are only above the top part, the cello has "Allegro". */
    private static final String SAMPLE = """
            {
              "format": "audiveris-claude-omr/1",
              "title": "Trio", "composer": "Anonymous",
              "credits": [ { "text": "Edition XYZ", "position": "bottom-left" } ],
              "layout": { "staffHeight": 4, "pageWidth": 216, "pageHeight": 279 },
              "parts": [
                { "id": "P1", "name": "Flute", "abbreviation": "Fl.",
                  "measures": [
                    { "number": "1", "key": { "fifths": 0 }, "time": { "beats": 2, "beatType": 4 },
                      "clefs": [ { "sign": "G" } ],
                      "voices": [ { "voice": 1, "events": [
                        { "type": "quarter", "pitches": [ { "step": "C", "octave": 5 } ],
                          "directions": [ { "words": "Allegro" },
                                          { "tempo": { "beatUnit": "quarter", "perMinute": 120 } },
                                          { "dynamics": "f" } ] },
                        { "type": "quarter", "pitches": [ { "step": "D", "octave": 5 } ],
                          "directions": [ { "words": "dolce" } ] }
                      ] } ] },
                    { "number": "2", "newSystem": true,
                      "voices": [ { "voice": 1, "events": [
                        { "type": "quarter", "rest": true },
                        { "type": "quarter", "pitches": [ { "step": "E", "octave": 5 } ],
                          "directions": [ { "words": "rit." } ] }
                      ] } ] }
                  ] },
                { "id": "P2", "name": "Horn in F", "transpose": { "chromatic": -7 },
                  "measures": [
                    { "number": "1", "key": { "fifths": 1 }, "time": { "beats": 2, "beatType": 4 },
                      "clefs": [ { "sign": "G" } ],
                      "voices": [ { "voice": 1, "events": [ { "rest": true, "measureRest": true } ] } ] },
                    { "number": "2", "newSystem": true,
                      "voices": [ { "voice": 1, "events": [
                        { "type": "half", "pitches": [ { "step": "G", "octave": 4 } ] }
                      ] } ] }
                  ] },
                { "id": "P3", "name": "Cello",
                  "measures": [
                    { "number": "1", "key": { "fifths": 0 }, "time": { "beats": 2, "beatType": 4 },
                      "clefs": [ { "sign": "F" } ],
                      "voices": [ { "voice": 1, "events": [
                        { "type": "half", "pitches": [ { "step": "C", "octave": 3 } ],
                          "directions": [ { "words": "allegro" } ] }
                      ] } ] },
                    { "number": "2", "newSystem": true,
                      "voices": [ { "voice": 1, "events": [
                        { "type": "quarter", "pitches": [ { "step": "D", "octave": 3 } ] },
                        { "type": "quarter", "pitches": [ { "step": "E", "octave": 3 } ] }
                      ] } ] }
                  ] }
              ]
            }
            """;

    private static String marshal (ScorePartwise scorePartwise)
        throws Exception
    {
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        Marshalling.marshal(scorePartwise, os, false, 2);

        return os.toString(StandardCharsets.UTF_8);
    }

    private static int occurrences (String text,
                                    String sub)
    {
        int count = 0;

        for (int i = text.indexOf(sub); i >= 0; i = text.indexOf(sub, i + 1)) {
            count++;
        }

        return count;
    }

    @Test
    public void testPartition ()
        throws Exception
    {
        final Object json = Json.parse(SAMPLE);
        final List<PartDescription> parts = ClaudeParts.partition(json);
        assertEquals(3, parts.size());
        assertEquals("Horn_in_F", parts.get(1).stem());

        // The source description is left unchanged
        assertTrue(Json.parse(SAMPLE).equals(json));

        final ScorePartwise full = new ClaudeScoreBuilder(null).build(json);

        for (PartDescription pd : parts) {
            final ScorePartwise partScore = new ClaudeScoreBuilder(null).setChecks(false).build(
                    pd.description());
            assertEquals(1, partScore.getPart().size());
            assertTrue(
                    ClaudeParts.compareCounts(
                            ClaudeParts.contentCounts(full.getPart().get(pd.index())),
                            ClaudeParts.contentCounts(partScore.getPart().get(0)),
                            pd.propagatedMarks()).isEmpty());

            final String xml = marshal(partScore);
            assertTrue(xml.contains("<work-title>Trio</work-title>"));
            assertTrue(xml.contains("Edition XYZ"));
            assertTrue(xml.contains(">" + pd.name() + "</credit-words>"));
            assertFalse(xml.contains("new-system"));
            assertTrue(xml.contains("<millimeters>7"));
        }

        // Top part: nothing copied
        assertEquals(0, parts.get(0).propagatedMarks());

        // Horn: Allegro, metronome mark and rit. copied, not "dolce" nor the dynamics
        assertEquals(3, parts.get(1).propagatedMarks());

        final String horn = marshal(new ClaudeScoreBuilder(null).setChecks(false).build(
                parts.get(1).description()));
        assertTrue(horn.contains(">Allegro</words>"));
        assertTrue(horn.contains("<per-minute>120</per-minute>"));
        assertTrue(horn.contains(">rit.</words>"));
        assertFalse(horn.contains("dolce"));
        assertFalse(horn.contains("<f/>"));
        assertTrue(horn.contains("<chromatic>-7</chromatic>"));
        assertTrue(horn.contains("<part-name>Horn in F</part-name>"));

        // Cello already has "allegro": only the metronome mark and rit. are copied
        assertEquals(2, parts.get(2).propagatedMarks());

        final String cello = marshal(new ClaudeScoreBuilder(null).setChecks(false).build(
                parts.get(2).description()));
        assertEquals(1, occurrences(cello.toLowerCase(), ">allegro</words>"));
    }

    @Test
    public void testImportWithParts ()
        throws Exception
    {
        final Path dir = Files.createTempDirectory("claude-parts");
        final Path json = dir.resolve("trio.claude.json");
        Files.writeString(json, SAMPLE, StandardCharsets.UTF_8);

        final Path full = ClaudeOmr.importScore(json, dir, false, true);
        assertTrue(Files.exists(full));

        final Path folder = dir.resolve("trio" + ClaudeOmr.PARTS_FOLDER_SUFFIX);

        for (String stem : new String[] { "Flute", "Horn_in_F", "Cello" }) {
            assertTrue(stem, Files.exists(folder.resolve(stem + ".xml")));
        }
    }

    @Test
    public void testSinglePart ()
        throws Exception
    {
        final Map<?, ?> root = (Map<?, ?>) Json.parse(SAMPLE);
        final List<?> parts = (List<?>) root.get("parts");
        parts.subList(1, parts.size()).clear();

        assertEquals(1, ClaudeParts.partition(root).size());
    }

    @Test
    public void testStems ()
    {
        assertEquals("Violin_I", ClaudeParts.sanitizeFileName("Violin I", "x"));
        assertEquals("Horn_in_F_1_2", ClaudeParts.sanitizeFileName("Horn in F 1/2", "x"));
        assertEquals("Скрипка_I", ClaudeParts.sanitizeFileName("Скрипка I", "x"));
        assertEquals("CON_", ClaudeParts.sanitizeFileName("con", "x").toUpperCase());
        assertEquals(
                List.of("Trumpet", "Trumpet_2", "Part_3", "trombone"),
                ClaudeParts.uniqueStems(List.of("Trumpet", "Trumpet", "", "trombone")));
    }
}

//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                          C l a u d e S c o r e B u i l d e r T e s t                           //
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

import org.audiveris.proxymusic.ScorePartwise;
import org.audiveris.proxymusic.util.Marshalling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link Json} and {@link ClaudeScoreBuilder}.
 */
public class ClaudeScoreBuilderTest
{
    private static final String SAMPLE = """
            {
              "format": "audiveris-claude-omr/1",
              "title": "Test piece",
              "composer": "Anonymous",
              "parts": [
                {
                  "id": "P1", "name": "Piano", "staves": 2,
                  "measures": [
                    {
                      "number": "1",
                      "key": { "fifths": 1, "mode": "major" },
                      "time": { "beats": 3, "beatType": 4 },
                      "clefs": [ { "staff": 1, "sign": "G" }, { "staff": 2, "sign": "F" } ],
                      "voices": [
                        { "voice": 1, "staff": 1, "events": [
                          { "type": "quarter", "dots": 1,
                            "pitches": [ { "step": "F", "octave": 4, "alter": 1, "tie": "start" },
                                         { "step": "A", "octave": 4 } ],
                            "directions": [ { "dynamics": "mf" } ],
                            "articulations": ["staccato"] },
                          { "type": "eighth", "rest": true },
                          { "type": "eighth", "pitches": [ { "step": "C", "octave": 5 } ],
                            "tuplet": { "actual": 3, "normal": 2, "type": "start" },
                            "beams": ["begin"] },
                          { "type": "eighth", "pitches": [ { "step": "D", "octave": 5 } ],
                            "tuplet": { "actual": 3, "normal": 2 }, "beams": ["continue"] },
                          { "type": "eighth", "pitches": [ { "step": "E", "octave": 5 } ],
                            "tuplet": { "actual": 3, "normal": 2, "type": "stop" },
                            "beams": ["end"] }
                        ] },
                        { "voice": 5, "staff": 2, "events": [
                          { "rest": true, "measureRest": true }
                        ] }
                      ],
                      "rightBarline": { "repeat": "backward" }
                    },
                    {
                      "number": "2", "newSystem": true,
                      "voices": [
                        { "voice": 1, "staff": 1, "events": [
                          { "type": "half", "dots": 1,
                            "pitches": [ { "step": "F", "octave": 4, "alter": 1, "tie": "stop" } ],
                            "lyrics": [ { "text": "la" } ] }
                        ] },
                        { "voice": 5, "staff": 2, "events": [
                          { "rest": true, "measureRest": true }
                        ] }
                      ]
                    }
                  ]
                }
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

    @Test
    public void testJson ()
    {
        final Object value = Json.parse("{\"a\": [1, 2.5, \"x\\u0041\", true, null], \"b\": {}}");
        assertTrue(value instanceof Map);

        final List<?> a = (List<?>) ((Map<?, ?>) value).get("a");
        assertEquals(5, a.size());
        assertEquals("xA", a.get(2));
        assertEquals(Boolean.TRUE, a.get(3));
    }

    @Test(expected = JsonException.class)
    public void testJsonError ()
    {
        Json.parse("{\"a\": [1, 2,]}");
    }

    @Test
    public void testBuild ()
        throws Exception
    {
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder("test");
        final ScorePartwise scorePartwise = builder.build(Json.parse(SAMPLE));
        final String xml = marshal(scorePartwise);

        // Dotted quarter + triplet of eighths: divisions must be 6 per quarter
        assertTrue(xml, xml.contains("<divisions>6</divisions>"));
        assertTrue(xml, xml.contains("<duration>9</duration>")); // dotted quarter
        assertTrue(xml, xml.contains("<duration>2</duration>")); // triplet eighth
        assertTrue(xml, xml.contains("<duration>18</duration>")); // measure rest & dotted half
        assertTrue(xml, xml.contains("<backup>"));
        assertTrue(xml, xml.contains("<chord/>"));
        assertTrue(xml, xml.contains("<staves>2</staves>"));
        assertTrue(xml, xml.contains("<fifths>1</fifths>"));
        assertTrue(xml, xml.contains("<rest measure=\"yes\""));
        assertTrue(xml, xml.contains("<actual-notes>3</actual-notes>"));
        assertTrue(xml, xml.contains("<tuplet"));
        assertTrue(xml, xml.contains("<staccato"));
        assertTrue(xml, xml.contains("<mf/>"));
        assertTrue(xml, xml.contains("<repeat direction=\"backward\""));
        assertTrue(xml, xml.contains("new-system=\"yes\""));
        assertTrue(xml, xml.contains("<text>la</text>"));
        assertTrue(xml, xml.contains("Test piece"));
        assertTrue(builder.getWarnings().toString(), builder.getWarnings().isEmpty());
    }

    @Test
    public void testIncompleteMeasureWarning ()
    {
        final String json = """
                { "parts": [ { "measures": [ {
                    "time": { "beats": 4, "beatType": 4 },
                    "voices": [ { "events": [
                        { "type": "half", "pitches": [ { "step": "C", "octave": 4 } ] } ] } ]
                } ] } ] }
                """;
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(null);
        builder.build(Json.parse(json));
        assertEquals(1, builder.getWarnings().size());
    }

    /**
     * Two staves, each with a wedge and a slur crossing the barline (so they overlap in
     * document order), plus a transposition.
     */
    private static final String TWO_STAVES = """
            { "parts": [ { "staves": 2, "transpose": { "chromatic": -8 },
              "measures": [
              { "time": { "beats": 1, "beatType": 4 },
                "voices": [
                  { "voice": 1, "staff": 1, "events": [
                    { "type": "quarter", "pitches": [ { "step": "C", "octave": 5 } ],
                      "slurs": ["start"], "directions": [ { "wedge": "crescendo" } ] } ] },
                  { "voice": 5, "staff": 2, "events": [
                    { "type": "quarter", "pitches": [ { "step": "C", "octave": 3 } ],
                      "slurs": ["start"], "directions": [ { "wedge": "diminuendo" } ] } ] }
                ] },
              { "voices": [
                  { "voice": 1, "staff": 1, "events": [
                    { "type": "quarter", "pitches": [ { "step": "D", "octave": 5 } ],
                      "slurs": ["stop"], "directions": [ { "wedge": "stop" } ] } ] },
                  { "voice": 5, "staff": 2, "events": [
                    { "type": "quarter", "pitches": [ { "step": "D", "octave": 3 } ],
                      "slurs": ["stop"], "directions": [ { "wedge": "stop" } ] } ] }
                ] } ] } ] }
            """;

    @Test
    public void testSpannerNumbers ()
        throws Exception
    {
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(null);
        final String xml = marshal(builder.build(Json.parse(TWO_STAVES)));

        // Wedges and slurs of staff 1 and staff 2 overlap: they must not share a number
        assertTrue(xml, xml.contains("<wedge type=\"crescendo\" number=\"1\""));
        assertTrue(xml, xml.contains("<wedge type=\"diminuendo\" number=\"2\""));
        assertTrue(xml, xml.contains("<wedge type=\"stop\" number=\"1\""));
        assertTrue(xml, xml.contains("<wedge type=\"stop\" number=\"2\""));
        assertTrue(xml, xml.contains("<slur type=\"start\" number=\"2\""));
        assertTrue(builder.getWarnings().toString(), builder.getWarnings().isEmpty());
    }

    @Test
    public void testUnstoppedWedge ()
    {
        // Replace the first wedge stop (staff 1) by words
        final String json = TWO_STAVES.replaceFirst(
                "\\{ \"wedge\": \"stop\" \\}",
                "{ \"words\": \"x\" }");
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(null);
        builder.build(Json.parse(json));
        assertEquals(builder.getWarnings().toString(), 1, builder.getWarnings().size());
        assertTrue(builder.getWarnings().get(0).contains("never stopped"));
    }

    @Test
    public void testTransposeAndLayout ()
        throws Exception
    {
        final String xml = marshal(new ClaudeScoreBuilder(null).build(Json.parse(TWO_STAVES)));

        // Horn in E: minor sixth down, diatonic computed from chromatic
        assertTrue(xml, xml.contains("<diatonic>-5</diatonic>"));
        assertTrue(xml, xml.contains("<chromatic>-8</chromatic>"));

        // Two staves only: maximum staff height
        assertTrue(xml, xml.contains("<millimeters>7.00</millimeters>"));
        assertTrue(xml, xml.contains("<page-width>1200.00</page-width>"));

        // Explicit layout
        final String custom = TWO_STAVES.replaceFirst(
                "\\{ \"parts\"",
                "{ \"layout\": { \"staffHeight\": 5 }, \"parts\"");
        final String xml2 = marshal(new ClaudeScoreBuilder(null).build(Json.parse(custom)));
        assertTrue(xml2, xml2.contains("<millimeters>5.00</millimeters>"));
        assertTrue(xml2, xml2.contains("<page-height>2376.00</page-height>"));
    }

    @Test
    public void testInvalidType ()
    {
        final String json = """
                { "parts": [ { "measures": [ { "voices": [ { "events": [
                    { "type": "crotchet", "pitches": [ { "step": "C", "octave": 4 } ] } ] } ] } ] } ] }
                """;

        try {
            new ClaudeScoreBuilder(null).build(Json.parse(json));
            fail("Expected a JsonException");
        } catch (JsonException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("events[0].type"));
        }
    }

    /** One single-staff part of 2/4 measures, each one given as a JSON event list. */
    private static String part (String keyAndTranspose,
                                String... measureEvents)
    {
        final StringBuilder sb = new StringBuilder("{ " + keyAndTranspose + " \"measures\": [");

        for (int i = 0; i < measureEvents.length; i++) {
            sb.append((i > 0) ? "," : "").append("{");

            if (i == 0) {
                sb.append("\"time\": { \"beats\": 2, \"beatType\": 4 },");
            }

            sb.append("\"voices\": [ { \"events\": [ ").append(measureEvents[i]).append(
                    " ] } ] }");
        }

        return sb.append("] }").toString();
    }

    private static final String HALF_C = """
            { "type": "half", "pitches": [ { "step": "C", "octave": 5 } ] }""";

    private static final String REST = "{ \"rest\": true, \"measureRest\": true }";

    private static List<String> warningsOf (String json)
    {
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(null);
        builder.build(Json.parse(json));

        return builder.getWarnings();
    }

    private static void assertWarning (List<String> warnings,
                                       String fragment)
    {
        assertTrue(
                warnings.toString(),
                warnings.stream().anyMatch(w -> w.contains(fragment)));
    }

    @Test
    public void testUnknownKey ()
    {
        final String json = "{ \"subtitel\": \"x\", \"parts\": [ " + part(
                "",
                HALF_C.replace("\"type\"", "\"bowing\": [\"down-bow\"], \"type\"")) + " ] }";
        final List<String> warnings = warningsOf(json);
        assertWarning(warnings, "unknown key 'subtitel'");
        assertWarning(warnings, "unknown key 'bowing'");
    }

    @Test
    public void testTies ()
    {
        final String start = HALF_C.replace("\"octave\": 5", "\"octave\": 5, \"tie\": \"start\"");
        final String stop = HALF_C.replace("\"octave\": 5", "\"octave\": 5, \"tie\": \"stop\"");

        // Tie started and never stopped (not in the last measure)
        assertWarning(warningsOf("{ \"parts\": [ " + part("", start, HALF_C) + " ] }"),
                "tie is never stopped");

        // Tie stop without start (not in the first measure)
        assertWarning(warningsOf("{ \"parts\": [ " + part("", HALF_C, stop) + " ] }"),
                "tie stop without a tie start");

        // Correct pair, and a tie left open in the last measure (it continues on next page)
        assertTrue(warningsOf("{ \"parts\": [ " + part("", start, stop.replace(
                "\"tie\": \"stop\"", "\"tie\": \"continue\"")) + " ] }").isEmpty());
    }

    @Test
    public void testEmptyStaff ()
    {
        // Two-staff part, second staff left without any voice
        final String json = part("\"staves\": 2,", HALF_C);
        assertWarning(warningsOf("{ \"parts\": [ " + json + " ] }"), "staff 2 has no content");
    }

    @Test
    public void testRepeatedTexts ()
    {
        final String words = REST.replace(
                "}",
                ", \"directions\": [ { \"words\": \"Allegro\" } ] }");

        // Same text in 3 consecutive measures of a part
        assertWarning(
                warningsOf("{ \"parts\": [ " + part("", words, words, words) + " ] }"),
                "consecutive measures");

        // Same text in all parts of a measure
        final String p = part("", words, REST);
        assertWarning(warningsOf("{ \"parts\": [ " + p + "," + p + "," + p + " ] }"),
                "repeated in all 3 parts");
    }

    @Test
    public void testConcertKeys ()
    {
        final String e = "\"key\": { \"fifths\": 4 },";
        final List<String> parts = List.of(
                part("", REST, REST).replace("\"time\"", e + "\"time\""),
                part("", REST, REST).replace("\"time\"", e + "\"time\""),
                // Clarinet in A: written 1 sharp sounds 4 sharps, consistent
                part("\"transpose\": { \"chromatic\": -3 },", REST, REST).replace(
                        "\"time\"",
                        "\"key\": { \"fifths\": 1 }, \"time\""),
                // Misread viola key: 1 sharp instead of 4
                part("", REST, REST).replace("\"time\"", "\"key\": { \"fifths\": 1 }, \"time\""));
        final List<String> warnings = warningsOf(
                "{ \"parts\": [ " + String.join(",", parts) + " ] }");
        assertEquals(warnings.toString(), 1, warnings.size());
        assertWarning(warnings, "$.parts[3].measures[0].key: concert key differs");
    }

    @Test
    public void testCreditsBowingsOrnaments ()
        throws Exception
    {
        final String json = """
                { "title": "T", "subtitle": "S", "opus": "Op. 46", "composer": "C",
                  "rights": "(c) 1985 X",
                  "credits": [ { "text": "Edition X", "position": "bottom-left" },
                               { "text": "(c) 1985 X", "position": "bottom-right" } ],
                  "parts": [ %s ] }
                """.formatted(part("", HALF_C.replace(
                "\"type\"",
                "\"bowings\": [\"down-bow\"], \"ornaments\": [\"trill-mark\"], \"type\"")));
        final ClaudeScoreBuilder builder = new ClaudeScoreBuilder(null);
        final String xml = marshal(builder.build(Json.parse(json)));
        assertTrue(builder.getWarnings().toString(), builder.getWarnings().isEmpty());
        assertTrue(xml, xml.contains("<down-bow"));
        assertTrue(xml, xml.contains("<trill-mark"));
        assertTrue(xml, xml.contains("<credit-type>subtitle</credit-type>"));
        assertTrue(xml, xml.contains("<work-number>Op. 46</work-number>"));
        // Top texts sharing a position are merged (score editors would overlap them)
        assertTrue(xml, xml.contains(">C\nOp. 46</credit-words>"));

        // Footer texts form one line, left to right; rights listed in credits printed once
        assertTrue(xml, xml.contains(">Edition X        (c) 1985 X</credit-words>"));
        assertEquals(xml, xml.indexOf("1985 X</credit-words>"), xml.lastIndexOf("1985 X</credit-words>"));
        assertTrue(xml, xml.contains("<rights>Edition X        (c) 1985 X</rights>"));
        assertTrue(builder.getSummary(), builder.getSummary().contains("bowings 1"));
    }
}

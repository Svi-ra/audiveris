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
}

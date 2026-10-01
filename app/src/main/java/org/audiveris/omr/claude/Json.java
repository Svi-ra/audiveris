//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                            J s o n                                             //
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class <code>Json</code> is a minimal, dependency-free JSON reader.
 * <p>
 * It maps JSON objects to <code>Map&lt;String, Object&gt;</code> (keeping key order),
 * arrays to <code>List&lt;Object&gt;</code>, numbers to <code>BigDecimal</code>,
 * strings to <code>String</code>, booleans to <code>Boolean</code> and null to <code>null</code>.
 * <p>
 * It is used to read the score description produced by Claude vision, so that no additional
 * library is needed by Audiveris.
 *
 * @author Audiveris contributors
 */
public final class Json
{
    //~ Instance fields ----------------------------------------------------------------------------

    private final String text;

    private int pos;

    //~ Constructors -------------------------------------------------------------------------------

    private Json (String text)
    {
        this.text = text;
    }

    //~ Methods ------------------------------------------------------------------------------------

    private JsonException error (String msg)
    {
        int line = 1;
        int col = 1;

        for (int i = 0; i < Math.min(pos, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }

        return new JsonException(msg + " at line " + line + ", column " + col);
    }

    private void expect (char c)
    {
        skipWhitespace();

        if ((pos >= text.length()) || (text.charAt(pos) != c)) {
            throw error("Expected '" + c + "'");
        }

        pos++;
    }

    private boolean peekIs (char c)
    {
        skipWhitespace();

        return (pos < text.length()) && (text.charAt(pos) == c);
    }

    private List<Object> readArray ()
    {
        expect('[');

        final List<Object> list = new ArrayList<>();

        if (peekIs(']')) {
            pos++;

            return list;
        }

        while (true) {
            list.add(readValue());

            if (peekIs(',')) {
                pos++;
            } else {
                expect(']');

                return list;
            }
        }
    }

    private Object readLiteral (String literal,
                                Object value)
    {
        if (!text.startsWith(literal, pos)) {
            throw error("Unexpected token");
        }

        pos += literal.length();

        return value;
    }

    private BigDecimal readNumber ()
    {
        final int start = pos;

        while (pos < text.length()) {
            final char c = text.charAt(pos);

            if (((c >= '0') && (c <= '9')) || (c == '-') || (c == '+') || (c == '.') || (c == 'e')
                    || (c == 'E')) {
                pos++;
            } else {
                break;
            }
        }

        try {
            return new BigDecimal(text.substring(start, pos));
        } catch (NumberFormatException ex) {
            pos = start;
            throw error("Invalid number");
        }
    }

    private Map<String, Object> readObject ()
    {
        expect('{');

        final Map<String, Object> map = new LinkedHashMap<>();

        if (peekIs('}')) {
            pos++;

            return map;
        }

        while (true) {
            skipWhitespace();

            if (!peekIs('"')) {
                throw error("Expected object key");
            }

            final String key = readString();
            expect(':');
            map.put(key, readValue());

            if (peekIs(',')) {
                pos++;
            } else {
                expect('}');

                return map;
            }
        }
    }

    private String readString ()
    {
        expect('"');

        final StringBuilder sb = new StringBuilder();

        while (pos < text.length()) {
            final char c = text.charAt(pos++);

            if (c == '"') {
                return sb.toString();
            }

            if (c != '\\') {
                sb.append(c);

                continue;
            }

            if (pos >= text.length()) {
                break;
            }

            final char e = text.charAt(pos++);

            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if ((pos + 4) > text.length()) {
                        throw error("Invalid unicode escape");
                    }

                    try {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Invalid unicode escape");
                    }

                    pos += 4;
                }
                default -> throw error("Invalid escape '\\" + e + "'");
            }
        }

        throw error("Unterminated string");
    }

    private Object readValue ()
    {
        skipWhitespace();

        if (pos >= text.length()) {
            throw error("Unexpected end of input");
        }

        final char c = text.charAt(pos);

        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if ((c == '-') || ((c >= '0') && (c <= '9'))) {
                    yield readNumber();
                }

                throw error("Unexpected character '" + c + "'");
            }
        };
    }

    private void skipWhitespace ()
    {
        while ((pos < text.length()) && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    //~ Static Methods -----------------------------------------------------------------------------

    /**
     * Parse the provided JSON text.
     *
     * @param text the JSON text
     * @return the resulting value (Map, List, String, BigDecimal, Boolean or null)
     * @throws JsonException if text is not valid JSON
     */
    public static Object parse (String text)
    {
        final Json json = new Json(text);
        final Object value = json.readValue();
        json.skipWhitespace();

        if (json.pos != text.length()) {
            throw json.error("Trailing characters");
        }

        return value;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    /**
     * Exception raised on malformed JSON or on unexpected JSON structure.
     */
    public static class JsonException
            extends RuntimeException
    {
        /**
         * Create a JsonException.
         *
         * @param message explanation
         */
        public JsonException (String message)
        {
            super(message);
        }
    }
}

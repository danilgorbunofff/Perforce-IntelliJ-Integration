package p4gate;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal reader for `p4 -ztag -Mj` output: one flat JSON object per line, values are strings or numbers.
 *  Kept dependency-free on purpose (the platform's Gson is a module jar a plugin would have to declare). */
final class P4Json {
    private P4Json() { }

    /** Parses one flat object; returns null when the line is not a JSON object (e.g. plain-text p4 output). */
    static Map<String, String> parseObject(String line) {
        String s = line.strip();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        int[] pos = {1};
        skipWs(s, pos);
        if (peek(s, pos) == '}') {
            return out;
        }
        while (true) {
            skipWs(s, pos);
            String key = readString(s, pos);
            skipWs(s, pos);
            expect(s, pos, ':');
            skipWs(s, pos);
            String value = peek(s, pos) == '"' ? readString(s, pos) : readBare(s, pos);
            out.put(key, value);
            skipWs(s, pos);
            char c = s.charAt(pos[0]++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + (pos[0] - 1) + " in: " + s);
            }
        }
    }

    private static char peek(String s, int[] pos) {
        if (pos[0] >= s.length()) throw new IllegalArgumentException("unexpected end of: " + s);
        return s.charAt(pos[0]);
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    private static void expect(String s, int[] pos, char c) {
        if (peek(s, pos) != c) throw new IllegalArgumentException("expected '" + c + "' at " + pos[0] + " in: " + s);
        pos[0]++;
    }

    /** Numbers, true/false/null — kept as their literal text. Nested arrays/objects are not produced by -Mj -ztag. */
    private static String readBare(String s, int[] pos) {
        int start = pos[0];
        while (pos[0] < s.length() && ",}".indexOf(s.charAt(pos[0])) < 0) pos[0]++;
        String v = s.substring(start, pos[0]).strip();
        if (v.startsWith("{") || v.startsWith("[")) throw new IllegalArgumentException("nested value in: " + s);
        return v;
    }

    private static String readString(String s, int[] pos) {
        expect(s, pos, '"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(pos[0]++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(pos[0]++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(pos[0], pos[0] + 4), 16));
                    pos[0] += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + e + " in: " + s);
            }
        }
    }
}

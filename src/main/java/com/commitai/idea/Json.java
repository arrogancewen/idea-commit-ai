package com.commitai.idea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer + reader.
 *
 * <p>Only what is needed to talk to OpenAI-compatible and Anthropic-compatible
 * chat APIs: build a request body and read a few nested string fields out of the
 * response. Written by hand so the plugin needs no third-party libraries and no
 * extra runtime dependencies.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ write

    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------ read

    /** Parses {@code text}; returns Map / List / String / Double / Boolean / null. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        if (p.eof()) {
            throw new IllegalArgumentException("empty JSON document");
        }
        Object value = p.value();
        p.skipWs();
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /** Map lookup that never throws. */
    public static Object get(Object o, String key) {
        Map<String, Object> m = asMap(o);
        return m == null ? null : m.get(key);
    }

    /** List index lookup that never throws. */
    public static Object at(Object o, int index) {
        List<Object> l = asList(o);
        if (l == null || index < 0 || index >= l.size()) {
            return null;
        }
        return l.get(index);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            skipWs();
            if (eof()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (!eof() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                if (eof() || s.charAt(i) != '"') {
                    throw err("expected object key");
                }
                String key = string();
                skipWs();
                if (eof() || s.charAt(i) != ':') {
                    throw err("expected ':'");
                }
                i++;
                map.put(key, value());
                skipWs();
                if (eof()) {
                    throw err("unterminated object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return map;
                }
                throw err("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (!eof() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWs();
                if (eof()) {
                    throw err("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return list;
                }
                throw err("expected ',' or ']'");
            }
        }

        private String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw err("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw err("bad \\u escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw err("bad escape \\" + e);
                }
            }
        }

        private Object number() {
            int start = i;
            if (!eof() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            while (!eof()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    i++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, i);
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException ex) {
                throw err("bad number '" + raw + "'");
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, i)) {
                throw err("expected " + literal);
            }
            i += literal.length();
        }

        private IllegalArgumentException err(String message) {
            return new IllegalArgumentException(message + " at offset " + i);
        }
    }
}

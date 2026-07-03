package gg.ginco.hytest.junit5.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser. Returns plain Java types
 * ({@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean}, {@code null}).
 * Sufficient for the simple flat objects emitted by the runtime result reporter.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String src) {
        Json parser = new Json(src);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            char c = next();
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                break;
            }
            throw error("expected ',' or '}'");
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            Object value = parseValue();
            list.add(value);
            skipWhitespace();
            char c = next();
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                break;
            }
            throw error("expected ',' or ']'");
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw error("unterminated escape");
                }
                char esc = src.charAt(pos++);
                sb.append(switch (esc) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> parseUnicodeEscape();
                    default -> throw error("invalid escape: \\" + esc);
                });
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private char parseUnicodeEscape() {
        if (pos + 4 > src.length()) {
            throw error("unterminated unicode escape");
        }
        String hex = src.substring(pos, pos + 4);
        pos += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Object parseNumber() {
        int start = pos;
        while (pos < src.length() && isNumberChar(src.charAt(pos))) {
            pos++;
        }
        String token = src.substring(start, pos);
        if (token.isEmpty()) {
            throw error("expected value");
        }
        return Double.parseDouble(token);
    }

    private boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private Object parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw error("invalid literal");
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw error("invalid literal");
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw error("expected '" + expected + "' but got '" + c + "'");
        }
    }

    private RuntimeException error(String message) {
        return new IllegalArgumentException("JSON parse error at " + pos + ": " + message);
    }
}

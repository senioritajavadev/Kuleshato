package kuleshato;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {}

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.space();
        if (!parser.done()) throw new IllegalArgumentException("Unexpected JSON content");
        return value;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String text) quote(text, out);
        else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private boolean done() {
            return index == text.length();
        }

        private void space() {
            while (!done() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private Object value() {
            space();
            if (done()) throw invalid();
            return switch (text.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            while (true) {
                space();
                if (done() || text.charAt(index) != '"') throw invalid();
                String key = string();
                space();
                if (!take(':')) throw invalid();
                result.put(key, value());
                space();
                if (take('}')) return result;
                if (!take(',')) throw invalid();
            }
        }

        private List<Object> array() {
            index++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                space();
                if (take(']')) return result;
                if (!take(',')) throw invalid();
            }
        }

        private String string() {
            index++;
            StringBuilder result = new StringBuilder();
            while (!done()) {
                char c = text.charAt(index++);
                if (c == '"') return result.toString();
                if (c == '\\') {
                    if (done()) throw invalid();
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) throw invalid();
                            try {
                                result.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            } catch (NumberFormatException error) {
                                throw invalid();
                            }
                            index += 4;
                        }
                        default -> throw invalid();
                    }
                } else {
                    if (c < 0x20) throw invalid();
                    result.append(c);
                }
            }
            throw invalid();
        }

        private Object number() {
            int start = index;
            if (take('-')) {}
            digits();
            boolean fractional = take('.');
            if (fractional) digits();
            if (take('e') || take('E')) {
                fractional = true;
                if (!take('+')) take('-');
                digits();
            }
            if (start == index) throw invalid();
            try {
                String number = text.substring(start, index);
                if (fractional) return new BigDecimal(number);
                try {
                    return Long.valueOf(number);
                } catch (NumberFormatException ignored) {
                    return new BigInteger(number);
                }
            } catch (NumberFormatException error) {
                throw invalid();
            }
        }

        private void digits() {
            int start = index;
            while (!done() && Character.isDigit(text.charAt(index))) index++;
            if (start == index) throw invalid();
        }

        private Object literal(String spelling, Object value) {
            if (!text.startsWith(spelling, index)) throw invalid();
            index += spelling.length();
            return value;
        }

        private boolean take(char expected) {
            if (!done() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid JSON at character " + index);
        }
    }
}

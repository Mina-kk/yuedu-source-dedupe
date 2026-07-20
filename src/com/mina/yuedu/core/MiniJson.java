package com.mina.yuedu.core;
import java.io.*;
import java.util.*;

public final class MiniJson {
    public interface ObjectConsumer {
        void accept(Map<String, Object> object) throws Exception;
    }

    private final String s;
    private int p;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String s) {
        MiniJson j = new MiniJson(s);
        Object v = j.value();
        j.ws();
        if (j.p != s.length()) throw new IllegalArgumentException("trailing JSON");
        return v;
    }

    public static void parseArrayStream(Reader reader, ObjectConsumer consumer) throws Exception {
        StringBuilder sb = new StringBuilder(Math.max(8192, 64));
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
        parseArrayStream(sb.toString(), consumer);
    }

    public static void parseArrayStream(String json, ObjectConsumer consumer) throws Exception {
        MiniJson j = new MiniJson(json);
        j.ws();
        j.need('[');
        j.ws();
        if (j.ch(']')) return;
        do {
            Object value = j.value();
            if (!(value instanceof Map)) {
                throw new IllegalArgumentException("array item is not object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            consumer.accept(map);
            j.ws();
        } while (j.ch(','));
        j.need(']');
        j.ws();
        if (j.p != json.length()) throw new IllegalArgumentException("trailing JSON");
    }

    private Object value() {
        ws();
        if (p >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(p);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't' && take("true")) return true;
        if (c == 'f' && take("false")) return false;
        if (c == 'n' && take("null")) return null;
        return number();
    }

    private Map<String, Object> object() {
        p++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (ch('}')) return m;
        do {
            ws();
            String k = string();
            ws();
            need(':');
            m.put(k, value());
            ws();
        } while (ch(','));
        need('}');
        return m;
    }

    private List<Object> array() {
        p++;
        List<Object> a = new ArrayList<>();
        ws();
        if (ch(']')) return a;
        do {
            a.add(value());
            ws();
        } while (ch(','));
        need(']');
        return a;
    }

    private String string() {
        need('"');
        StringBuilder b = new StringBuilder();
        while (p < s.length()) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (p >= s.length()) throw new IllegalArgumentException("escape");
                char e = s.charAt(p++);
                if (e == 'u') {
                    int cp = Integer.parseInt(s.substring(p, p + 4), 16);
                    p += 4;
                    b.append((char) cp);
                } else {
                    String x = "\"\\/bfnrt";
                    String y = "\"\\/\b\f\n\r\t";
                    int i = x.indexOf(e);
                    if (i < 0) throw new IllegalArgumentException("escape " + Integer.toHexString(e) + " at " + (p - 1));
                    b.append(y.charAt(i));
                }
            } else b.append(c);
        }
        throw new IllegalArgumentException("string");
    }

    private Number number() {
        int b = p;
        while (p < s.length() && "-+0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        String n = s.substring(b, p);
        return n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0 ? Double.valueOf(n) : Long.valueOf(n);
    }

    private boolean take(String x) {
        if (s.startsWith(x, p)) {
            p += x.length();
            return true;
        }
        return false;
    }

    private void ws() {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
    }

    private boolean ch(char c) {
        if (p < s.length() && s.charAt(p) == c) {
            p++;
            return true;
        }
        return false;
    }

    private void need(char c) {
        if (!ch(c)) throw new IllegalArgumentException("expected " + c + " at " + p);
    }

    public static String stringify(Object v) {
        StringBuilder b = new StringBuilder();
        try {
            writeValue(b, v);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toString();
    }

    public static void writeValue(Appendable out, Object v) throws IOException {
        if (v == null) {
            out.append("null");
            return;
        }
        if (v instanceof String) {
            quote(out, (String) v);
            return;
        }
        if (v instanceof Number || v instanceof Boolean) {
            out.append(String.valueOf(v));
            return;
        }
        if (v instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Object e0 : ((Map<?, ?>) v).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) e0;
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(e.getKey()));
                out.append(':');
                writeValue(out, e.getValue());
            }
            out.append('}');
            return;
        }
        if (v instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) v) {
                if (!first) out.append(',');
                first = false;
                writeValue(out, x);
            }
            out.append(']');
            return;
        }
        quote(out, String.valueOf(v));
    }

    private static void quote(Appendable out, String s) throws IOException {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        String h = Integer.toHexString(c);
                        out.append("\\u");
                        for (int z = h.length(); z < 4; z++) out.append('0');
                        out.append(h);
                    } else out.append(c);
            }
        }
        out.append('"');
    }
}

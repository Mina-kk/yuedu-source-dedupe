package com.mina.yuedu.core;
import java.io.*;
import java.util.*;

public final class MiniJson {
    public interface ObjectConsumer {
        void accept(Map<String, Object> object) throws Exception;
    }

    private final Reader reader;
    private final char[] buf = new char[8192];
    private int buflen;
    private int bufpos;
    private int abspos;
    private int current = -2; // -2 = unread, -1 = EOF, else char
    private boolean eof;

    private MiniJson(Reader reader) {
        this.reader = reader;
    }

    private MiniJson(String s) {
        this.reader = new StringReader(s == null ? "" : s);
    }

    public static Object parse(String s) {
        try {
            MiniJson j = new MiniJson(s);
            Object v = j.value();
            j.ws();
            if (!j.isEof()) throw new IllegalArgumentException("trailing JSON");
            return v;
        } catch (IOException e) {
            throw new IllegalArgumentException("read error: " + e.getMessage());
        }
    }

    /** True streaming parse of a top-level JSON array of objects. */
    public static void parseArrayStream(Reader reader, ObjectConsumer consumer) throws Exception {
        if (reader == null) throw new IllegalArgumentException("reader");
        MiniJson j = new MiniJson(new BufferedReader(reader, 65536));
        j.ws();
        j.need('[');
        j.ws();
        if (j.ch(']')) {
            j.ws();
            return;
        }
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
    }

    public static void parseArrayStream(String json, ObjectConsumer consumer) throws Exception {
        parseArrayStream(new StringReader(json == null ? "" : json), consumer);
    }

    private int peek() throws IOException {
        if (current != -2) return current;
        if (bufpos >= buflen) {
            if (eof) {
                current = -1;
                return -1;
            }
            buflen = reader.read(buf);
            bufpos = 0;
            if (buflen < 0) {
                eof = true;
                buflen = 0;
                current = -1;
                return -1;
            }
        }
        current = buf[bufpos];
        return current;
    }

    private int next() throws IOException {
        int c = peek();
        if (c >= 0) {
            bufpos++;
            abspos++;
            current = -2;
        }
        return c;
    }

    private boolean isEof() {
        try {
            return peek() < 0;
        } catch (IOException e) {
            return true;
        }
    }

    private Object value() {
        try {
            ws();
            int c = peek();
            if (c < 0) throw new IllegalArgumentException("unexpected end");
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && take("true")) return true;
            if (c == 'f' && take("false")) return false;
            if (c == 'n' && take("null")) return null;
            return number();
        } catch (IOException e) {
            throw new IllegalArgumentException("read error at " + abspos + ": " + e.getMessage());
        }
    }

    private Map<String, Object> object() throws IOException {
        need('{');
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

    private List<Object> array() throws IOException {
        need('[');
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

    private String string() throws IOException {
        need('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            int ci = next();
            if (ci < 0) throw new IllegalArgumentException("string");
            char c = (char) ci;
            if (c == '"') return b.toString();
            if (c == '\\') {
                int ei = next();
                if (ei < 0) throw new IllegalArgumentException("escape");
                char e = (char) ei;
                if (e == 'u') {
                    int cp = 0;
                    for (int i = 0; i < 4; i++) {
                        int h = next();
                        if (h < 0) throw new IllegalArgumentException("unicode");
                        cp = (cp << 4) + Character.digit((char) h, 16);
                        if (Character.digit((char) h, 16) < 0) throw new IllegalArgumentException("unicode");
                    }
                    b.append((char) cp);
                } else {
                    String x = "\"\\/bfnrt";
                    String y = "\"\\/\b\f\n\r\t";
                    int i = x.indexOf(e);
                    if (i < 0) throw new IllegalArgumentException("escape " + Integer.toHexString(e) + " at " + abspos);
                    b.append(y.charAt(i));
                }
            } else b.append(c);
        }
    }

    private Number number() throws IOException {
        StringBuilder n = new StringBuilder();
        while (true) {
            int c = peek();
            if (c < 0) break;
            if ("-+0123456789.eE".indexOf((char) c) < 0) break;
            n.append((char) next());
        }
        if (n.length() == 0) throw new IllegalArgumentException("number at " + abspos);
        String s = n.toString();
        return s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0
                ? Double.valueOf(s)
                : Long.valueOf(s);
    }

    private boolean take(String x) throws IOException {
        for (int i = 0; i < x.length(); i++) {
            int c = peek();
            if (c != x.charAt(i)) {
                if (i == 0) return false;
                throw new IllegalArgumentException("expected " + x + " at " + abspos);
            }
            next();
        }
        return true;
    }

    private void ws() throws IOException {
        while (true) {
            int c = peek();
            if (c < 0 || !Character.isWhitespace((char) c)) return;
            next();
        }
    }

    private boolean ch(char c) throws IOException {
        if (peek() == c) {
            next();
            return true;
        }
        return false;
    }

    private void need(char c) throws IOException {
        if (!ch(c)) throw new IllegalArgumentException("expected " + c + " at " + abspos);
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

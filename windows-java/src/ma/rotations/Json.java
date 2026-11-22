package ma.rotations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lecture / écriture JSON minimale (aucune bibliothèque externe). Objets = LinkedHashMap (ordre conservé). */
public final class Json {
    private Json() { }

    // ------------------------------------------------------------------ lecture
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("caractères en trop");
        return v;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON invalide (" + m + ") à la position " + i); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw err("fin inattendue"); return s.charAt(i); }

        Object value() {
            char c = peek();
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }
        void expect(String w) { if (!s.startsWith(w, i)) throw err("« " + w + " » attendu"); i += w.length(); }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw err("clé attendue");
                String k = string();
                ws();
                if (peek() != ':') throw err("« : » attendu");
                i++; ws();
                m.put(k, value());
                ws();
                char c = peek(); i++;
                if (c == '}') return m;
                if (c != ',') throw err("« , » ou « } » attendu");
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek(); i++;
                if (c == ']') return l;
                if (c != ',') throw err("« , » ou « ] » attendu");
            }
        }
        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                char c = peek(); i++;
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = peek(); i++;
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("\\u incomplet");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw err("échappement inconnu");
                    }
                } else b.append(c);
            }
        }
        Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw err("valeur attendue");
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }

    // ------------------------------------------------------------------ écriture
    public static String write(Object v) { StringBuilder b = new StringBuilder(); write(b, v, -1, 0); return b.toString(); }
    public static String pretty(Object v) { StringBuilder b = new StringBuilder(); write(b, v, 1, 0); return b.toString(); }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int indent, int level) {
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) b.append(v);
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) b.append((long) d); else b.append(d);
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(indent < 0 ? ", " : ",");
                first = false;
                nl(b, indent, level + 1);
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), indent, level + 1);
            }
            nl(b, indent, level);
            b.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(indent < 0 ? ", " : ",");
                nl(b, indent, level + 1);
                write(b, l.get(k), indent, level + 1);
            }
            nl(b, indent, level);
            b.append(']');
        } else quote(b, String.valueOf(v));
    }
    private static void nl(StringBuilder b, int indent, int level) {
        if (indent < 0) return;
        b.append('\n');
        for (int k = 0; k < indent * level; k++) b.append(' ');
    }
    public static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }
    public static String quote(String s) { StringBuilder b = new StringBuilder(); quote(b, s); return b.toString(); }

    // ------------------------------------------------------------------ accès pratiques
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }
    public static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }
    public static int num(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? def : Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; }
    }
}

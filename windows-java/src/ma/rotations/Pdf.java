package ma.rotations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static ma.rotations.Core.*;

/** Tableau imprimable (A4 portrait), même design que la feuille papier, la tablette et le site. Aucune bibliothèque. */
public final class Pdf {
    private Pdf() { }

    static final double MM = 72 / 25.4, W = 595.2756, H = 841.8898;
    static final double M_TOP = 14 * MM, M_BOTTOM = 12 * MM, M_LEFT = 12 * MM, M_RIGHT = 10 * MM, TITLE_H = 14 * MM, DAY_W = 11 * MM;
    static final String BLUE = rgb("#29a8e0"), GREY = rgb("#c4c7c5"), YELLOW = rgb("#f2c21b"), WHITE = "1 1 1", DARK = rgb("#1c2733");
    static final String[] PATTERN = {BLUE, GREY, WHITE, WHITE, YELLOW, WHITE, WHITE};
    static final Map<String, Map<String, Object>> WIDTHS = new HashMap<>();

    static {
        try (InputStream in = Pdf.class.getResourceAsStream("pdf_metrics.json")) {
            Map<String, Object> m = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            for (String f : m.keySet()) WIDTHS.put(f, Json.obj(m.get(f)));
        } catch (Exception e) {
            throw new IllegalStateException("métriques PDF introuvables", e);
        }
    }

    static String rgb(String hex) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < 7; i += 2) b.append(i > 1 ? " " : "").append(n(Integer.parseInt(hex.substring(i, i + 2), 16) / 255.0, 3));
        return b.toString();
    }
    static String n(double x) { return n(x, 2); }
    static String n(double x, int dec) {
        double f = Math.pow(10, dec), r = Math.round(x * f) / f;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        String s = String.format(Locale.ROOT, "%." + dec + "f", r);
        return s.replaceAll("0+$", "");
    }
    static double width(String text, String font, double size) {
        double w = 0;
        for (int i = 0; i < text.length(); i++) w += Json.num(WIDTHS.get(font).get(String.valueOf(text.charAt(i))), 556);
        return w * size / 1000;
    }
    /** Windows-1252 (WinAnsi), parenthèses et barres obliques inverses échappées. */
    static String ansi(String text) {
        StringBuilder b = new StringBuilder();
        for (char ch : text.toCharArray()) {
            int c = ch;
            if (ch == '–') c = 0x96; else if (ch == '—') c = 0x97; else if (ch == '’') c = 0x92; else if (c > 255) c = '?';
            if (c == '(' || c == ')' || c == '\\') b.append('\\');
            b.append((char) c);
        }
        return b.toString();
    }
    static String[] splitTwo(String text) {
        List<Integer> cuts = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == ' ') cuts.add(text.startsWith(" / ", i) ? i : i);
        if (cuts.isEmpty()) return null;
        double mid = text.length() / 2.0;
        int best = cuts.get(0);
        for (int c : cuts) if (Math.abs(c - mid) < Math.abs(best - mid)) best = c;
        // préférer le séparateur « / »
        for (int c : cuts) if (text.startsWith(" / ", c) && Math.abs(c - mid) <= Math.abs(best - mid) + 2) { best = c; break; }
        if (text.startsWith(" / ", best)) return new String[]{text.substring(0, best) + " /", text.substring(best + 3)};
        if (best > 0 && text.startsWith(" / ", best - 2)) best -= 2;
        return text.startsWith(" / ", best) ? new String[]{text.substring(0, best) + " /", text.substring(best + 3)}
                : new String[]{text.substring(0, best), text.substring(best + 1)};
    }

    static final Map<String, String> FONTS = Map.of("Helvetica-Bold", "F1", "Times-Roman", "F2");

    static void vtext(List<String> ops, double x, double y, double w, double h, String text, double size, String font, boolean underline) {
        if (text == null || text.isEmpty()) return;
        double avail = h - 3 * MM;
        String[] lines = width(text, font, size) > avail && width(text, font, 7) > avail ? splitTwo(text) : null;
        if (lines != null && w > 2.2 * 7) {
            double sz = Math.min(size, 8);
            while (sz > 4 && Math.max(width(lines[0], font, sz), width(lines[1], font, sz)) > avail) sz -= 0.5;
            for (int i = 0; i < 2; i++) {
                double tw = width(lines[i], font, sz), px = x + w / 2 + (i == 0 ? 0.55 : -0.55) * sz * 1.05 - sz * 0.35, py = y + h / 2 + tw / 2;
                ops.add("BT /" + FONTS.get(font) + " " + n(sz) + " Tf 0 g 0 -1 1 0 " + n(px) + " " + n(py) + " Tm (" + ansi(lines[i]) + ") Tj ET");
            }
            return;
        }
        while (size > 4 && width(text, font, size) > avail) size -= 0.5;
        double tw = width(text, font, size), cx = x + w / 2, cy = y + h / 2, px = cx - size * 0.35, py = cy + tw / 2;
        ops.add("BT /" + FONTS.get(font) + " " + n(size) + " Tf 0 g 0 -1 1 0 " + n(px) + " " + n(py) + " Tm (" + ansi(text) + ") Tj ET");
        if (underline) ops.add("0.6 w 0 G " + n(px - 1.5) + " " + n(cy + tw / 2) + " m " + n(px - 1.5) + " " + n(cy - tw / 2) + " l S");
    }
    static String st(boolean used, String s, String t) {
        if (!used || (up(s).isEmpty() && (t == null || t.isEmpty()))) return "";
        Integer v = safeTime(t);
        return up(s) + " : " + (v != null ? fmtTime(v) : (t == null ? "" : t));
    }

    static String pageOps(Aircraft ac) {
        List<String> ops = new ArrayList<>();
        double x0 = M_LEFT, yTop = H - M_TOP, tw = W - M_LEFT - M_RIGHT;
        double colW = (tw - DAY_W) / 21, dayH = (H - M_TOP - M_BOTTOM - TITLE_H) / DAYS.length, yt = yTop - TITLE_H;
        ops.add("0 G 0.6 w");
        ops.add(n(x0) + " " + n(yt) + " " + n(colW) + " " + n(TITLE_H) + " re S " + n(x0 + colW) + " " + n(yt) + " " + n(tw - colW) + " " + n(TITLE_H) + " re S");
        String title = ac.week == null ? ac.title : ac.title + "  –  " + weekShort(ac.week).toUpperCase(Locale.ROOT);
        double ts = 22;
        while (ts > 10 && width(title, "Times-Roman", ts) > tw - colW - 8) ts -= 0.5;
        ops.add("BT /F2 " + n(ts) + " Tf " + rgb("#333333") + " rg 1 0 0 1 " + n(x0 + colW + (tw - colW) / 2 - width(title, "Times-Roman", ts) / 2) + " "
                + n(yt + TITLE_H / 2 - ts * 0.32) + " Tm (" + ansi(title) + ") Tj ET");
        int wk = weekTotal(ac);
        if (wk != 0) vtext(ops, x0, yt, colW, TITLE_H, fmtDuration(wk), 7, "Helvetica-Bold", false);
        for (int di = 0; di < DAYS.length; di++) {
            double y = yt - (di + 1) * dayH;
            DayCalc dc = computeDay(ac.days.get(di));
            int[] order = {2, 1, 0};
            for (int bi = 0; bi < 3; bi++) {
                int ri = order[bi];
                Rotation r = ac.days.get(di).get(ri);
                RotationCalc c = dc.rotations.get(ri);
                boolean u = r.isUsed();
                String[] txt = new String[7];
                boolean[] big = new boolean[7];
                if (ri == 2) { txt[0] = dc.total != 0 ? fmtDuration(dc.total) : ""; big[0] = true; }
                else txt[0] = c.ecart != null ? "Escale " + c.ecart + "mn" : "";
                txt[1] = c.duree != null ? fmtDuration(c.duree) : ""; big[1] = true;
                txt[2] = st(u, r.arrivee, r.hArrivee);
                txt[3] = st(u, r.escale, r.hRedepart);
                txt[4] = c.escale != null ? "Escale " + c.escale + "mn" : "";
                if (!up(r.remarque).isEmpty()) { txt[5] = up(r.remarque); big[5] = true; } else txt[5] = st(u, r.escale, r.hArriveeEscale);
                txt[6] = st(u, r.depart, r.hDepart);
                for (int k = 0; k < 7; k++) {
                    double x = x0 + (bi * 7 + k) * colW;
                    ops.add(PATTERN[k] + " rg 0 G 0.6 w " + n(x) + " " + n(y) + " " + n(colW) + " " + n(dayH) + " re B");
                    vtext(ops, x, y, colW, dayH, txt[k], big[k] ? 10 : 8.5, "Helvetica-Bold", ri == 2 && k == 0 && !txt[k].isEmpty());
                }
            }
            double xd = x0 + 21 * colW;
            ops.add("1 1 1 rg 0 G 0.6 w " + n(xd) + " " + n(y) + " " + n(DAY_W) + " " + n(dayH) + " re B");
            vtext(ops, xd, y, DAY_W, dayH, dayLabel(ac.week, di), 12, "Times-Roman", false);
        }
        double yBot = yt - DAYS.length * dayH;
        ops.add(DARK + " RG 2.2 w");
        for (int b = 0; b < 3; b++) { double xx = x0 + b * 7 * colW; ops.add(n(xx) + " " + n(yt) + " m " + n(xx) + " " + n(yBot) + " l S"); }
        for (double xx : new double[]{x0 + 21 * colW, x0 + tw}) ops.add(n(xx) + " " + n(yt) + " m " + n(xx) + " " + n(yBot) + " l S");
        ops.add("0 G 0.8 w " + n(x0) + " " + n(yBot) + " m " + n(x0 + tw) + " " + n(yBot) + " l S");
        return String.join("\n", ops);
    }

    /** PDF (une page par avion). */
    public static byte[] build(List<Aircraft> list, String title) {
        List<String> objs = new ArrayList<>();
        objs.add(""); objs.add("");                                   // 1 catalogue, 2 pages
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>");
        List<Integer> kids = new ArrayList<>();
        for (Aircraft ac : list) {
            String content = pageOps(ac);
            objs.add("<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream");
            int cid = objs.size();
            objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + n(W) + " " + n(H) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + cid + " 0 R >>");
            kids.add(objs.size());
        }
        objs.set(0, "<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder k = new StringBuilder();
        for (int id : kids) k.append(k.length() > 0 ? " " : "").append(id).append(" 0 R");
        objs.set(1, "<< /Type /Pages /Kids [" + k + "] /Count " + kids.size() + " >>");
        StringBuilder t = new StringBuilder("<FEFF");
        for (char ch : title.toCharArray()) t.append(String.format("%04X", (int) ch));
        objs.add("<< /Title " + t + "> /Producer (Programme des Rotations) >>");
        int info = objs.size();
        StringBuilder out = new StringBuilder("%PDF-1.4\n%âãÏÓ\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objs.size(); i++) { offsets.add(out.length()); out.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n"); }
        int xref = out.length();
        out.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (int o : offsets) out.append(String.format("%010d 00000 n \n", o));
        out.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R /Info ").append(info).append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        byte[] b = new byte[out.length()];
        for (int i = 0; i < out.length(); i++) b[i] = (byte) out.charAt(i);   // octets latin-1
        return b;
    }
    public static void write(Path path, List<Aircraft> list, String title) throws IOException { Files.write(path, build(list, title)); }
}

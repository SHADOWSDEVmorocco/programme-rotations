package ma.rotations;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Modèle de données et calculs du programme des rotations (identique au logiciel Python, à la tablette et au site). */
public final class Core {
    private Core() { }

    public static final String[] DAYS = {"LUNDI", "MARDI", "MERCREDI", "JEUDI", "VENDREDI", "SAMEDI", "DIMANCHE"};
    public static final int ROTATIONS_PER_DAY = 3;
    public static final int SCHEMA_VERSION = 2;
    public static final List<String> DEFAULT_STATIONS = List.of("DAKHLA", "LAAYOUNE", "AGADIR", "GUELMIM", "SMARA",
            "MARRAKECH", "OUARZAZATE", "CASABLANCA", "LAS PALMAS", "TENERIFE", "NOUADHIBOU", "NOUAKCHOTT", "DAKAR", "BAMAKO");
    public static final String[] FIELDS = {"depart", "h_depart", "escale", "h_arrivee_escale", "h_redepart", "arrivee", "h_arrivee", "remarque"};
    public static final List<String> TIME_CHAIN = List.of("h_depart", "h_arrivee_escale", "h_redepart", "h_arrivee");

    // ================================================================== heures
    private static final Pattern TIME = Pattern.compile("^\\s*(\\d{1,2})\\s*[:hH.]?\\s*(\\d{2})?\\s*$");

    /** « 07:00 », « 0700 », « 7h00 », « 7 » -> minutes. Vide -> null. Invalide -> IllegalArgumentException. */
    public static Integer parseTime(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String s = text.trim();
        if (s.matches("\\d{3}")) s = "0" + s;
        Matcher m = TIME.matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("Heure invalide : « " + text + " »");
        int h = Integer.parseInt(m.group(1)), mn = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || mn > 59) throw new IllegalArgumentException("Heure invalide : « " + text + " »");
        return h * 60 + mn;
    }
    public static Integer safeTime(String t) { try { return parseTime(t); } catch (IllegalArgumentException e) { return null; } }
    public static String fmtTime(int m) { m = ((m % 1440) + 1440) % 1440; return String.format("%02d:%02d", m / 60, m % 60); }
    public static String fmtDuration(int m) { String s = m < 0 ? "-" : ""; m = Math.abs(m); return s + String.format("%02d:%02d", m / 60, m % 60); }
    public static String normalizeTime(String t) { Integer v = parseTime(t); return v == null ? "" : fmtTime(v); }
    public static int elapsed(int a, int b) { int d = b - a; return d < 0 ? d + 1440 : d; }
    public static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    /** « 90 », « 90 mn », « 75min », « 1:30 », « 1h30 » -> minutes. */
    public static Integer parseMinutes(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String s = text.trim().toLowerCase(Locale.ROOT).replace("min", "").replace("mn", "").replace("m", "").trim();
        if (s.matches("-?\\d{1,3}")) return Integer.parseInt(s);
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[:h]\\s*(\\d{2})").matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        if (s.matches("\\d{1,2}\\s*h")) return Integer.parseInt(s.replaceAll("\\D", "")) * 60;     // « 1h » = 60 mn
        throw new IllegalArgumentException("Durée invalide : « " + text + " »");
    }

    // ================================================================== modèle
    public static final class Rotation {
        public String depart = "", hDepart = "", escale = "", hArriveeEscale = "", hRedepart = "", arrivee = "", hArrivee = "", remarque = "";

        public Rotation() { }
        public Rotation(String... v) { for (int k = 0; k < v.length && k < 8; k++) set(FIELDS[k], v[k]); }

        public String get(String f) {
            switch (f) {
                case "depart": return depart;
                case "h_depart": return hDepart;
                case "escale": return escale;
                case "h_arrivee_escale": return hArriveeEscale;
                case "h_redepart": return hRedepart;
                case "arrivee": return arrivee;
                case "h_arrivee": return hArrivee;
                case "remarque": return remarque;
                default: throw new IllegalArgumentException(f);
            }
        }
        public void set(String f, String v) {
            v = v == null ? "" : v;
            switch (f) {
                case "depart": depart = v; break;
                case "h_depart": hDepart = v; break;
                case "escale": escale = v; break;
                case "h_arrivee_escale": hArriveeEscale = v; break;
                case "h_redepart": hRedepart = v; break;
                case "arrivee": arrivee = v; break;
                case "h_arrivee": hArrivee = v; break;
                case "remarque": remarque = v; break;
                default: throw new IllegalArgumentException(f);
            }
        }
        /** Contient des vols (pas seulement une remarque). */
        public boolean hasFlight() {
            for (String f : new String[]{"depart", "escale", "arrivee", "h_depart", "h_arrivee_escale", "h_redepart", "h_arrivee"})
                if (!up(get(f)).isEmpty()) return true;
            return false;
        }
        public boolean isUsed() {
            for (String f : new String[]{"depart", "escale", "arrivee", "h_depart", "h_arrivee", "remarque"})
                if (!up(get(f)).isEmpty()) return true;
            return false;
        }
        public Rotation copy() { Rotation r = new Rotation(); for (String f : FIELDS) r.set(f, get(f)); return r; }
    }

    public static final class Aircraft {
        public String title, base;
        public List<List<Rotation>> days;
        public Map<String, Integer> durations = new LinkedHashMap<>();
        public int escaleMin = 45, baseMin = 45;
        /** Semaines datées : lundi (AAAA-MM-JJ) -> 7 jours. Le « programme type » (sans date) est dans days. */
        public Map<String, List<List<Rotation>>> weeks = new TreeMap<>();
        /** Pour une vue d'une semaine datée : l'avion d'origine et la semaine (null = programme type). */
        public Aircraft origin;
        public String week;
        public Aircraft(String title, String base) { this(title, base, emptyWeek()); }
        public Aircraft(String title, String base, List<List<Rotation>> days) { this.title = title; this.base = base; this.days = days; }
    }

    public static final class Settings {
        public int escaleDefaut = 45, escaleBase = 60;
        public String maxJour = "13:00";
    }

    public static final class Program {
        public List<Aircraft> aircraft = new ArrayList<>();
        public Settings settings = new Settings();
        public List<String> defaultsSeen = new ArrayList<>();
        public List<String> stations = new ArrayList<>(DEFAULT_STATIONS);
        public int rev = 0;
    }

    public static List<Rotation> emptyDay() { List<Rotation> d = new ArrayList<>(); for (int k = 0; k < ROTATIONS_PER_DAY; k++) d.add(new Rotation()); return d; }
    public static List<List<Rotation>> emptyWeek() { List<List<Rotation>> w = new ArrayList<>(); for (int k = 0; k < DAYS.length; k++) w.add(emptyDay()); return w; }
    public static List<Rotation> copyDay(List<Rotation> d) { List<Rotation> o = new ArrayList<>(); for (Rotation r : d) o.add(r.copy()); return o; }

    // ================================================================== calculs
    public static final class RotationCalc { public Integer escale, duree, ecart; public int bloc; }
    public static final class DayCalc { public List<RotationCalc> rotations = new ArrayList<>(); public int total, bloc; }

    public static DayCalc computeDay(List<Rotation> rots) {
        DayCalc dc = new DayCalc();
        for (Rotation r : rots) {
            RotationCalc c = new RotationCalc();
            Integer a = safeTime(r.hArriveeEscale), b = safeTime(r.hRedepart), d = safeTime(r.hDepart), f = safeTime(r.hArrivee);
            if (a != null && b != null) c.escale = elapsed(a, b);
            if (d != null && f != null) c.duree = elapsed(d, f);
            if (d != null && a != null) c.bloc += elapsed(d, a);
            if (b != null && f != null) c.bloc += elapsed(b, f);
            dc.rotations.add(c);
        }
        for (int i = 0; i < rots.size(); i++) {
            Rotation n = null;
            for (int j = i + 1; j < rots.size(); j++) if (rots.get(j).hasFlight()) { n = rots.get(j); break; }
            if (n != null) {
                Integer f = safeTime(rots.get(i).hArrivee), d = safeTime(n.hDepart);
                if (f != null && d != null) dc.rotations.get(i).ecart = d - f;
            }
        }
        for (RotationCalc c : dc.rotations) { dc.total += c.duree == null ? 0 : c.duree; dc.bloc += c.bloc; }
        return dc;
    }
    public static int weekTotal(Aircraft ac) { int t = 0; for (List<Rotation> d : ac.days) t += computeDay(d).total; return t; }
    public static int weekBloc(Aircraft ac) { int t = 0; for (List<Rotation> d : ac.days) t += computeDay(d).bloc; return t; }
    public static String family(String title) { String[] p = up(title).split("\\s+"); return p.length > 0 ? p[0] : ""; }

    // ================================================================== durées de vol
    public static int learnDurations(Program prog, Aircraft only) {
        int n = 0;
        for (Aircraft a : only != null ? List.of(only) : prog.aircraft) {
            Map<String, List<Integer>> acc = new LinkedHashMap<>();
            for (List<Rotation> day : a.days) for (Rotation r : day) {
                addLeg(acc, r.depart, r.escale, r.hDepart, r.hArriveeEscale);
                addLeg(acc, r.escale, r.arrivee, r.hRedepart, r.hArrivee);
            }
            for (Map.Entry<String, List<Integer>> e : acc.entrySet()) {
                double avg = e.getValue().stream().mapToInt(Integer::intValue).sum() / (double) e.getValue().size();
                a.durations.put(e.getKey(), (int) Math.rint(avg / 5.0) * 5);       // arrondi « bancaire » comme Python
            }
            n += acc.size();
        }
        return n;
    }
    private static void addLeg(Map<String, List<Integer>> acc, String x, String y, String t1, String t2) {
        Integer a = safeTime(t1), b = safeTime(t2);
        if (!up(x).isEmpty() && !up(y).isEmpty() && a != null && b != null)
            acc.computeIfAbsent(up(x) + ">" + up(y), k -> new ArrayList<>()).add(elapsed(a, b));
    }
    public static Integer getDuration(Program prog, Aircraft ac, String key) {
        if (ac.durations.containsKey(key)) return ac.durations.get(key);
        for (Aircraft o : prog.aircraft)
            if (o != ac && family(o.title).equals(family(ac.title)) && o.durations.containsKey(key)) return o.durations.get(key);
        return null;
    }

    // ================================================================== profil appris (propositions)
    public static final class Profile {
        public Map<String, Integer> escaleByStation = new LinkedHashMap<>();
        public Map<String, String> routeArrival = new LinkedHashMap<>();
        public Map<String, List<String>> destinations = new LinkedHashMap<>();
        public List<String> stations = new ArrayList<>();
        public String firstDeparture;
        public Integer baseGap;
        public List<String> remarks = new ArrayList<>();
    }
    private static <T> T mode(List<T> values) {
        if (values.isEmpty()) return null;
        Map<T, Integer> c = new HashMap<>();
        for (T v : values) c.merge(v, 1, Integer::sum);
        T best = null; int bc = -1;
        for (T v : values) if (c.get(v) > bc) { best = v; bc = c.get(v); }
        return best;
    }
    private static List<String> byFreq(List<String> l) {
        Map<String, Integer> c = new HashMap<>();
        for (String v : l) c.merge(v, 1, Integer::sum);
        List<String> out = new ArrayList<>(new LinkedHashSet<>(l));
        out.sort((x, y) -> c.get(y).equals(c.get(x)) ? x.compareTo(y) : c.get(y) - c.get(x));
        return out;
    }

    public static Profile profile(Program prog, Aircraft ac) {
        String fam = family(ac.title);
        List<Aircraft> pool = new ArrayList<>(List.of(ac)), all = new ArrayList<>();
        for (Aircraft a : prog.aircraft) if (a != ac && family(a.title).equals(fam)) pool.add(a);
        all.addAll(pool);
        for (Aircraft a : prog.aircraft) if (!pool.contains(a)) all.add(a);
        Map<String, List<Integer>> esc = new LinkedHashMap<>();
        Map<String, List<String>> routes = new LinkedHashMap<>(), dests = new LinkedHashMap<>();
        Map<String, Integer> freq = new LinkedHashMap<>();
        List<String> firsts = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        Profile p = new Profile();
        for (Aircraft a : all) {
            boolean own = pool.contains(a);
            for (List<Rotation> day : a.days) {
                DayCalc dc = computeDay(day);
                int k = 0;
                for (int i = 0; i < day.size(); i++) {
                    Rotation r = day.get(i);
                    if (!r.isUsed()) continue;
                    RotationCalc c = dc.rotations.get(i);
                    for (String st : new String[]{r.depart, r.escale, r.arrivee})
                        if (!up(st).isEmpty()) freq.merge(up(st), own ? 2 : 1, Integer::sum);
                    if (!up(r.remarque).isEmpty() && !p.remarks.contains(up(r.remarque))) p.remarks.add(up(r.remarque));
                    if (own) {
                        if (!up(r.escale).isEmpty() && c.escale != null && c.escale >= 0 && c.escale <= 240)
                            esc.computeIfAbsent(up(r.escale), x -> new ArrayList<>()).add(c.escale);
                        if (!up(r.depart).isEmpty() && !up(r.escale).isEmpty()) {
                            dests.computeIfAbsent(up(r.depart), x -> new ArrayList<>()).add(up(r.escale));
                            if (!up(r.arrivee).isEmpty())
                                routes.computeIfAbsent(up(r.depart) + ">" + up(r.escale), x -> new ArrayList<>()).add(up(r.arrivee));
                        }
                        if (k == 0 && safeTime(r.hDepart) != null) firsts.add(fmtTime(safeTime(r.hDepart)));
                        if (c.ecart != null && c.ecart >= 0 && c.ecart <= 120) gaps.add(c.ecart);
                    }
                    k++;
                }
            }
        }
        esc.forEach((key, v) -> p.escaleByStation.put(key, mode(v)));
        routes.forEach((key, v) -> p.routeArrival.put(key, mode(v)));
        dests.forEach((key, v) -> p.destinations.put(key, byFreq(v)));
        List<String> st = new ArrayList<>(freq.keySet());
        st.sort((x, y) -> freq.get(y).equals(freq.get(x)) ? x.compareTo(y) : freq.get(y) - freq.get(x));
        p.stations.addAll(st);
        TreeSet<String> extra = new TreeSet<>();
        for (String s : prog.stations) if (!up(s).isEmpty() && !freq.containsKey(up(s))) extra.add(up(s));
        p.stations.addAll(extra);
        p.firstDeparture = mode(firsts);
        p.baseGap = mode(gaps);
        return p;
    }

    static Rotation prevUsed(List<Rotation> rots, int ri) {
        for (int j = ri - 1; j >= 0; j--) if (rots.get(j).hasFlight()) return rots.get(j);
        return null;
    }

    /** Propositions pour une case, la plus probable en premier. */
    public static List<String> suggest(Program prog, Aircraft ac, int di, int ri, String attr) {
        Profile prof = profile(prog, ac);
        List<Rotation> rots = ac.days.get(di);
        Rotation r = rots.get(ri), prev = prevUsed(rots, ri);
        String base = up(ac.base);
        String dep = !up(r.depart).isEmpty() ? up(r.depart) : (prev != null ? up(prev.arrivee) : base);
        List<String> out = new ArrayList<>();
        switch (attr) {
            case "depart": out.add(prev != null ? up(prev.arrivee) : base); out.add(base); break;
            case "escale": {
                out.addAll(prof.destinations.getOrDefault(dep, List.of()));
                for (String s : prof.stations) if (!out.contains(s)) out.add(s);
                // heure de départ prévue : saisie, sinon arrivée précédente + escale base, sinon 1er départ habituel
                Integer t = safeTime(r.hDepart);
                if (t == null && prev != null && safeTime(prev.hArrivee) != null) t = safeTime(prev.hArrivee) + (prof.baseGap != null ? prof.baseGap : prog.settings.escaleBase);
                if (t == null && prev == null && prof.firstDeparture != null) t = safeTime(prof.firstDeparture);
                Set<String> busy = busyDestinations(prog, ac, di, t);
                List<String> free = new ArrayList<>(), taken = new ArrayList<>();
                for (String x : out) (busy.contains(x) ? taken : free).add(x);
                out = new ArrayList<>(free); out.addAll(taken);
                break;
            }
            case "arrivee": out.add(prof.routeArrival.getOrDefault(dep + ">" + up(r.escale), base)); out.add(base); break;
            case "remarque": {
                LinkedHashSet<String> s = new LinkedHashSet<>(prof.remarks);
                s.addAll(List.of("MAINTENANCE", "CHARTER", "ANNULÉ", "RÉSERVE"));
                return new ArrayList<>(s);
            }
            case "escale_mn": {
                LinkedHashSet<String> s = new LinkedHashSet<>();
                Integer e = prof.escaleByStation.get(up(r.escale));
                if (e != null) s.add(e + " mn");
                for (int v : new int[]{ac.escaleMin, 30, 45, 60, 75, 90}) s.add(v + " mn");
                return new ArrayList<>(s);
            }
            case "ecart": {
                LinkedHashSet<String> s = new LinkedHashSet<>();
                if (prof.baseGap != null) s.add(prof.baseGap + " mn");
                for (int v : new int[]{prog.settings.escaleBase, ac.baseMin, 45, 60, 75, 90, 120}) s.add(v + " mn");
                return new ArrayList<>(s);
            }
            default:
                if (TIME_CHAIN.contains(attr)) {
                    Aircraft tmp = new Aircraft(ac.title, ac.base, new ArrayList<>());
                    for (List<Rotation> d : ac.days) tmp.days.add(copyDay(d));
                    tmp.durations = ac.durations;
                    tmp.days.get(di).get(ri).set(attr, "");
                    complete(prog, tmp, di, ri, prof, new ArrayList<>(), ac);
                    String v = tmp.days.get(di).get(ri).get(attr);
                    return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(v));
                }
        }
        if (attr.equals("depart") || attr.equals("escale") || attr.equals("arrivee"))
            for (String s : prof.stations) if (!out.contains(s)) out.add(s);
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String s : out) if (s != null && !s.isEmpty()) uniq.add(s);
        return new ArrayList<>(uniq);
    }

    // ================================================================== remplissage automatique
    static void complete(Program prog, Aircraft ac, int di, int ri, Profile prof, List<String> missing, Aircraft durAc) {
        Settings s = prog.settings;
        List<Rotation> rots = ac.days.get(di);
        Rotation r = rots.get(ri), prev = prevUsed(rots, ri);
        String label = DAYS[di] + " rot. " + (ri + 1);
        if (up(r.escale).isEmpty()) {
            if (r.isUsed() && up(r.remarque).isEmpty()) missing.add(label + " : escale manquante");
            return;
        }
        if (up(r.depart).isEmpty()) r.depart = prev != null && !up(prev.arrivee).isEmpty() ? up(prev.arrivee) : up(ac.base);
        if (up(r.arrivee).isEmpty()) {
            String a = prof.routeArrival.get(up(r.depart) + ">" + up(r.escale));
            r.arrivee = a != null ? a : up(ac.base);
        }
        if (safeTime(r.hDepart) == null) {
            if (prev != null && safeTime(prev.hArrivee) != null)
                r.hDepart = fmtTime(safeTime(prev.hArrivee) + (prof.baseGap != null ? prof.baseGap : s.escaleBase));
            else if (prev == null && prof.firstDeparture != null) r.hDepart = prof.firstDeparture;
        }
        if (safeTime(r.hDepart) == null) { missing.add(label + " : heure de départ manquante"); return; }
        Integer d1 = getDuration(prog, durAc, up(r.depart) + ">" + up(r.escale));
        Integer d2 = getDuration(prog, durAc, up(r.escale) + ">" + up(r.arrivee));
        if (safeTime(r.hArriveeEscale) == null) {
            if (d1 != null) r.hArriveeEscale = fmtTime(safeTime(r.hDepart) + d1);
            else missing.add("Durée de vol inconnue : " + up(r.depart) + " → " + up(r.escale));
        }
        if (safeTime(r.hRedepart) == null && safeTime(r.hArriveeEscale) != null) {
            Integer e = prof.escaleByStation.get(up(r.escale));
            r.hRedepart = fmtTime(safeTime(r.hArriveeEscale) + (e != null ? e : s.escaleDefaut));
        }
        if (safeTime(r.hArrivee) == null && safeTime(r.hRedepart) != null) {
            if (d2 != null) r.hArrivee = fmtTime(safeTime(r.hRedepart) + d2);
            else missing.add("Durée de vol inconnue : " + up(r.escale) + " → " + up(r.arrivee));
        }
    }
    public static List<String> completeRotation(Program prog, Aircraft ac, int di, int ri) {
        List<String> m = new ArrayList<>();
        complete(prog, ac, di, ri, profile(prog, ac), m, ac);
        return m;
    }
    public static List<String> autocomplete(Program prog, Aircraft ac) {
        Profile prof = profile(prog, ac);
        List<String> m = new ArrayList<>();
        for (int di = 0; di < DAYS.length; di++) for (int ri = 0; ri < ROTATIONS_PER_DAY; ri++) complete(prog, ac, di, ri, prof, m, ac);
        return new ArrayList<>(new LinkedHashSet<>(m));
    }
    private static void shift(Rotation r, List<String> keys, int delta) {
        for (String k : keys) { Integer t = safeTime(r.get(k)); if (t != null) r.set(k, fmtTime(t + delta)); }
    }

    /** Modification d'une case + mise à jour intelligente de la rotation (cf. logiciel Python). */
    public static List<String> applyChange(Program prog, Aircraft ac, int di, int ri, String attr, String value) {
        Rotation r = ac.days.get(di).get(ri);
        String old = r.get(attr);
        r.set(attr, value);
        if (attr.equals("escale") && !up(value).equals(up(old))) {
            if (!up(old).isEmpty()) r.arrivee = "";
            r.hArriveeEscale = r.hRedepart = r.hArrivee = "";
        } else if (attr.equals("arrivee") && !up(value).equals(up(old))) r.hArrivee = "";
        else if (attr.equals("depart") && !up(value).equals(up(old)) && !up(old).isEmpty()) r.hArriveeEscale = r.hRedepart = r.hArrivee = "";
        else if (TIME_CHAIN.contains(attr)) {
            Integer o = safeTime(old), n = safeTime(value);
            if (o != null && n != null && !o.equals(n)) shift(r, TIME_CHAIN.subList(TIME_CHAIN.indexOf(attr) + 1, 4), n - o);
        }
        if (up(r.escale).isEmpty()) return new ArrayList<>();
        return completeRotation(prog, ac, di, ri);
    }
    public static void setEscale(Program prog, Aircraft ac, int di, int ri, int minutes) {
        Rotation r = ac.days.get(di).get(ri);
        Integer a = safeTime(r.hArriveeEscale);
        if (a == null) return;
        Integer old = safeTime(r.hRedepart);
        r.hRedepart = fmtTime(a + minutes);
        if (old != null) shift(r, List.of("h_arrivee"), a + minutes - old); else completeRotation(prog, ac, di, ri);
    }
    public static void setGap(Program prog, Aircraft ac, int di, int ri, int minutes) {
        List<Rotation> rots = ac.days.get(di);
        Integer t = safeTime(rots.get(ri).hArrivee);
        int nj = -1;
        for (int j = ri + 1; j < rots.size(); j++) if (rots.get(j).isUsed()) { nj = j; break; }
        if (t == null || nj < 0) return;
        Rotation n = rots.get(nj);
        Integer old = safeTime(n.hDepart);
        n.hDepart = fmtTime(t + minutes);
        if (old != null) shift(n, TIME_CHAIN.subList(1, 4), t + minutes - old);
        completeRotation(prog, ac, di, nj);
    }
    public static List<String[]> missingDurations(Program prog, Aircraft ac, int di, int ri) {
        Rotation r = ac.days.get(di).get(ri);
        List<String[]> out = new ArrayList<>();
        for (String[] leg : new String[][]{{r.depart, r.escale}, {r.escale, r.arrivee}})
            if (!up(leg[0]).isEmpty() && !up(leg[1]).isEmpty() && getDuration(prog, ac, up(leg[0]) + ">" + up(leg[1])) == null)
                out.add(new String[]{up(leg[0]), up(leg[1])});
        return out;
    }

    // ================================================================== conflits entre avions du même type
    /** Jours d'un avion pour la même semaine que « ref » (sans créer de semaine). */
    static List<List<Rotation>> sameWeekDays(Aircraft o, String week) {
        return week == null ? original(o).days : original(o).weeks.get(week);
    }
    static Set<String> destinations(Rotation r, String base) {
        Set<String> d = new LinkedHashSet<>();
        if (!up(r.escale).isEmpty()) d.add(up(r.escale));
        if (!up(r.arrivee).isEmpty() && !up(r.arrivee).equals(up(base))) d.add(up(r.arrivee));
        return d;
    }
    static int[] window(Rotation r) {
        Integer s = safeTime(r.hDepart), e = safeTime(r.hArrivee);
        if (s == null || e == null) return null;
        return new int[]{s, e < s ? e + 1440 : e};
    }
    /**
     * Règle : deux avions du même type (même début de nom, ex. « ERJ.145 », « BEECHCRAFT ») ne vont pas
     * à la même destination au même moment le même jour. Renvoie {avion, destination, départ, arrivée} pour chaque conflit.
     */
    public static List<String[]> conflicts(Program prog, Aircraft ac, int di, int ri) {
        List<String[]> out = new ArrayList<>();
        Rotation r = ac.days.get(di).get(ri);
        int[] w = window(r);
        if (!r.hasFlight() || w == null) return out;
        Set<String> dest = destinations(r, ac.base);
        for (Aircraft o : prog.aircraft) {
            if (original(o) == original(ac) || !family(o.title).equals(family(ac.title))) continue;
            List<List<Rotation>> days = sameWeekDays(o, ac.week);
            if (days == null) continue;
            for (Rotation q : days.get(di)) {
                int[] v = window(q);
                if (!q.hasFlight() || v == null || !(w[0] < v[1] && v[0] < w[1])) continue;
                for (String d : destinations(q, o.base))
                    if (dest.contains(d)) { out.add(new String[]{o.title, d, fmtTime(v[0]), fmtTime(v[1])}); break; }
            }
        }
        return out;
    }
    /** Destinations déjà prises par un avion du même type vers l'heure « t » (±90 mn au départ). */
    static Set<String> busyDestinations(Program prog, Aircraft ac, int di, Integer t) {
        Set<String> busy = new HashSet<>();
        if (t == null) return busy;
        for (Aircraft o : prog.aircraft) {
            if (original(o) == original(ac) || !family(o.title).equals(family(ac.title))) continue;
            List<List<Rotation>> days = sameWeekDays(o, ac.week);
            if (days == null) continue;
            for (Rotation q : days.get(di)) {
                int[] v = window(q);
                if (v != null && (Math.abs(v[0] - t) <= 90 || (t >= v[0] && t < v[1]))) busy.addAll(destinations(q, o.base));
            }
        }
        return busy;
    }

    // ================================================================== compteur de fréquences
    /** Fréquences par destination : nombre de rotations qui desservent la destination, par jour (0-6) et total (7). */
    public static final class Frequencies {
        public final Map<String, int[]> counts = new LinkedHashMap<>();
        public final Map<String, List<List<String>>> details = new LinkedHashMap<>();
        public final int[] totals = new int[8];
    }
    public static Frequencies frequencies(List<Aircraft> list) {
        Map<String, int[]> c = new HashMap<>();
        Map<String, List<List<String>>> det = new HashMap<>();
        for (Aircraft a : list)
            for (int di = 0; di < DAYS.length; di++)
                for (Rotation r : a.days.get(di)) {
                    if (!r.hasFlight()) continue;
                    for (String d : destinations(r, a.base)) {
                        int[] v = c.computeIfAbsent(d, k -> new int[8]);
                        v[di]++; v[7]++;
                        det.computeIfAbsent(d, k -> { List<List<String>> l = new ArrayList<>(); for (int i = 0; i < 7; i++) l.add(new ArrayList<>()); return l; })
                                .get(di).add(a.title + " " + (r.hDepart.isEmpty() ? "?" : r.hDepart));
                    }
                }
        List<String> keys = new ArrayList<>(c.keySet());
        keys.sort((x, y) -> c.get(y)[7] != c.get(x)[7] ? c.get(y)[7] - c.get(x)[7] : x.compareTo(y));
        Frequencies f = new Frequencies();
        for (String k : keys) {
            f.counts.put(k, c.get(k)); f.details.put(k, det.get(k));
            for (int i = 0; i < 8; i++) f.totals[i] += c.get(k)[i];
        }
        return f;
    }

    // ================================================================== contrôles
    public static List<String> check(Program prog, Aircraft ac) {
        List<String> w = new ArrayList<>();
        Integer maxDay = safeTime(prog.settings.maxJour);
        for (int di = 0; di < DAYS.length; di++) {
            List<Rotation> rots = ac.days.get(di);
            DayCalc dc = computeDay(rots);
            for (int ri = 0; ri < rots.size(); ri++) {
                Rotation r = rots.get(ri);
                if (!r.hasFlight()) continue;
                String L = DAYS[di] + " rot. " + (ri + 1);
                String[][] names = {{"h_depart", "départ"}, {"h_arrivee_escale", "arrivée escale"}, {"h_redepart", "redépart"}, {"h_arrivee", "arrivée"}};
                for (String[] kn : names) {
                    String v = r.get(kn[0]);
                    if (v.trim().isEmpty()) w.add(L + " : heure de " + kn[1] + " manquante");
                    else if (safeTime(v) == null) w.add(L + " : heure de " + kn[1] + " invalide (" + v + ")");
                }
                RotationCalc c = dc.rotations.get(ri);
                for (String[] leg : new String[][]{{r.depart, r.escale, r.hDepart, r.hArriveeEscale}, {r.escale, r.arrivee, r.hRedepart, r.hArrivee}}) {
                    Integer t1 = safeTime(leg[2]), t2 = safeTime(leg[3]), known = getDuration(prog, ac, up(leg[0]) + ">" + up(leg[1]));
                    if (t1 == null || t2 == null || known == null || up(leg[0]).isEmpty() || up(leg[1]).isEmpty()) continue;
                    int dur = elapsed(t1, t2);
                    if (Math.abs(dur - known) > Math.max(20, known * 0.35))
                        w.add(L + " : durée de vol anormale " + up(leg[0]) + " → " + up(leg[1]) + " " + fmtDuration(dur) + " (habituel " + fmtDuration(known) + ")");
                }
                if (c.escale != null && c.escale < ac.escaleMin) w.add(L + " : escale à " + up(r.escale) + " trop courte (" + c.escale + " mn)");
                if (c.ecart != null) {
                    if (c.ecart < 0) w.add(L + " : chevauchement avec la rotation suivante");
                    else if (c.ecart < ac.baseMin) w.add(L + " : escale à la base trop courte (" + c.ecart + " mn)");
                }
                for (String[] cf : conflicts(prog, ac, di, ri))
                    w.add(L + " : même destination que " + cf[0] + " (" + cf[1] + " " + cf[2] + "–" + cf[3] + ") au même moment");
                Rotation n = null;
                for (int j = ri + 1; j < rots.size(); j++) if (rots.get(j).hasFlight()) { n = rots.get(j); break; }
                if (n != null && !up(n.depart).isEmpty() && !up(r.arrivee).isEmpty() && !up(n.depart).equals(up(r.arrivee)))
                    w.add(L + " : arrive à " + up(r.arrivee) + " mais la suivante part de " + up(n.depart));
            }
            if (maxDay != null && dc.total > maxDay) w.add(DAYS[di] + " : total " + fmtDuration(dc.total) + " > maximum " + prog.settings.maxJour);
        }
        return w;
    }

    // ================================================================== données des feuilles papier
    private static Rotation R(String... v) { return new Rotation(v); }
    private static List<Rotation> day(Rotation... r) { List<Rotation> d = new ArrayList<>(List.of(r)); while (d.size() < 3) d.add(new Rotation()); return d; }

    public static Program defaultProgram(boolean semaineType) {
        List<List<Rotation>> erj = emptyWeek(), pax = emptyWeek();
        erj.set(0, day(R("DAKHLA", "07:00", "AGADIR", "08:15", "09:00", "DAKHLA", "10:35"), R("DAKHLA", "11:35", "DAKAR", "13:25", "14:25", "DAKHLA", "16:15"), R("DAKHLA", "17:15", "NOUADHIBOU", "18:05", "19:05", "DAKHLA", "19:55")));
        erj.set(2, day(R("DAKHLA", "07:00", "LAAYOUNE", "08:00", "08:45", "LAS PALMAS", "09:45"), R("LAS PALMAS", "10:45", "LAAYOUNE", "11:45", "12:30", "DAKHLA", "13:30"), R("DAKHLA", "14:30", "GUELMIM", "15:45", "16:30", "DAKHLA", "17:45")));
        erj.set(4, day(R("DAKHLA", "07:00", "LAS PALMAS", "07:55", "08:55", "DAKHLA", "09:55"), R("DAKHLA", "10:55", "NOUADHIBOU", "11:40", "12:40", "DAKHLA", "13:25"), R("DAKHLA", "14:25", "OUARZAZATE", "15:50", "16:40", "DAKHLA", "18:20")));
        erj.set(5, day(R("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"), R("DAKHLA", "11:30", "BAMAKO", "14:25", "15:25", "DAKHLA", "18:10"), R("DAKHLA", "20:10", "NOUADHIBOU", "21:00", "21:45", "DAKHLA", "22:35", "MAINTENANCE")));
        erj.set(6, day(R("DAKHLA", "08:00", "AGADIR", "09:30", "10:15", "DAKHLA", "11:45"), R("DAKHLA", "12:45", "NOUADHIBOU", "13:40", "14:40", "DAKHLA", "15:35"), R("DAKHLA", "16:15", "LAS PALMAS", "17:10", "18:10", "DAKHLA", "19:03")));
        pax.set(0, day(R("DAKHLA", "08:00", "GUELMIM", "09:25", "09:55", "LAS PALMAS", "11:20"), R("LAS PALMAS", "12:20", "GUELMIM", "13:45", "14:15", "DAKHLA", "15:40"), R("DAKHLA", "17:00", "AGADIR", "19:25", "19:55", "DAKHLA", "22:20")));
        pax.set(2, day(R("DAKHLA", "08:00", "NOUADHIBOU", "09:05", "09:35", "DAKHLA", "10:40"), R("DAKHLA", "11:40", "LAS PALMAS", "12:50", "13:20", "DAKHLA", "14:30"), R("DAKHLA", "15:30", "NOUAKCHOTT", "17:20", "17:50", "DAKHLA", "19:40")));
        pax.set(4, day(R("DAKHLA", "07:00", "SMARA", "08:25", "08:55", "DAKHLA", "10:20"), R("DAKHLA", "12:40", "LAAYOUNE", "13:50", "14:20", "DAKHLA", "15:30"), R("DAKHLA", "16:30", "AGADIR", "18:55", "19:25", "DAKHLA", "21:50")));
        pax.set(5, day(R("DAKHLA", "07:00", "GUELMIM", "08:25", "08:55", "LAS PALMAS", "10:20"), R("LAS PALMAS", "11:20", "GUELMIM", "12:45", "13:15", "DAKHLA", "14:40"), R("DAKHLA", "19:50", "NOUADHIBOU", "20:55", "21:25", "DAKHLA", "22:30")));
        pax.set(6, day(R("DAKHLA", "07:00", "LAS PALMAS", "08:10", "08:40", "DAKHLA", "09:50"), R("DAKHLA", "10:50", "MARRAKECH", "13:20", "13:50", "DAKHLA", "16:20"), R("DAKHLA", "17:20", "NOUAKCHOTT", "19:10", "19:40", "DAKHLA", "21:30")));
        Program p = new Program();
        p.aircraft.add(new Aircraft("ERJ.145", "DAKHLA", erj));
        p.aircraft.add(new Aircraft("ERJ.145 - AVION 2", "DAKHLA"));
        p.aircraft.add(new Aircraft("BEECHCRAFT 1900 CARGO", "DAKHLA"));
        p.aircraft.add(new Aircraft("BEECHCRAFT 1900 PASSAGERS", "DAKHLA", pax));
        learnDurations(p, null);
        for (Aircraft a : p.aircraft) p.defaultsSeen.add(a.title);
        if (semaineType) { ensureSemaineType(p); ensureErj2Rot3(p); }
        return p;
    }

    public static List<String> applySemaineType(Program prog, boolean replaceAll) {
        List<String> report = new ArrayList<>();
        for (Map.Entry<String, String[][][]> e : SemaineType.WEEK.entrySet()) {
            Aircraft ac = null;
            for (Aircraft a : prog.aircraft) if (up(a.title).equals(up(e.getKey()))) { ac = a; break; }
            if (ac == null) continue;
            List<String> filled = new ArrayList<>();
            String[][][] days = e.getValue();
            for (int di = 0; di < days.length; di++) {
                if (!replaceAll && ac.days.get(di).stream().anyMatch(Rotation::isUsed)) continue;
                List<Rotation> nw = new ArrayList<>();
                for (String[] r : days[di]) nw.add(new Rotation(r));
                while (nw.size() < ROTATIONS_PER_DAY) nw.add(new Rotation());
                ac.days.set(di, nw);
                filled.add(DAYS[di].charAt(0) + DAYS[di].substring(1).toLowerCase(Locale.ROOT));
            }
            if (!filled.isEmpty()) report.add(ac.title + " : " + (filled.size() < 7 ? String.join(", ", filled) : "toute la semaine"));
        }
        return report;
    }
    /** Feuille « ERJ.145 - AVION 2 » : 3e rotation du programme par défaut (ajoutée à la main sur la feuille). */
    public static final String ERJ2_ROT3_MARKER = "ERJ2_ROT3_2026_11";
    public static final String[][] ERJ2_ROT3 = {
            {"DAKHLA", "14:25", "TENERIFE", "15:30", "16:30", "DAKHLA", "17:35"},
            {"DAKHLA", "15:35", "OUARZAZATE", "17:00", "17:40", "DAKHLA", "19:20"},
            {"DAKHLA", "16:45", "NOUAKCHOTT", "18:00", "19:00", "DAKHLA", "20:15"},
            {"DAKHLA", "14:45", "AGADIR", "16:15", "16:55", "DAKHLA", "18:30"},
            null,
            {"DAKHLA", "16:45", "MARRAKECH", "18:30", "19:10", "DAKHLA", "20:45"},
            {"DAKHLA", "15:55", "LAAYOUNE", "16:00", "16:40", "DAKHLA", "17:45"}};
    public static List<String> ensureErj2Rot3(Program prog) {
        List<String> out = new ArrayList<>();
        if (prog.defaultsSeen.contains(ERJ2_ROT3_MARKER)) return out;
        prog.defaultsSeen.add(ERJ2_ROT3_MARKER);
        for (Aircraft a : prog.aircraft) {
            if (!up(a.title).equals("ERJ.145 - AVION 2")) continue;
            for (int di = 0; di < 7; di++) {
                if (ERJ2_ROT3[di] == null) continue;
                List<Rotation> d = a.days.get(di);
                int slot = -1;
                for (int ri = 0; ri < d.size(); ri++) if (!d.get(ri).isUsed()) { slot = ri; break; }
                final String[] add = ERJ2_ROT3[di];
                if (slot < 0 || d.stream().anyMatch(r -> up(r.escale).equals(add[2]) && r.hDepart.equals(add[1]))) continue;
                d.set(slot, new Rotation(ERJ2_ROT3[di]));
                out.add(DAYS[di].charAt(0) + DAYS[di].substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ================================================================== cloner / ajouter des avions
    /** Copie les jours d'un programme vers un autre avion. recompute : heures recalculées avec les durées de vol de l'avion cible. */
    public static List<String> cloneDays(Program prog, Aircraft src, Aircraft dst, boolean recompute) {
        List<String> missing = new ArrayList<>();
        for (int di = 0; di < DAYS.length; di++) {
            List<Rotation> d = copyDay(src.days.get(di));
            dst.days.set(di, d);
        }
        if (recompute) {
            Profile prof = profile(prog, dst);
            for (int di = 0; di < DAYS.length; di++) {
                List<Rotation> d = dst.days.get(di);
                boolean first = true;
                for (int ri = 0; ri < d.size(); ri++) {
                    Rotation r = d.get(ri);
                    if (!r.hasFlight() || up(r.escale).isEmpty()) continue;
                    // on garde l'heure de départ de la 1re rotation ; le reste est recalculé
                    if (!first) r.hDepart = "";
                    r.hArriveeEscale = r.hRedepart = r.hArrivee = "";
                    complete(prog, dst, di, ri, prof, missing, dst);
                    first = false;
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(missing));
    }
    /** Nouvel avion : durées de vol et escales minimum reprises d'un modèle (facultatif). */
    public static Aircraft newAircraft(Program prog, String title, String base, Aircraft model) {
        Aircraft a = new Aircraft(up(title), up(base));
        if (model != null) {
            Aircraft m = original(model);
            a.durations = new LinkedHashMap<>(m.durations);
            a.escaleMin = m.escaleMin; a.baseMin = m.baseMin;
        }
        prog.aircraft.add(a);
        return a;
    }

    public static List<String> ensureSemaineType(Program prog) {
        if (prog.defaultsSeen.contains(SemaineType.MARKER)) return new ArrayList<>();
        prog.defaultsSeen.add(SemaineType.MARKER);
        return applySemaineType(prog, false);
    }
    /**
     * Programme par défaut d'une nouvelle installation (programme_defaut.json, issu du PDF fourni le 13/11/2026).
     * Entièrement modifiable ensuite ; « Revenir au programme par défaut » le recharge.
     */
    public static Program factoryProgram() {
        Program p = new Program();
        try (java.io.InputStream in = Core.class.getResourceAsStream("programme_defaut.json")) {
            for (Object ao : Json.arr(Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)))) {
                Map<String, Object> a = Json.obj(ao);
                List<List<Rotation>> days = new ArrayList<>();
                for (Object d : Json.arr(a.get("days"))) {
                    List<Rotation> rots = new ArrayList<>();
                    for (Object r : Json.arr(d)) {
                        List<Object> f = Json.arr(r);
                        String[] v = new String[8];
                        for (int k = 0; k < 8; k++) v[k] = k < f.size() ? Json.str(f.get(k), "") : "";
                        rots.add(new Rotation(v));
                    }
                    while (rots.size() < ROTATIONS_PER_DAY) rots.add(new Rotation());
                    days.add(rots);
                }
                p.aircraft.add(new Aircraft(Json.str(a.get("title"), "AVION"), "DAKHLA", days));
            }
        } catch (Exception e) {
            return defaultProgram(true);              // sécurité : ancien programme intégré
        }
        learnDurations(p, null);
        for (Aircraft a : p.aircraft) p.defaultsSeen.add(a.title);
        p.defaultsSeen.add(SemaineType.MARKER);
        p.defaultsSeen.add(ERJ2_ROT3_MARKER);
        return p;
    }

    public static List<String> addMissingDefaults(Program prog) {
        Program d = defaultProgram(true);
        Set<String> titles = new HashSet<>();
        for (Aircraft a : prog.aircraft) titles.add(up(a.title));
        List<String> added = new ArrayList<>();
        for (int i = 0; i < d.aircraft.size(); i++) {
            Aircraft ref = d.aircraft.get(i);
            if (prog.defaultsSeen.contains(ref.title)) continue;
            prog.defaultsSeen.add(ref.title);
            if (!titles.contains(up(ref.title))) { prog.aircraft.add(Math.min(i, prog.aircraft.size()), ref); added.add(ref.title); }
        }
        return added;
    }
    public static List<String> fillEmptyFromDefaults(Program prog) {
        Program d = defaultProgram(false);
        List<String> done = new ArrayList<>();
        for (Aircraft ref : d.aircraft) {
            if (ref.days.stream().flatMap(List::stream).noneMatch(Rotation::isUsed)) continue;
            for (Aircraft ac : prog.aircraft)
                if (up(ac.title).equals(up(ref.title)) && ac.days.stream().flatMap(List::stream).noneMatch(Rotation::isUsed)) {
                    ac.days = new ArrayList<>();
                    for (List<Rotation> dd : ref.days) ac.days.add(copyDay(dd));
                    learnDurations(prog, ac);
                    done.add(ac.title);
                }
        }
        return done;
    }

    /** Programme en texte (WhatsApp / e-mail). */
    public static String textSummary(Program prog, List<Aircraft> list) {
        List<String> L = new ArrayList<>();
        for (Aircraft ac : list) {
            L.add("*" + ac.title + " – " + (ac.week == null ? "programme de la semaine" : weekShort(ac.week)) + "*");
            for (int di = 0; di < DAYS.length; di++) {
                List<Rotation> rots = ac.days.get(di);
                DayCalc dc = computeDay(rots);
                if (rots.stream().noneMatch(Rotation::isUsed)) continue;
                L.add("_" + dayLabel(ac.week, di) + "_");
                for (int ri = 0; ri < rots.size(); ri++) {
                    Rotation r = rots.get(ri);
                    if (!r.isUsed()) continue;
                    if (!r.hasFlight()) { L.add("  " + up(r.remarque)); continue; }
                    RotationCalc c = dc.rotations.get(ri);
                    String t = "  " + up(r.depart) + " " + r.hDepart + " > " + up(r.escale) + " " + r.hArriveeEscale + "/" + r.hRedepart
                            + " > " + up(r.arrivee) + " " + r.hArrivee;
                    if (c.duree != null) t += " (" + fmtDuration(c.duree) + ")";
                    if (!up(r.remarque).isEmpty()) t += " – " + up(r.remarque);
                    L.add(t);
                }
                L.add("  Total : " + fmtDuration(dc.total) + " – bloc " + fmtDuration(dc.bloc));
            }
            L.add("Total semaine : " + fmtDuration(weekTotal(ac)) + " – bloc de vol : " + fmtDuration(weekBloc(ac)));
            L.add("");
        }
        return String.join("\n", L).trim();
    }

    // ================================================================== semaines datées (historique)
    static final java.time.format.DateTimeFormatter DM = java.time.format.DateTimeFormatter.ofPattern("dd/MM"),
            DMY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Lundi de la semaine d'une date, au format AAAA-MM-JJ. */
    public static String monday(java.time.LocalDate d) { return d.minusDays(d.getDayOfWeek().getValue() - 1).toString(); }
    public static String thisWeek() { return monday(java.time.LocalDate.now()); }
    public static java.time.LocalDate dateOf(String week, int di) { return java.time.LocalDate.parse(week).plusDays(di); }
    public static String shiftWeek(String week, int n) { return java.time.LocalDate.parse(week).plusWeeks(n).toString(); }
    /** « LUNDI » (programme type) ou « LUNDI 16/11 » (semaine datée). */
    public static String dayLabel(String week, int di) { return week == null ? DAYS[di] : DAYS[di] + " " + dateOf(week, di).format(DM); }
    public static String weekLabel(String week) {
        if (week == null) return "Programme type (sans date)";
        return "Semaine du " + dateOf(week, 0).format(DMY) + " au " + dateOf(week, 6).format(DMY);
    }
    public static String weekShort(String week) {
        return week == null ? "programme type" : "semaine du " + dateOf(week, 0).format(DM) + " au " + dateOf(week, 6).format(DMY);
    }

    /** Toutes les semaines datées existantes (triées). */
    public static TreeSet<String> weeks(Program p) {
        TreeSet<String> s = new TreeSet<>();
        for (Aircraft a : p.aircraft) s.addAll(a.weeks.keySet());
        return s;
    }
    /** Vue d'un avion pour une semaine : mêmes durées de vol, jours de la semaine choisie. */
    public static Aircraft view(Aircraft ac, String week) {
        Aircraft o = original(ac);
        if (week == null) return o;
        Aircraft v = new Aircraft(o.title, o.base, o.weeks.computeIfAbsent(week, k -> emptyWeek()));
        v.durations = o.durations; v.escaleMin = o.escaleMin; v.baseMin = o.baseMin; v.origin = o; v.week = week;
        return v;
    }
    public static Aircraft original(Aircraft a) { return a.origin != null ? a.origin : a; }
    public static List<Aircraft> views(Program p, String week) {
        List<Aircraft> l = new ArrayList<>();
        for (Aircraft a : p.aircraft) l.add(view(a, week));
        return l;
    }
    /** Crée une semaine datée pour tous les avions. source : null = programme type, "vide", ou une autre semaine. */
    public static void createWeek(Program p, String week, String source) {
        for (Aircraft a : p.aircraft) {
            List<List<Rotation>> src = source == null ? a.days : "vide".equals(source) ? null : a.weeks.get(source);
            List<List<Rotation>> d = new ArrayList<>();
            for (int di = 0; di < DAYS.length; di++) d.add(src == null ? emptyDay() : copyDay(src.get(di)));
            a.weeks.put(week, d);
        }
    }
    public static void deleteWeek(Program p, String week) { for (Aircraft a : p.aircraft) a.weeks.remove(week); }

    // ================================================================== sérialisation (format commun à toutes les versions)
    public static Map<String, Object> toDict(Program prog) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schema", SCHEMA_VERSION);
        d.put("saved_at", java.time.LocalDateTime.now().withNano(0).toString());
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("escale_defaut", prog.settings.escaleDefaut);
        st.put("escale_base", prog.settings.escaleBase);
        st.put("max_jour", prog.settings.maxJour);
        d.put("settings", st);
        d.put("defaults_seen", new ArrayList<Object>(prog.defaultsSeen));
        d.put("stations", new ArrayList<Object>(prog.stations));
        d.put("rev", prog.rev);
        List<Object> acs = new ArrayList<>();
        for (Aircraft a : prog.aircraft) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", a.title);
            m.put("base", a.base);
            m.put("durations", new LinkedHashMap<String, Object>(a.durations));
            m.put("escale_min", a.escaleMin);
            m.put("base_min", a.baseMin);
            List<Object> days = new ArrayList<>();
            for (List<Rotation> day : a.days) {
                List<Object> rs = new ArrayList<>();
                for (Rotation r : day) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    for (String f : FIELDS) rm.put(f, r.get(f));
                    rs.add(rm);
                }
                days.add(rs);
            }
            m.put("days", days);
            Map<String, Object> wk = new LinkedHashMap<>();
            for (Map.Entry<String, List<List<Rotation>>> e : a.weeks.entrySet()) wk.put(e.getKey(), daysToList(e.getValue()));
            m.put("weeks", wk);
            acs.add(m);
        }
        d.put("aircraft", acs);
        return d;
    }

    static List<Object> daysToList(List<List<Rotation>> week) {
        List<Object> days = new ArrayList<>();
        for (List<Rotation> day : week) {
            List<Object> rs = new ArrayList<>();
            for (Rotation r : day) {
                Map<String, Object> rm = new LinkedHashMap<>();
                for (String f : FIELDS) rm.put(f, r.get(f));
                rs.add(rm);
            }
            days.add(rs);
        }
        return days;
    }
    static List<List<Rotation>> daysFromList(Object o) {
        List<Object> rawDays = Json.arr(o);
        List<List<Rotation>> days = new ArrayList<>();
        for (int di = 0; di < DAYS.length; di++) {
            List<Rotation> rots = new ArrayList<>();
            List<Object> raw = di < rawDays.size() ? Json.arr(rawDays.get(di)) : List.of();
            for (int ri = 0; ri < raw.size() && ri < ROTATIONS_PER_DAY; ri++) {
                Map<String, Object> rm = Json.obj(raw.get(ri));
                Rotation r = new Rotation();
                for (String f : FIELDS) r.set(f, Json.str(rm.get(f), ""));
                rots.add(r);
            }
            while (rots.size() < ROTATIONS_PER_DAY) rots.add(new Rotation());
            days.add(rots);
        }
        return days;
    }

    public static Program fromDict(Object o) {
        Map<String, Object> d = Json.obj(o);
        if (!(d.get("aircraft") instanceof List)) throw new IllegalArgumentException("Fichier de programme invalide.");
        Program p = new Program();
        for (Object ao : Json.arr(d.get("aircraft"))) {
            Map<String, Object> a = Json.obj(ao);
            List<Object> rawDays = Json.arr(a.get("days"));
            List<List<Rotation>> days = new ArrayList<>();
            for (int di = 0; di < DAYS.length; di++) {
                List<Rotation> rots = new ArrayList<>();
                List<Object> raw = di < rawDays.size() ? Json.arr(rawDays.get(di)) : List.of();
                for (int ri = 0; ri < raw.size() && ri < ROTATIONS_PER_DAY; ri++) {
                    Map<String, Object> rm = Json.obj(raw.get(ri));
                    Rotation r = new Rotation();
                    for (String f : FIELDS) r.set(f, Json.str(rm.get(f), ""));
                    rots.add(r);
                }
                while (rots.size() < ROTATIONS_PER_DAY) rots.add(new Rotation());
                days.add(rots);
            }
            Aircraft ac = new Aircraft(Json.str(a.get("title"), "AVION"), Json.str(a.get("base"), ""), days);
            for (Map.Entry<String, Object> e : Json.obj(a.get("durations")).entrySet()) ac.durations.put(e.getKey(), Json.num(e.getValue(), 0));
            ac.escaleMin = Json.num(a.get("escale_min"), 45);
            ac.baseMin = Json.num(a.get("base_min"), 45);
            for (Map.Entry<String, Object> e : Json.obj(a.get("weeks")).entrySet())
                if (e.getKey().matches("\\d{4}-\\d{2}-\\d{2}")) ac.weeks.put(e.getKey(), daysFromList(e.getValue()));
            p.aircraft.add(ac);
        }
        Map<String, Object> st = Json.obj(d.get("settings"));
        p.settings.escaleDefaut = Json.num(st.get("escale_defaut"), 45);
        p.settings.escaleBase = Json.num(st.get("escale_base"), 60);
        p.settings.maxJour = Json.str(st.get("max_jour"), "13:00");
        if (d.containsKey("defaults_seen")) for (Object x : Json.arr(d.get("defaults_seen"))) p.defaultsSeen.add(String.valueOf(x));
        else for (Aircraft a : p.aircraft) p.defaultsSeen.add(a.title);
        p.rev = Json.num(d.get("rev"), 0);
        if (d.containsKey("stations")) {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (Object x : Json.arr(d.get("stations"))) if (!up(String.valueOf(x)).isEmpty()) s.add(up(String.valueOf(x)));
            p.stations = new ArrayList<>(s);
        }
        if (d.containsKey("durations")) for (Aircraft ac : p.aircraft) if (ac.durations.isEmpty()) learnDurations(p, ac);
        return p;
    }
    public static Program copy(Program p) { return fromDict(Json.parse(Json.write(toDict(p)))); }
}

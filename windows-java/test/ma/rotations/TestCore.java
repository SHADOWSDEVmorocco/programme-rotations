package ma.rotations;

import java.util.*;
import static ma.rotations.Core.*;

/** Tests du moteur Java (mêmes cas que les tests du logiciel Python). Lancer : java ... ma.rotations.TestCore */
public class TestCore {
    static int n = 0;
    static void eq(Object a, Object b, String m) { if (!Objects.equals(a, b)) throw new AssertionError(m + " : " + a + " != " + b); n++; }
    static void ok(boolean c, String m) { if (!c) throw new AssertionError(m); n++; }

    public static void main(String[] args) throws Exception {
        String[][] t = {{"0700", "07:00"}, {"7:00", "07:00"}, {"7h05", "07:05"}, {"700", "07:00"}, {"07.30", "07:30"}, {"23:59", "23:59"}, {"7", "07:00"}, {"", ""}};
        for (String[] x : t) eq(normalizeTime(x[0]), x[1], "heure " + x[0]);
        for (String bad : new String[]{"25:00", "12:60", "abc", "1:2:3"}) { try { parseTime(bad); ok(false, "invalide " + bad); } catch (IllegalArgumentException e) { n++; } }
        eq(parseMinutes("1h"), 60, "1h"); eq(parseMinutes("1h30"), 90, "1h30"); eq(parseMinutes("75 mn"), 75, "75 mn");

        Program p = defaultProgram(false);
        Aircraft erj = p.aircraft.get(0);
        DayCalc lun = computeDay(erj.days.get(0));
        eq(fmtDuration(lun.rotations.get(0).duree) + fmtDuration(lun.rotations.get(1).duree) + fmtDuration(lun.rotations.get(2).duree), "03:3504:4002:40", "lundi");
        eq(fmtDuration(lun.total), "10:55", "total lundi");
        eq(fmtDuration(weekTotal(erj)), "50:58", "semaine ERJ (feuilles papier)");
        eq(fmtDuration(weekTotal(p.aircraft.get(3))), "55:00", "semaine Beechcraft passagers");
        DayCalc mid = computeDay(List.of(new Rotation("A", "23:00", "B", "23:50", "00:30", "A", "01:20"), new Rotation(), new Rotation()));
        eq(mid.rotations.get(0).duree, 140, "passage de minuit");

        Program q = defaultProgram(true);
        for (Aircraft a : q.aircraft)
            if (a.title.equals("BEECHCRAFT 1900 CARGO")) eq(fmtDuration(weekBloc(a)), SemaineType.BLOC_FEUILLE.get(a.title), "bloc " + a.title);
        eq(fmtDuration(weekBloc(q.aircraft.get(1))), "45:40", "ERJ2 : semaine type + 3e rotation");
        ok(check(q, q.aircraft.get(1)).stream().anyMatch(x -> x.contains("durée de vol anormale DAKHLA → LAAYOUNE 00:05")), "durée anormale détectée");
        Aircraft a3 = newAircraft(q, "erj.145 - avion 3", "dakhla", q.aircraft.get(1));
        cloneDays(q, q.aircraft.get(1), a3, false);
        eq(a3.title, "ERJ.145 - AVION 3", "nouvel avion");
        eq(a3.days.get(0).get(2).escale, "TENERIFE", "clone");
        a3.days.get(0).get(0).hDepart = "05:00";
        eq(q.aircraft.get(1).days.get(0).get(0).hDepart, "07:15", "clone indépendant");
        cloneDays(q, q.aircraft.get(1), q.aircraft.get(2), true);
        ok(!q.aircraft.get(2).days.get(3).get(2).hArrivee.equals(q.aircraft.get(1).days.get(3).get(2).hArrivee), "recalcul Beechcraft");
        applySemaineType(q, true);
        for (Aircraft a : q.aircraft) if (SemaineType.BLOC_FEUILLE.containsKey(a.title)) eq(fmtDuration(weekBloc(a)), SemaineType.BLOC_FEUILLE.get(a.title), "bloc semaine type " + a.title);
        for (Aircraft a : q.aircraft) for (String w : check(q, a)) ok(!w.contains("manquante"), "réserve sans alerte : " + w);
        eq(ensureSemaineType(q).size(), 0, "semaine type une seule fois");

        Program r = defaultProgram(true);
        Aircraft e2 = r.aircraft.get(1);
        e2.days.set(1, emptyDay());
        applyChange(r, e2, 1, 0, "escale", "AGADIR");
        ok(!e2.days.get(1).get(0).hArrivee.isEmpty(), "remplissage instantané");
        String dep = e2.days.get(1).get(0).hDepart;
        applyChange(r, e2, 1, 1, "escale", "DAKAR");
        setGap(r, e2, 1, 0, 90);
        eq(e2.days.get(1).get(1).hDepart, fmtTime(parseTime(e2.days.get(1).get(0).hArrivee) + 90), "esc. base 90");
        eq(missingDurations(r, e2, 1, 0).size(), 0, "durées connues");

        // fichier : écriture atomique + sauvegarde + relecture identique
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rot");
        java.nio.file.Path f = dir.resolve("programme.json");
        Storage.saveProgram(r, f, 20); Storage.saveProgram(r, f, 20);
        eq(Storage.backups(dir.resolve("sauvegardes"), "programme").size(), 1, "copie de sécurité");
        Map<String, Object> a1 = toDict(r), a2 = toDict(Storage.loadProgram(f));
        a1.remove("saved_at"); a2.remove("saved_at");
        eq(Json.write(a1), Json.write(a2), "relecture");
        try { fromDict(Json.parse("{\"x\":1}")); ok(false, "fichier invalide"); } catch (IllegalArgumentException e) { n++; }
        // PDF
        byte[] pdf = Pdf.build(q.aircraft, "test");
        ok(new String(pdf, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"), "PDF");
        // règle : même type d'avion, même destination, même heure → conflit
        Program cp = factoryProgram();
        Aircraft c1 = cp.aircraft.get(0), c2 = cp.aircraft.get(1);
        c1.days.set(5, emptyDay()); c2.days.set(5, emptyDay());
        c1.days.get(5).set(0, new Rotation("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"));
        c2.days.get(5).set(0, new Rotation("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"));
        eq(conflicts(cp, c1, 5, 0).size(), 1, "conflit détecté");
        ok(check(cp, c2).stream().anyMatch(x -> x.startsWith("SAMEDI rot. 1 : même destination que ERJ.145 (NOUAKCHOTT 07:00–10:30)")), "conflit dans les contrôles");
        for (String[] cf : conflicts(cp, cp.aircraft.get(2), 5, 0)) ok(cf[0].startsWith("BEECHCRAFT"), "autre type d'avion : pas de conflit avec " + cf[0]);
        c2.days.get(5).set(0, new Rotation("DAKHLA", "10:45", "NOUAKCHOTT", "12:00", "13:00", "DAKHLA", "14:15"));
        eq(conflicts(cp, c1, 5, 0).size(), 0, "heures différentes : pas de conflit");
        c2.days.get(5).set(0, new Rotation());
        List<String> sg = suggest(cp, c2, 5, 0, "escale");
        ok(sg.indexOf("NOUAKCHOTT") > 3, "destination occupée proposée en dernier : " + sg);
        // compteur de fréquences
        Program fq = factoryProgram();
        Frequencies fr = frequencies(fq.aircraft);
        int visits = 0;
        for (Aircraft a : fq.aircraft) for (List<Rotation> d : a.days) for (Rotation rr : d) if (rr.hasFlight()) visits += destinations(rr, a.base).size();
        eq(fr.totals[7], visits, "total des fréquences = destinations desservies");
        int sumDays = 0; for (int i = 0; i < 7; i++) sumDays += fr.totals[i];
        eq(sumDays, fr.totals[7], "somme des jours = total semaine");
        ok(!fr.counts.containsKey("DAKHLA"), "la base n'est pas comptée");
        Program fp = factoryProgram();
        String[] tot = {"74:08", "74:05", "45:00", "74:55"};
        for (int i = 0; i < 4; i++) eq(fmtDuration(weekTotal(fp.aircraft.get(i))), tot[i], "programme par défaut " + fp.aircraft.get(i).title);
        eq(ensureSemaineType(fp).size() + ensureErj2Rot3(fp).size(), 0, "programme par défaut non modifié par les mises à jour");
        System.out.println(n + " vérifications réussies");
    }
}

package ma.rotations;

import java.util.*;
import static ma.rotations.Core.*;

/** Exécute les mêmes scénarios que crosscheck.py et écrit le résultat en JSON (comparaison Java / Python). */
public class CrossCheck {
    public static void main(String[] a) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        Program p = defaultProgram(true);
        Map<String, Object> d = toDict(p); d.remove("saved_at");
        out.put("default", d);
        List<Object> per = new ArrayList<>();
        for (Aircraft ac : p.aircraft) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", ac.title); m.put("total", weekTotal(ac)); m.put("bloc", weekBloc(ac));
            m.put("check", new ArrayList<Object>(check(p, ac)));
            m.put("text", textSummary(p, List.of(ac)));
            List<Object> sg = new ArrayList<>();
            for (String attr : new String[]{"depart", "escale", "arrivee", "h_depart", "h_arrivee_escale", "h_redepart", "h_arrivee", "escale_mn", "ecart", "remarque"})
                for (int[] c : new int[][]{{0, 0}, {1, 1}, {3, 2}, {6, 1}}) sg.add(new ArrayList<Object>(suggest(p, ac, c[0], c[1], attr)));
            m.put("suggest", sg);
            per.add(m);
        }
        out.put("per", per);
        // scénario de saisie sur une copie vidée
        Program q = defaultProgram(false);
        Aircraft e2 = q.aircraft.get(1);
        List<Object> steps = new ArrayList<>();
        steps.add(new ArrayList<Object>(applyChange(q, e2, 1, 0, "escale", "AGADIR")));
        steps.add(new ArrayList<Object>(applyChange(q, e2, 1, 1, "escale", "DAKAR")));
        steps.add(new ArrayList<Object>(applyChange(q, e2, 1, 2, "escale", "TENERIFE")));
        steps.add(new ArrayList<Object>(applyChange(q, e2, 1, 0, "h_depart", "07:30")));
        setGap(q, e2, 1, 0, 90); setEscale(q, e2, 1, 1, 75);
        steps.add(new ArrayList<Object>(applyChange(q, e2, 1, 0, "escale", "NOUADHIBOU")));
        Aircraft pax = q.aircraft.get(3);
        steps.add(new ArrayList<Object>(applyChange(q, pax, 1, 0, "escale", "GUELMIM")));
        steps.add(new ArrayList<Object>(applyChange(q, pax, 1, 1, "escale", "AGADIR")));
        q.aircraft.get(0).days.set(5, emptyDay());
        q.aircraft.get(0).days.get(5).get(0).escale = "BAMAKO";
        steps.add(new ArrayList<Object>(autocomplete(q, q.aircraft.get(0))));
        steps.add(new ArrayList<Object>(applySemaineType(q, false)));
        Map<String, Object> qd = toDict(q); qd.remove("saved_at");
        out.put("steps", steps); out.put("after", qd);
        // lecture d'un fichier ancien format
        Map<String, Object> old = toDict(defaultProgram(false)); old.remove("saved_at");
        for (Object ao : Json.arr(old.get("aircraft"))) Json.obj(ao).remove("durations");
        old.put("durations", Map.of("X>Y", 10));
        Program r = fromDict(Json.parse(Json.write(old)));
        Map<String, Object> rd = toDict(r); rd.remove("saved_at");
        out.put("migration", rd);
        out.put("times", List.of(normalizeTime("0700"), normalizeTime("7h05"), normalizeTime("700"), normalizeTime("7"), parseMinutes("1h30"), parseMinutes("75 mn")));
        new java.io.PrintStream(System.out, true, "UTF-8").print(Json.write(out));
    }
}

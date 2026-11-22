// Tests du moteur (mêmes cas que le logiciel Windows) : node tests/core.test.mjs
import assert from "node:assert/strict";
import * as C from "../public/js/core.js";
let n = 0; const t = (name, f) => { f(); n++; };

t("heures", () => {
  for (const [a, b] of [["0700", "07:00"], ["7:00", "07:00"], ["7h05", "07:05"], ["700", "07:00"], ["07.30", "07:30"], ["23:59", "23:59"], ["7", "07:00"], ["", ""]]) assert.equal(C.normalizeTime(a), b);
  for (const bad of ["25:00", "12:60", "abc", "1:2:3"]) assert.throws(() => C.parseTime(bad));
  assert.deepEqual(["90", "90 mn", "75min", "1:30", "1h30", ""].map(C.parseMinutes), [90, 90, 75, 90, 90, null]);
});
t("valeurs des feuilles", () => {
  const p = C.defaultProgram(false), erj = p.aircraft[0], lun = C.computeDay(erj.days[0]);
  assert.deepEqual(lun.rotations.map((c) => C.fmtDuration(c.duree)), ["03:35", "04:40", "02:40"]);
  assert.equal(C.fmtDuration(lun.total), "10:55");
  assert.equal(C.fmtDuration(C.weekTotal(erj)), "50:58");
  assert.equal(C.fmtDuration(C.weekTotal(p.aircraft[3])), "55:00");
});
t("durées par type d'avion", () => {
  const p = C.defaultProgram(false), [erj, erj2, cargo, pax] = p.aircraft;
  assert.equal(C.getDuration(p, erj, "DAKHLA>AGADIR"), 80);
  assert.equal(C.getDuration(p, erj2, "DAKHLA>AGADIR"), 80);
  assert.equal(C.getDuration(p, pax, "DAKHLA>AGADIR"), 145);
  assert.equal(C.getDuration(p, cargo, "DAKHLA>AGADIR"), 145);
});
t("remplissage instantané", () => {
  const p = C.defaultProgram(false), erj2 = p.aircraft[1];
  assert.equal(C.suggest(p, erj2, 1, 0, "escale")[0], "NOUADHIBOU");
  assert.ok(C.suggest(p, erj2, 1, 0, "escale").includes("TENERIFE"));
  C.applyChange(p, erj2, 1, 0, "escale", "AGADIR");
  const r = erj2.days[1][0];
  assert.deepEqual([r.depart, r.h_depart, r.h_arrivee_escale, r.h_redepart, r.h_arrivee], ["DAKHLA", "07:00", "08:20", "09:05", "10:35"]);
  C.applyChange(p, erj2, 1, 1, "escale", "DAKAR");
  assert.equal(erj2.days[1][1].h_depart, "11:35");
  C.applyChange(p, erj2, 1, 0, "h_depart", "07:30");
  assert.equal(r.h_arrivee, "11:05");
  C.applyChange(p, erj2, 1, 0, "escale", "NOUADHIBOU");
  assert.deepEqual([r.h_depart, r.h_arrivee_escale, r.h_redepart, r.h_arrivee], ["07:30", "08:20", "09:20", "10:10"]);
  const pax = p.aircraft[3];
  C.applyChange(p, pax, 1, 0, "escale", "GUELMIM");
  assert.equal(pax.days[1][0].arrivee, "LAS PALMAS");
  assert.equal(pax.days[1][0].h_redepart, "08:55");
});
t("escales modifiables", () => {
  const p = C.defaultProgram(false), erj = p.aircraft[0];
  C.setGap(p, erj, 0, 0, 90);
  const r2 = erj.days[0][1];
  assert.deepEqual([r2.h_depart, r2.h_arrivee_escale, r2.h_redepart, r2.h_arrivee], ["12:05", "13:55", "14:55", "16:45"]);
  C.setEscale(p, erj, 0, 0, 60);
  assert.deepEqual([erj.days[0][0].h_redepart, erj.days[0][0].h_arrivee], ["09:15", "10:50"]);
});
t("contrôles", () => {
  const p = C.defaultProgram(false);
  assert.deepEqual(C.check(p, p.aircraft[0]), ["DIMANCHE rot. 2 : escale à la base trop courte (40 mn)"]);
  const ac = C.Aircraft("X");
  ac.days[0][0] = C.R("DAKHLA", "07:00", "AGADIR", "08:00", "08:10", "DAKHLA", "09:30");
  ac.days[0][1] = C.R("DAKHLA", "09:00", "DAKAR", "11:00", "12:00", "DAKHLA", "1x:00");
  const w = C.check(p, ac);
  assert.ok(w.some((x) => x.includes("trop courte")) && w.some((x) => x.includes("chevauchement")) && w.some((x) => x.includes("invalide")));
});
t("nouvelle ville", () => {
  const p = C.defaultProgram(false), erj2 = p.aircraft[1];
  erj2.days[1][0] = C.R("DAKHLA", "07:00", "TENERIFE", "", "", "DAKHLA");
  assert.deepEqual(C.missingDurations(p, erj2, 1, 0), [["DAKHLA", "TENERIFE"], ["TENERIFE", "DAKHLA"]]);
});
t("sérialisation et migration", () => {
  const p = C.defaultProgram(false), d = C.toDict(p), q = C.fromDict(JSON.parse(JSON.stringify(d)));
  assert.deepEqual(C.toDict(q).aircraft, d.aircraft);
  d.aircraft.splice(1, 1); delete d.defaults_seen;
  const old = C.fromDict(d);
  assert.deepEqual(C.addMissingDefaults(old), ["ERJ.145 - AVION 2"]);
  assert.deepEqual(C.addMissingDefaults(old), []);
  assert.throws(() => C.fromDict({ x: 1 }));
});
import { BLOC_FEUILLE } from "../public/js/semaine_type.js";
t("semaine type (cargo, jours manquants)", () => {
  const p = C.defaultProgram(), [erj, erj2, cargo, pax] = p.aircraft;
  assert.equal(C.fmtDuration(C.weekBloc(cargo)), BLOC_FEUILLE["BEECHCRAFT 1900 CARGO"]);
  assert.equal(C.fmtDuration(C.weekBloc(erj2)), "45:40");            // semaine type 30:20 + 3e rotation 15:20
  assert.equal(erj2.days[0][2].escale, "TENERIFE"); assert.equal(erj2.days[3][2].h_redepart, "16:55");
  assert.equal(erj.days[0][0].h_depart, "07:00"); assert.equal(erj.days[1][0].escale, "BAMAKO");
  assert.deepEqual(C.ensureSemaineType(p), []);
  C.applySemaineType(p, true);
  for (const a of p.aircraft) assert.equal(C.fmtDuration(C.weekBloc(a)), BLOC_FEUILLE[a.title]);
  assert.ok(!p.aircraft.some((a) => C.check(p, a).some((w) => w.includes("manquante"))));
  assert.ok(C.textSummary(p, [cargo]).includes("DAKHLA 05:00 > AGADIR 07:25/07:55 > DAKHLA 10:20"));
});
t("cloner, ajouter un avion, durée anormale", () => {
  const p = C.defaultProgram(), [erj, erj2, cargo] = p.aircraft;
  assert.ok(C.check(p, erj2).some((w) => w.includes("durée de vol anormale DAKHLA → LAAYOUNE 00:05")));
  assert.deepEqual(C.ensureErj2Rot3(p), []);
  const a = C.newAircraft(p, "erj.145 - avion 3", "dakhla", erj2);
  assert.equal(a.title, "ERJ.145 - AVION 3"); assert.equal(a.durations["DAKHLA>AGADIR"], erj2.durations["DAKHLA>AGADIR"]);
  C.cloneDays(p, erj2, a, false);
  assert.deepEqual(a.days, erj2.days); a.days[0][0].h_depart = "05:00"; assert.equal(erj2.days[0][0].h_depart, "07:15");   // copie indépendante
  const m = C.cloneDays(p, erj2, cargo, true);                          // vers un Beechcraft : heures recalculées
  assert.equal(cargo.days[0][0].h_depart, "07:15");
  assert.notEqual(cargo.days[3][2].h_arrivee, erj2.days[3][2].h_arrivee);
  assert.ok(Array.isArray(m));
  C.createWeek(p, "2026-11-16", null); const v = C.view(a, "2026-11-16"); C.cloneDays(p, C.view(erj, "2026-11-16"), v, false);
  assert.equal(a.weeks["2026-11-16"][0][0].escale, erj.weeks["2026-11-16"][0][0].escale);
});
t("même type d'avion : pas la même destination à la même heure", () => {
  const p = C.factoryProgram(), [e1, e2, cargo] = p.aircraft;
  e1.days[5] = [C.R("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"), C.R(), C.R()];
  e2.days[5] = [C.R("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"), C.R(), C.R()];
  assert.equal(C.conflicts(p, e1, 5, 0).length, 1);
  assert.ok(C.check(p, e2).some((x) => x.startsWith("SAMEDI rot. 1 : même destination que ERJ.145 (NOUAKCHOTT 07:00–10:30)")));
  assert.ok(C.conflicts(p, cargo, 5, 0).every(([t]) => t.startsWith("BEECHCRAFT")));
  e2.days[5][0] = C.R("DAKHLA", "10:45", "NOUAKCHOTT", "12:00", "13:00", "DAKHLA", "14:15");
  assert.equal(C.conflicts(p, e1, 5, 0).length, 0);
  e2.days[5][0] = C.R();
  assert.ok(C.suggest(p, e2, 5, 0, "escale").indexOf("NOUAKCHOTT") > 3);
});
t("compteur de fréquences", () => {
  const p = C.factoryProgram(), f = C.frequencies(p.aircraft);
  assert.equal(f.totals.slice(0, 7).reduce((x, y) => x + y, 0), f.totals[7]);
  assert.ok(!f.rows.some((r) => r.dest === "DAKHLA"));
  for (const r of f.rows) for (let di = 0; di < 7; di++) assert.equal(r.details[di].length, r.counts[di]);
});
console.log(`${n} groupes de tests réussis`);

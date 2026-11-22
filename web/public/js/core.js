// Moteur de calcul du programme des rotations (identique au logiciel Windows).
// Aucune dépendance : utilisable dans le navigateur et dans Node (tests).
import { WEEK as SEMAINE_TYPE, MARKER as SEMAINE_TYPE_MARKER } from "./semaine_type.js";
import { PROGRAMME_DEFAUT } from "./programme_defaut.js";

export const DAYS = ["LUNDI", "MARDI", "MERCREDI", "JEUDI", "VENDREDI", "SAMEDI", "DIMANCHE"];
export const ROTATIONS_PER_DAY = 3;
export const SCHEMA_VERSION = 2;
export const DEFAULT_STATIONS = ["DAKHLA", "LAAYOUNE", "AGADIR", "GUELMIM", "SMARA", "MARRAKECH", "OUARZAZATE",
  "CASABLANCA", "LAS PALMAS", "TENERIFE", "NOUADHIBOU", "NOUAKCHOTT", "DAKAR", "BAMAKO"];
export const ROT_FIELDS = ["depart", "h_depart", "escale", "h_arrivee_escale", "h_redepart", "arrivee", "h_arrivee", "remarque"];
export const TIME_CHAIN = ["h_depart", "h_arrivee_escale", "h_redepart", "h_arrivee"];

// ------------------------------------------------------------------ heures
export function parseTime(text) {
  if (text === null || text === undefined || !String(text).trim()) return null;
  let s = String(text).trim();
  if (/^\d{3}$/.test(s)) s = "0" + s;
  const m = s.match(/^(\d{1,2})\s*[:hH.]?\s*(\d{2})?$/);
  if (!m) throw new Error(`Heure invalide : « ${text} »`);
  const h = +m[1], mn = +(m[2] || 0);
  if (h > 23 || mn > 59) throw new Error(`Heure invalide : « ${text} »`);
  return h * 60 + mn;
}
export const pad = (n) => String(n).padStart(2, "0");
export function fmtTime(min) { min = ((min % 1440) + 1440) % 1440; return `${pad(Math.floor(min / 60))}:${pad(min % 60)}`; }
export function fmtDuration(min) { const s = min < 0 ? "-" : ""; min = Math.abs(min); return `${s}${pad(Math.floor(min / 60))}:${pad(min % 60)}`; }
export function normalizeTime(t) { const v = parseTime(t); return v === null ? "" : fmtTime(v); }
export function safeTime(t) { try { return parseTime(t); } catch { return null; } }
export function elapsed(a, b) { const d = b - a; return d < 0 ? d + 1440 : d; }
export const up = (s) => String(s ?? "").trim().toUpperCase();
export function parseMinutes(text) {
  if (text === null || text === undefined || !String(text).trim()) return null;
  const s = String(text).trim().toLowerCase().replace("min", "").replace("mn", "").replace("m", "").trim();
  if (/^-?\d{1,3}$/.test(s)) return parseInt(s, 10);
  const m = s.match(/^(\d{1,2})\s*[:h]\s*(\d{2})$/);
  if (m) return +m[1] * 60 + +m[2];
  if (/^\d{1,2}\s*h$/.test(s)) return parseInt(s, 10) * 60;   // « 1h » = 60 mn
  throw new Error(`Durée invalide : « ${text} »`);
}
const clone = (x) => JSON.parse(JSON.stringify(x));

// ------------------------------------------------------------------ modèle
export function R(depart = "", h_depart = "", escale = "", h_arrivee_escale = "", h_redepart = "", arrivee = "", h_arrivee = "", remarque = "") {
  return { depart, h_depart, escale, h_arrivee_escale, h_redepart, arrivee, h_arrivee, remarque };
}
export const hasFlight = (r) => ["depart", "escale", "arrivee", "h_depart", "h_arrivee_escale", "h_redepart", "h_arrivee"].some((k) => up(r[k]));
export const isUsed = (r) => ["depart", "escale", "arrivee", "h_depart", "h_arrivee", "remarque"].some((k) => up(r[k]));
export const emptyWeek = () => DAYS.map(() => [R(), R(), R()]);
export function Aircraft(title, base = "DAKHLA", days = emptyWeek(), durations = {}, escale_min = 45, base_min = 45) {
  return { title, base, days, durations, escale_min, base_min, weeks: {} };
}

// ------------------------------------------------------------------ semaines datées (historique)
const pad2 = (n) => String(n).padStart(2, "0");
const isoDate = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
const parseIso = (s) => { const [y, m, d] = s.split("-").map(Number); return new Date(y, m - 1, d); };
export function monday(date = new Date()) { const d = new Date(date.getFullYear(), date.getMonth(), date.getDate()); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return isoDate(d); }
export const thisWeek = () => monday(new Date());
export function dateOf(week, di) { const d = parseIso(week); d.setDate(d.getDate() + di); return d; }
export function shiftWeek(week, n) { const d = parseIso(week); d.setDate(d.getDate() + 7 * n); return isoDate(d); }
const dm = (d) => `${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}`, dmy = (d) => `${dm(d)}/${d.getFullYear()}`;
export const dayLabel = (week, di) => (week ? `${DAYS[di]} ${dm(dateOf(week, di))}` : DAYS[di]);
export const weekLabel = (week) => (week ? `Semaine du ${dmy(dateOf(week, 0))} au ${dmy(dateOf(week, 6))}` : "Programme type (sans date)");
export const weekShort = (week) => (week ? `semaine du ${dm(dateOf(week, 0))} au ${dmy(dateOf(week, 6))}` : "programme type");
export const dmyOf = (week, di) => dmy(dateOf(week, di));
export function weeks(prog) { const s = new Set(); for (const a of prog.aircraft) Object.keys(a.weeks || {}).forEach((w) => s.add(w)); return [...s].sort(); }
export const original = (a) => a.origin || a;
/** Vue d'un avion pour une semaine (mêmes durées de vol, jours de la semaine choisie). */
export function view(ac, week) {
  const o = original(ac);
  if (!week) return o;
  o.weeks ||= {};
  if (!o.weeks[week]) o.weeks[week] = emptyWeek();
  return { title: o.title, base: o.base, days: o.weeks[week], durations: o.durations, escale_min: o.escale_min, base_min: o.base_min, weeks: {}, origin: o, week };
}
export const views = (prog, week) => prog.aircraft.map((a) => view(a, week));
export function createWeek(prog, week, source) {
  for (const a of prog.aircraft) {
    a.weeks ||= {};
    const src = source == null ? a.days : source === "vide" ? null : (a.weeks[source] || null);
    a.weeks[week] = DAYS.map((_, di) => (src ? src[di].map((r) => ({ ...r })) : [R(), R(), R()]));
  }
}
export function deleteWeek(prog, week) { for (const a of prog.aircraft) if (a.weeks) delete a.weeks[week]; }
export const defaultSettings = () => ({ escale_defaut: 45, escale_base: 60, max_jour: "13:00" });

// ------------------------------------------------------------------ calculs
export function computeDay(rots) {
  const out = rots.map((r) => {
    const c = { escale: null, duree: null, ecart: null, bloc: 0 };
    const a = safeTime(r.h_arrivee_escale), b = safeTime(r.h_redepart);
    if (a !== null && b !== null) c.escale = elapsed(a, b);
    const d = safeTime(r.h_depart), f = safeTime(r.h_arrivee);
    if (d !== null && f !== null) c.duree = elapsed(d, f);
    if (d !== null && a !== null) c.bloc += elapsed(d, a);
    if (b !== null && f !== null) c.bloc += elapsed(b, f);
    return c;
  });
  rots.forEach((r, i) => {
    const n = rots.slice(i + 1).find(hasFlight);
    if (n) { const f = safeTime(r.h_arrivee), d = safeTime(n.h_depart); if (f !== null && d !== null) out[i].ecart = d - f; }
  });
  return { rotations: out, total: out.reduce((s, c) => s + (c.duree || 0), 0), bloc: out.reduce((s, c) => s + c.bloc, 0) };
}
export const weekTotal = (ac) => ac.days.reduce((s, d) => s + computeDay(d).total, 0);
export const weekBloc = (ac) => ac.days.reduce((s, d) => s + computeDay(d).bloc, 0);
export const family = (title) => (up(title).split(/\s+/)[0] || "");

export function learnDurations(prog, ac = null) {
  let n = 0;
  for (const a of ac ? [ac] : prog.aircraft) {
    const acc = {};
    const add = (x, y, t1, t2) => {
      const ta = safeTime(t1), tb = safeTime(t2);
      if (up(x) && up(y) && ta !== null && tb !== null) (acc[`${up(x)}>${up(y)}`] ||= []).push(elapsed(ta, tb));
    };
    for (const day of a.days) for (const r of day) {
      add(r.depart, r.escale, r.h_depart, r.h_arrivee_escale);
      add(r.escale, r.arrivee, r.h_redepart, r.h_arrivee);
    }
    for (const [k, v] of Object.entries(acc)) a.durations[k] = pyRound(v.reduce((x, y) => x + y, 0) / v.length / 5) * 5;
    n += Object.keys(acc).length;
  }
  return n;
}
// arrondi « bancaire » comme Python round()
function pyRound(x) { const f = Math.floor(x), d = x - f; if (Math.abs(d - 0.5) < 1e-9) return f % 2 === 0 ? f : f + 1; return Math.round(x); }

export function getDuration(prog, ac, key) {
  if (key in ac.durations) return ac.durations[key];
  for (const o of prog.aircraft) if (o !== ac && family(o.title) === family(ac.title) && key in o.durations) return o.durations[key];
  return null;
}

// ------------------------------------------------------------------ profil appris
function mode(values) {
  if (!values.length) return null;
  const c = new Map(); values.forEach((v) => c.set(v, (c.get(v) || 0) + 1));
  let best = null, bc = -1;
  for (const v of values) if (c.get(v) > bc) { best = v; bc = c.get(v); }
  return best;
}
export function profile(prog, ac) {
  const fam = family(ac.title);
  const pool = [ac, ...prog.aircraft.filter((a) => a !== ac && family(a.title) === fam)];
  const others = prog.aircraft.filter((a) => !pool.includes(a));
  const esc = {}, routes = {}, dests = {}, freq = {}, firsts = [], gaps = [], remarks = [];
  for (const a of [...pool, ...others]) {
    const own = pool.includes(a);
    for (const day of a.days) {
      const dc = computeDay(day);
      const used = day.map((r, i) => [r, dc.rotations[i]]).filter(([r]) => isUsed(r));
      used.forEach(([r, c], k) => {
        for (const st of [r.depart, r.escale, r.arrivee]) if (up(st)) freq[up(st)] = (freq[up(st)] || 0) + (own ? 2 : 1);
        if (up(r.remarque) && !remarks.includes(up(r.remarque))) remarks.push(up(r.remarque));
        if (!own) return;
        if (up(r.escale) && c.escale !== null && c.escale >= 0 && c.escale <= 240) (esc[up(r.escale)] ||= []).push(c.escale);
        if (up(r.depart) && up(r.escale)) {
          (dests[up(r.depart)] ||= []).push(up(r.escale));
          if (up(r.arrivee)) (routes[`${up(r.depart)}>${up(r.escale)}`] ||= []).push(up(r.arrivee));
        }
        if (k === 0 && safeTime(r.h_depart) !== null) firsts.push(fmtTime(safeTime(r.h_depart)));
        if (c.ecart !== null && c.ecart >= 0 && c.ecart <= 120) gaps.push(c.ecart);
      });
    }
  }
  const byFreq = (l) => [...new Set(l)].sort((x, y) => (l.filter((v) => v === y).length - l.filter((v) => v === x).length) || x.localeCompare(y));
  const map = (o, f) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, f(v)]));
  const st = Object.keys(freq).sort((x, y) => (freq[y] - freq[x]) || x.localeCompare(y));
  const extra = [...new Set((prog.stations || []).map(up))].filter((x) => x && !(x in freq)).sort();
  return {
    escale_by_station: map(esc, mode), route_arrival: map(routes, mode), destinations: map(dests, byFreq),
    stations: [...st, ...extra], first_departure: mode(firsts), base_gap: mode(gaps), remarks,
  };
}
const prevUsed = (rots, ri) => [...rots.slice(0, ri)].reverse().find(hasFlight) || null;

export function suggest(prog, ac, di, ri, attr, prof = null) {
  prof = prof || profile(prog, ac);
  const rots = ac.days[di], r = rots[ri], prev = prevUsed(rots, ri), base = up(ac.base);
  let out = [];
  const dep = up(r.depart) || (prev ? up(prev.arrivee) : base);
  if (attr === "depart") out = [prev ? up(prev.arrivee) : base, base];
  else if (attr === "escale") {
    out = [...(prof.destinations[dep] || []), ...prof.stations.filter((x) => !(prof.destinations[dep] || []).includes(x))];
    let t = safeTime(r.h_depart);
    if (t === null && prev && safeTime(prev.h_arrivee) !== null) t = safeTime(prev.h_arrivee) + (prof.base_gap ?? prog.settings.escale_base);
    if (t === null && !prev && prof.first_departure) t = safeTime(prof.first_departure);
    const busy = busyDestinations(prog, ac, di, t);   // destinations déjà prises par un avion du même type : en dernier
    out = [...out.filter((x) => !busy.has(x)), ...out.filter((x) => busy.has(x))];
  }
  else if (attr === "arrivee") out = [prof.route_arrival[`${dep}>${up(r.escale)}`] || base, base];
  else if (attr === "remarque") return [...new Set([...prof.remarks, "MAINTENANCE", "CHARTER", "ANNULÉ", "RÉSERVE"])];
  else if (attr === "escale_mn") {
    const e = prof.escale_by_station[up(r.escale)];
    return [...new Set([...(e != null ? [e] : []), ac.escale_min, 30, 45, 60, 75, 90].map((v) => `${v} mn`))];
  } else if (attr === "ecart") {
    return [...new Set([...(prof.base_gap != null ? [prof.base_gap] : []), prog.settings.escale_base, ac.base_min, 45, 60, 75, 90, 120].map((v) => `${v} mn`))];
  } else if (TIME_CHAIN.includes(attr)) {
    const tmpAc = { ...ac, days: clone(ac.days) };
    tmpAc.days[di][ri][attr] = "";
    completeInternal(prog, tmpAc, di, ri, prof, [], ac);
    const v = tmpAc.days[di][ri][attr];
    return v ? [v] : [];
  }
  if (["depart", "escale", "arrivee"].includes(attr)) out = [...out, ...prof.stations.filter((s) => !out.includes(s))];
  return [...new Set(out.filter(Boolean))];
}

function completeInternal(prog, ac, di, ri, prof, missing, durAc = ac) {
  const s = prog.settings, rots = ac.days[di], r = rots[ri], prev = prevUsed(rots, ri);
  const label = `${DAYS[di]} rot. ${ri + 1}`;
  if (!up(r.escale)) { if (isUsed(r) && !up(r.remarque)) missing.push(`${label} : escale manquante`); return; }
  if (!up(r.depart)) r.depart = prev && up(prev.arrivee) ? up(prev.arrivee) : up(ac.base);
  if (!up(r.arrivee)) r.arrivee = prof.route_arrival[`${up(r.depart)}>${up(r.escale)}`] || up(ac.base);
  if (safeTime(r.h_depart) === null) {
    if (prev && safeTime(prev.h_arrivee) !== null) r.h_depart = fmtTime(safeTime(prev.h_arrivee) + (prof.base_gap ?? s.escale_base));
    else if (!prev && prof.first_departure) r.h_depart = prof.first_departure;
  }
  if (safeTime(r.h_depart) === null) { missing.push(`${label} : heure de départ manquante`); return; }
  const d1 = getDuration(prog, durAc, `${up(r.depart)}>${up(r.escale)}`);
  const d2 = getDuration(prog, durAc, `${up(r.escale)}>${up(r.arrivee)}`);
  if (safeTime(r.h_arrivee_escale) === null) {
    if (d1 !== null) r.h_arrivee_escale = fmtTime(safeTime(r.h_depart) + d1);
    else missing.push(`Durée de vol inconnue : ${up(r.depart)} → ${up(r.escale)}`);
  }
  if (safeTime(r.h_redepart) === null && safeTime(r.h_arrivee_escale) !== null) {
    const e = prof.escale_by_station[up(r.escale)];
    r.h_redepart = fmtTime(safeTime(r.h_arrivee_escale) + (e ?? s.escale_defaut));
  }
  if (safeTime(r.h_arrivee) === null && safeTime(r.h_redepart) !== null) {
    if (d2 !== null) r.h_arrivee = fmtTime(safeTime(r.h_redepart) + d2);
    else missing.push(`Durée de vol inconnue : ${up(r.escale)} → ${up(r.arrivee)}`);
  }
}
export function completeRotation(prog, ac, di, ri) { const m = []; completeInternal(prog, ac, di, ri, profile(prog, ac), m); return m; }
export function autocomplete(prog, ac) {
  const prof = profile(prog, ac), m = [];
  for (let di = 0; di < DAYS.length; di++) for (let ri = 0; ri < ROTATIONS_PER_DAY; ri++) completeInternal(prog, ac, di, ri, prof, m);
  return [...new Set(m)];
}
function shift(r, keys, delta) { for (const k of keys) { const t = safeTime(r[k]); if (t !== null) r[k] = fmtTime(t + delta); } }

export function applyChange(prog, ac, di, ri, attr, value) {
  const r = ac.days[di][ri], old = r[attr];
  r[attr] = value;
  if (attr === "escale" && up(value) !== up(old)) {
    if (up(old)) r.arrivee = "";
    r.h_arrivee_escale = r.h_redepart = r.h_arrivee = "";
  } else if (attr === "arrivee" && up(value) !== up(old)) r.h_arrivee = "";
  else if (attr === "depart" && up(value) !== up(old) && up(old)) r.h_arrivee_escale = r.h_redepart = r.h_arrivee = "";
  else if (TIME_CHAIN.includes(attr)) {
    const o = safeTime(old), n = safeTime(value);
    if (o !== null && n !== null && o !== n) shift(r, TIME_CHAIN.slice(TIME_CHAIN.indexOf(attr) + 1), n - o);
  }
  if (!up(r.escale)) return [];
  return completeRotation(prog, ac, di, ri);
}
export function setEscale(prog, ac, di, ri, minutes) {
  const r = ac.days[di][ri], a = safeTime(r.h_arrivee_escale);
  if (a === null) return;
  const old = safeTime(r.h_redepart);
  r.h_redepart = fmtTime(a + minutes);
  if (old !== null) shift(r, ["h_arrivee"], a + minutes - old); else completeRotation(prog, ac, di, ri);
}
export function setGap(prog, ac, di, ri, minutes) {
  const rots = ac.days[di], r = rots[ri], t = safeTime(r.h_arrivee);
  let nj = null; for (let j = ri + 1; j < rots.length; j++) if (isUsed(rots[j])) { nj = j; break; }
  if (t === null || nj === null) return;
  const n = rots[nj], old = safeTime(n.h_depart);
  n.h_depart = fmtTime(t + minutes);
  if (old !== null) shift(n, TIME_CHAIN.slice(1), t + minutes - old);
  completeRotation(prog, ac, di, nj);
}
export function missingDurations(prog, ac, di, ri) {
  const r = ac.days[di][ri], out = [];
  for (const [a, b] of [[r.depart, r.escale], [r.escale, r.arrivee]])
    if (up(a) && up(b) && getDuration(prog, ac, `${up(a)}>${up(b)}`) === null) out.push([up(a), up(b)]);
  return out;
}

// ------------------------------------------------------------------ conflits entre avions du même type
// Règle : deux avions du même type (même début de nom) ne vont pas à la même destination au même moment le même jour.
const sameWeekDays = (o, week) => (week ? (original(o).weeks || {})[week] : original(o).days);
function destinationsOf(r, base) {
  const d = new Set();
  if (up(r.escale)) d.add(up(r.escale));
  if (up(r.arrivee) && up(r.arrivee) !== up(base)) d.add(up(r.arrivee));
  return d;
}
function windowOf(r) { const s = safeTime(r.h_depart), e = safeTime(r.h_arrivee); return s === null || e === null ? null : [s, e < s ? e + 1440 : e]; }
export function conflicts(prog, ac, di, ri) {
  const out = [], r = ac.days[di][ri], w = windowOf(r);
  if (!hasFlight(r) || !w) return out;
  const dest = destinationsOf(r, ac.base);
  for (const o of prog.aircraft) {
    if (original(o) === original(ac) || family(o.title) !== family(ac.title)) continue;
    const days = sameWeekDays(o, ac.week); if (!days) continue;
    for (const q of days[di]) {
      const v = windowOf(q);
      if (!hasFlight(q) || !v || !(w[0] < v[1] && v[0] < w[1])) continue;
      const d = [...destinationsOf(q, o.base)].find((x) => dest.has(x));
      if (d) out.push([o.title, d, fmtTime(v[0]), fmtTime(v[1])]);
    }
  }
  return out;
}
function busyDestinations(prog, ac, di, t) {
  const busy = new Set(); if (t === null || t === undefined) return busy;
  for (const o of prog.aircraft) {
    if (original(o) === original(ac) || family(o.title) !== family(ac.title)) continue;
    const days = sameWeekDays(o, ac.week); if (!days) continue;
    for (const q of days[di]) { const v = windowOf(q); if (v && (Math.abs(v[0] - t) <= 90 || (t >= v[0] && t < v[1]))) destinationsOf(q, o.base).forEach((x) => busy.add(x)); }
  }
  return busy;
}

// ------------------------------------------------------------------ compteur de fréquences
/** Fréquences par destination : nombre de rotations qui la desservent, par jour [0..6] et total [7]. */
export function frequencies(list) {
  const c = {}, det = {};
  for (const a of list) a.days.forEach((rots, di) => rots.forEach((r) => {
    if (!hasFlight(r)) return;
    for (const d of destinationsOf(r, a.base)) {
      (c[d] ||= Array(8).fill(0))[di]++; c[d][7]++;
      ((det[d] ||= DAYS.map(() => []))[di]).push(`${a.title} ${r.h_depart || "?"}`);
    }
  }));
  const keys = Object.keys(c).sort((x, y) => (c[y][7] - c[x][7]) || (x < y ? -1 : x > y ? 1 : 0));
  const totals = Array(8).fill(0);
  keys.forEach((k) => c[k].forEach((v, i) => { totals[i] += v; }));
  return { rows: keys.map((k) => ({ dest: k, counts: c[k], details: det[k] })), totals };
}

// ------------------------------------------------------------------ contrôles
export function check(prog, ac) {
  const w = []; let maxDay = null;
  try { maxDay = parseTime(prog.settings.max_jour); } catch { /* ignoré */ }
  ac.days.forEach((rots, di) => {
    const dc = computeDay(rots);
    rots.forEach((r, ri) => {
      if (!hasFlight(r)) return;
      const L = `${DAYS[di]} rot. ${ri + 1}`;
      for (const [k, name] of [["h_depart", "départ"], ["h_arrivee_escale", "arrivée escale"], ["h_redepart", "redépart"], ["h_arrivee", "arrivée"]]) {
        if (!String(r[k] || "").trim()) w.push(`${L} : heure de ${name} manquante`);
        else if (safeTime(r[k]) === null) w.push(`${L} : heure de ${name} invalide (${r[k]})`);
      }
      const c = dc.rotations[ri];
      for (const [x, y, t1, t2] of [[r.depart, r.escale, r.h_depart, r.h_arrivee_escale], [r.escale, r.arrivee, r.h_redepart, r.h_arrivee]]) {
        const a1 = safeTime(t1), a2 = safeTime(t2), known = getDuration(prog, ac, `${up(x)}>${up(y)}`);
        if (a1 === null || a2 === null || known === null || !up(x) || !up(y)) continue;
        const dur = elapsed(a1, a2);
        if (Math.abs(dur - known) > Math.max(20, known * 0.35)) w.push(`${L} : durée de vol anormale ${up(x)} → ${up(y)} ${fmtDuration(dur)} (habituel ${fmtDuration(known)})`);
      }
      if (c.escale !== null && c.escale < ac.escale_min) w.push(`${L} : escale à ${up(r.escale)} trop courte (${c.escale} mn)`);
      if (c.ecart !== null) {
        if (c.ecart < 0) w.push(`${L} : chevauchement avec la rotation suivante`);
        else if (c.ecart < ac.base_min) w.push(`${L} : escale à la base trop courte (${c.ecart} mn)`);
      }
      for (const [t, d, a, b] of conflicts(prog, ac, di, ri)) w.push(`${L} : même destination que ${t} (${d} ${a}–${b}) au même moment`);
      const n = rots.slice(ri + 1).find(hasFlight);
      if (n && up(n.depart) && up(r.arrivee) && up(n.depart) !== up(r.arrivee)) w.push(`${L} : arrive à ${up(r.arrivee)} mais la suivante part de ${up(n.depart)}`);
    });
    if (maxDay !== null && dc.total > maxDay) w.push(`${DAYS[di]} : total ${fmtDuration(dc.total)} > maximum ${prog.settings.max_jour}`);
  });
  return w;
}

// ------------------------------------------------------------------ données des feuilles papier
export function defaultProgram(semaineType = true) {
  const erj = emptyWeek();
  erj[0] = [R("DAKHLA", "07:00", "AGADIR", "08:15", "09:00", "DAKHLA", "10:35"), R("DAKHLA", "11:35", "DAKAR", "13:25", "14:25", "DAKHLA", "16:15"), R("DAKHLA", "17:15", "NOUADHIBOU", "18:05", "19:05", "DAKHLA", "19:55")];
  erj[2] = [R("DAKHLA", "07:00", "LAAYOUNE", "08:00", "08:45", "LAS PALMAS", "09:45"), R("LAS PALMAS", "10:45", "LAAYOUNE", "11:45", "12:30", "DAKHLA", "13:30"), R("DAKHLA", "14:30", "GUELMIM", "15:45", "16:30", "DAKHLA", "17:45")];
  erj[4] = [R("DAKHLA", "07:00", "LAS PALMAS", "07:55", "08:55", "DAKHLA", "09:55"), R("DAKHLA", "10:55", "NOUADHIBOU", "11:40", "12:40", "DAKHLA", "13:25"), R("DAKHLA", "14:25", "OUARZAZATE", "15:50", "16:40", "DAKHLA", "18:20")];
  erj[5] = [R("DAKHLA", "07:00", "NOUAKCHOTT", "08:15", "09:15", "DAKHLA", "10:30"), R("DAKHLA", "11:30", "BAMAKO", "14:25", "15:25", "DAKHLA", "18:10"), R("DAKHLA", "20:10", "NOUADHIBOU", "21:00", "21:45", "DAKHLA", "22:35", "MAINTENANCE")];
  erj[6] = [R("DAKHLA", "08:00", "AGADIR", "09:30", "10:15", "DAKHLA", "11:45"), R("DAKHLA", "12:45", "NOUADHIBOU", "13:40", "14:40", "DAKHLA", "15:35"), R("DAKHLA", "16:15", "LAS PALMAS", "17:10", "18:10", "DAKHLA", "19:03")];
  const pax = emptyWeek();
  pax[0] = [R("DAKHLA", "08:00", "GUELMIM", "09:25", "09:55", "LAS PALMAS", "11:20"), R("LAS PALMAS", "12:20", "GUELMIM", "13:45", "14:15", "DAKHLA", "15:40"), R("DAKHLA", "17:00", "AGADIR", "19:25", "19:55", "DAKHLA", "22:20")];
  pax[2] = [R("DAKHLA", "08:00", "NOUADHIBOU", "09:05", "09:35", "DAKHLA", "10:40"), R("DAKHLA", "11:40", "LAS PALMAS", "12:50", "13:20", "DAKHLA", "14:30"), R("DAKHLA", "15:30", "NOUAKCHOTT", "17:20", "17:50", "DAKHLA", "19:40")];
  pax[4] = [R("DAKHLA", "07:00", "SMARA", "08:25", "08:55", "DAKHLA", "10:20"), R("DAKHLA", "12:40", "LAAYOUNE", "13:50", "14:20", "DAKHLA", "15:30"), R("DAKHLA", "16:30", "AGADIR", "18:55", "19:25", "DAKHLA", "21:50")];
  pax[5] = [R("DAKHLA", "07:00", "GUELMIM", "08:25", "08:55", "LAS PALMAS", "10:20"), R("LAS PALMAS", "11:20", "GUELMIM", "12:45", "13:15", "DAKHLA", "14:40"), R("DAKHLA", "19:50", "NOUADHIBOU", "20:55", "21:25", "DAKHLA", "22:30")];
  pax[6] = [R("DAKHLA", "07:00", "LAS PALMAS", "08:10", "08:40", "DAKHLA", "09:50"), R("DAKHLA", "10:50", "MARRAKECH", "13:20", "13:50", "DAKHLA", "16:20"), R("DAKHLA", "17:20", "NOUAKCHOTT", "19:10", "19:40", "DAKHLA", "21:30")];
  const prog = {
    aircraft: [Aircraft("ERJ.145", "DAKHLA", erj), Aircraft("ERJ.145 - AVION 2"), Aircraft("BEECHCRAFT 1900 CARGO"), Aircraft("BEECHCRAFT 1900 PASSAGERS", "DAKHLA", pax)],
    settings: defaultSettings(), defaults_seen: [], stations: [...DEFAULT_STATIONS],
  };
  learnDurations(prog);
  prog.defaults_seen = prog.aircraft.map((a) => a.title);
  if (semaineType) { ensureSemaineType(prog); ensureErj2Rot3(prog); }
  return prog;
}

// « Semaine type » Dakhla Air Connect : jours vides seulement, ou remplacement complet
export function applySemaineType(prog, replaceAll = false) {
  const report = [];
  for (const [title, days] of Object.entries(SEMAINE_TYPE)) {
    const ac = prog.aircraft.find((a) => up(a.title) === up(title));
    if (!ac) continue;
    const filled = [];
    days.forEach((rots, di) => {
      if (!replaceAll && ac.days[di].some(isUsed)) return;
      const nw = rots.map((r) => R(...r));
      while (nw.length < ROTATIONS_PER_DAY) nw.push(R());
      ac.days[di] = nw;
      filled.push(DAYS[di].charAt(0) + DAYS[di].slice(1).toLowerCase());
    });
    if (filled.length) report.push(`${ac.title} : ${filled.length < 7 ? filled.join(", ") : "toute la semaine"}`);
  }
  return report;
}
// Feuille « ERJ.145 - AVION 2 » : 3e rotation du programme par défaut (ajoutée à la main)
export const ERJ2_ROT3_MARKER = "ERJ2_ROT3_2026_11";
export const ERJ2_ROT3 = [
  ["DAKHLA", "14:25", "TENERIFE", "15:30", "16:30", "DAKHLA", "17:35"], ["DAKHLA", "15:35", "OUARZAZATE", "17:00", "17:40", "DAKHLA", "19:20"],
  ["DAKHLA", "16:45", "NOUAKCHOTT", "18:00", "19:00", "DAKHLA", "20:15"], ["DAKHLA", "14:45", "AGADIR", "16:15", "16:55", "DAKHLA", "18:30"],
  null, ["DAKHLA", "16:45", "MARRAKECH", "18:30", "19:10", "DAKHLA", "20:45"], ["DAKHLA", "15:55", "LAAYOUNE", "16:00", "16:40", "DAKHLA", "17:45"]];
export function ensureErj2Rot3(prog) {
  const out = [];
  if (prog.defaults_seen.includes(ERJ2_ROT3_MARKER)) return out;
  prog.defaults_seen.push(ERJ2_ROT3_MARKER);
  for (const a of prog.aircraft) {
    if (up(a.title) !== "ERJ.145 - AVION 2") continue;
    ERJ2_ROT3.forEach((add, di) => {
      if (!add) return;
      const d = a.days[di], slot = d.findIndex((r) => !isUsed(r));
      if (slot < 0 || d.some((r) => up(r.escale) === add[2] && r.h_depart === add[1])) return;
      d[slot] = R(...add);
      out.push(DAYS[di].charAt(0) + DAYS[di].slice(1).toLowerCase());
    });
  }
  return out;
}
/** Copie les jours d'un programme vers un autre avion (recompute : heures recalculées avec les durées de l'avion cible). */
export function cloneDays(prog, src, dst, recompute) {
  const missing = [];
  for (let di = 0; di < DAYS.length; di++) dst.days[di] = src.days[di].map((r) => ({ ...r }));
  if (recompute) {
    const prof = profile(prog, dst);
    dst.days.forEach((d, di) => {
      let first = true;
      d.forEach((r, ri) => {
        if (!hasFlight(r) || !up(r.escale)) return;
        if (!first) r.h_depart = "";
        r.h_arrivee_escale = r.h_redepart = r.h_arrivee = "";
        completeInternal(prog, dst, di, ri, prof, missing);
        first = false;
      });
    });
  }
  return [...new Set(missing)];
}
export function newAircraft(prog, title, base, model) {
  const a = Aircraft(up(title), up(base));
  if (model) { const m = original(model); a.durations = { ...m.durations }; a.escale_min = m.escale_min; a.base_min = m.base_min; }
  prog.aircraft.push(a);
  return a;
}
export function ensureSemaineType(prog) {
  if (prog.defaults_seen.includes(SEMAINE_TYPE_MARKER)) return [];
  prog.defaults_seen.push(SEMAINE_TYPE_MARKER);
  return applySemaineType(prog, false);
}
export function textSummary(prog, aircraft) {
  const L = [];
  for (const ac of aircraft) {
    L.push(`*${ac.title} – ${ac.week ? weekShort(ac.week) : "programme de la semaine"}*`);
    ac.days.forEach((rots, di) => {
      const dc = computeDay(rots), used = rots.map((r, ri) => [ri, r]).filter(([, r]) => isUsed(r));
      if (!used.length) return;
      L.push(`_${dayLabel(ac.week, di)}_`);
      for (const [ri, r] of used) {
        if (!hasFlight(r)) { L.push(`  ${up(r.remarque)}`); continue; }
        const c = dc.rotations[ri];
        let t = `  ${up(r.depart)} ${r.h_depart} > ${up(r.escale)} ${r.h_arrivee_escale}/${r.h_redepart} > ${up(r.arrivee)} ${r.h_arrivee}`;
        if (c.duree !== null) t += ` (${fmtDuration(c.duree)})`;
        if (up(r.remarque)) t += ` – ${up(r.remarque)}`;
        L.push(t);
      }
      L.push(`  Total : ${fmtDuration(dc.total)} – bloc ${fmtDuration(dc.bloc)}`);
    });
    L.push(`Total semaine : ${fmtDuration(weekTotal(ac))} – bloc de vol : ${fmtDuration(weekBloc(ac))}`);
    L.push("");
  }
  return L.join("\n").trim();
}
/** Programme par défaut d'une nouvelle installation (fourni le 13/11/2026) – modifiable ensuite. */
export function factoryProgram() {
  const prog = { aircraft: PROGRAMME_DEFAUT.map((a) => Aircraft(a.title, "DAKHLA", a.days.map((d) => {
      const rots = d.map((r) => R(...r)); while (rots.length < ROTATIONS_PER_DAY) rots.push(R()); return rots; }))),
    settings: defaultSettings(), defaults_seen: [], stations: [...DEFAULT_STATIONS] };
  learnDurations(prog);
  prog.defaults_seen = [...prog.aircraft.map((a) => a.title), SEMAINE_TYPE_MARKER, ERJ2_ROT3_MARKER];
  return prog;
}

export function addMissingDefaults(prog) {
  const d = defaultProgram(), titles = new Set(prog.aircraft.map((a) => up(a.title))), added = [];
  d.aircraft.forEach((ref, i) => {
    if (prog.defaults_seen.includes(ref.title)) return;
    prog.defaults_seen.push(ref.title);
    if (!titles.has(up(ref.title))) { prog.aircraft.splice(Math.min(i, prog.aircraft.length), 0, ref); added.push(ref.title); }
  });
  return added;
}
export function fillEmptyFromDefaults(prog) {
  const d = defaultProgram(false), done = [];
  for (const ref of d.aircraft) {
    if (!ref.days.flat().some(isUsed)) continue;
    for (const ac of prog.aircraft) if (up(ac.title) === up(ref.title) && !ac.days.flat().some(isUsed)) {
      ac.days = clone(ref.days); learnDurations(prog, ac); done.push(ac.title);
    }
  }
  return done;
}

// ------------------------------------------------------------------ sérialisation (format commun avec le logiciel Windows)
export function toDict(prog) {
  return {
    schema: SCHEMA_VERSION, saved_at: new Date().toISOString().slice(0, 19), settings: { ...prog.settings },
    defaults_seen: [...prog.defaults_seen], stations: [...prog.stations],
    aircraft: prog.aircraft.map((a) => ({ title: a.title, base: a.base, durations: { ...a.durations }, escale_min: a.escale_min, base_min: a.base_min,
      days: daysOut(a.days), weeks: Object.fromEntries(Object.keys(a.weeks || {}).sort().map((w) => [w, daysOut(a.weeks[w])])) })),
  };
}
const daysOut = (days) => days.map((d) => d.map((r) => Object.fromEntries(ROT_FIELDS.map((k) => [k, r[k] ?? ""]))));
function daysIn(raw0) {
  return DAYS.map((_, di) => {
    const raw = (raw0 || [])[di] || [];
    const rots = raw.slice(0, ROTATIONS_PER_DAY).map((r) => Object.fromEntries(ROT_FIELDS.map((k) => [k, String(r?.[k] ?? "")])));
    while (rots.length < ROTATIONS_PER_DAY) rots.push(R());
    return rots;
  });
}
export function fromDict(d) {
  if (!d || typeof d !== "object" || !Array.isArray(d.aircraft)) throw new Error("Fichier de programme invalide.");
  const aircraft = d.aircraft.map((a) => {
    const days = DAYS.map((_, di) => {
      const raw = (a.days || [])[di] || [];
      const rots = raw.slice(0, ROTATIONS_PER_DAY).map((r) => Object.fromEntries(ROT_FIELDS.map((k) => [k, String(r?.[k] ?? "")])));
      while (rots.length < ROTATIONS_PER_DAY) rots.push(R());
      return rots;
    });
    const durs = Object.fromEntries(Object.entries(a.durations || {}).map(([k, v]) => [String(k), parseInt(v, 10)]));
    const ac = Aircraft(String(a.title ?? "AVION"), String(a.base ?? ""), days, durs, parseInt(a.escale_min ?? 45, 10), parseInt(a.base_min ?? 45, 10));
    for (const [w, wd] of Object.entries(a.weeks || {})) if (/^\d{4}-\d{2}-\d{2}$/.test(w)) ac.weeks[w] = daysIn(wd);
    return ac;
  });
  const settings = defaultSettings();
  for (const k of Object.keys(settings)) if (d.settings && k in d.settings) settings[k] = d.settings[k];
  const prog = { aircraft, settings, defaults_seen: [...(d.defaults_seen || aircraft.map((a) => a.title))],
    stations: d.stations ? [...new Set(d.stations.map(up).filter(Boolean))] : [...DEFAULT_STATIONS] };
  if ("durations" in d) for (const ac of prog.aircraft) if (!Object.keys(ac.durations).length) learnDurations(prog, ac);
  return prog;
}

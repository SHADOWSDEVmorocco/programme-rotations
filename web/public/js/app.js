// Interface web du Programme des Rotations.
import * as C from "./core.js";
import { icon } from "./icons.js";
import { createStore, isAndroid } from "./storage.js";
import { buildPdf, toBase64 } from "./pdf.js";

const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const COLS = [ // [titre, attribut, type, classe d'en-tête]
  ["Jour", null, "day"], ["Rot.", null, "num"],
  ["Départ", "depart", "station"], ["Heure", "h_depart", "time"],
  ["Escale", "escale", "station"], ["Arrivée", "h_arrivee_escale", "time"],
  ["Escale (mn)", "escale_mn", "esc", "y"], ["Redépart", "h_redepart", "time"],
  ["Arrivée", "arrivee", "station"], ["Heure", "h_arrivee", "time"],
  ["Remarque", "remarque", "text"],
  ["Durée rot.", "duree", "calc", "g"], ["Esc. base", "ecart", "gap", "b"], ["Total jour", "total", "calc", "b"],
];

let store, prog, cur = 0, undoStack = [], redoStack = [], clipDay = null, saveTimer = null;
// Semaine affichée : null = programme type (sans date), sinon le lundi « AAAA-MM-JJ » (mémorisée sur cet appareil)
let curWeek = localStorage.getItem("rotations-semaine") || null;

// ------------------------------------------------------------------ démarrage
async function start() {
  $("#brandIcon").innerHTML = icon("plane", 22);
  store = createStore();
  try {
    await store.init(askLogin);
    let data = store.mode === "firebase" ? await store.loadRemote() : store.load();
    prog = data ? C.fromDict(data) : C.factoryProgram();
    const added = C.addMissingDefaults(prog);
    const semType = data ? C.ensureSemaineType(prog) : [];
    const rot3 = data ? C.ensureErj2Rot3(prog) : [];
    if (rot3.length) { scheduleSave(); setTimeout(() => toast("ERJ.145 - AVION 2 : 3e rotation ajoutée au programme par défaut"), 1500); }
    if (!data) await store.save(C.toDict(prog), { initial: true });
    else if (added.length || semType.length) scheduleSave();
    if (semType.length) setTimeout(() => infoDlg("Semaine type intégrée", `Programme « Dakhla Air Connect – semaine type » ajouté dans les jours vides :<ul>${semType.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>Les jours déjà remplis n'ont pas été modifiés. Pour tout remplacer : Fichier &amp; avions → Semaine type.`), 600);
    if (added.length) toast("Nouvel avion ajouté : " + added.join(", "));
  } catch (e) {
    console.error(e);
    document.body.innerHTML = `<div style="padding:30px;font:16px Arial">Erreur au démarrage : ${esc(e.message)}<br><br>
      Vérifiez la connexion internet et le fichier <b>js/firebase-config.js</b>.</div>`;
    return;
  }
  const g = $("#grid");   // écouteurs posés une seule fois
  g.addEventListener("focusin", onFocus);
  g.addEventListener("change", (e) => { if (e.target.dataset.cell) commit(e.target); });
  g.addEventListener("keydown", onKey);
  g.addEventListener("input", onInput);
  g.addEventListener("focusout", onBlur);
  g.addEventListener("click", (e) => { if (e.target.dataset?.cell && e.target === document.activeElement) { sugg.inp = e.target; showSugg(e.target); } });
  g.addEventListener("click", onDayButton);
  buildToolbar(); renderUser(); renderAll();
  store.subscribe((data, by) => {
    try { prog = C.fromDict(data); cur = Math.min(cur, prog.aircraft.length - 1); renderAll(true); toast(`Programme mis à jour par ${by}`); }
    catch (e) { console.error(e); }
  }, setSync, conflictDlg);
  window.__androidBack = () => { const d = $("#dlg"); if (d.open) { d.close(); return true; } return false; };
  window.__flush = flushSave;
  window.addEventListener("beforeunload", (e) => { if (saveTimer) { e.preventDefault(); e.returnValue = ""; } });
}

// ------------------------------------------------------------------ connexion
function askLogin(error) {
  return new Promise((resolve) => {
    const d = $("#dlg");
    d.innerHTML = `<form class="login" method="dialog">
      <div class="dh">${icon("plane")} Programme des Rotations</div>
      <div class="db">
        <p>Connectez-vous avec l'adresse e-mail autorisée par l'administrateur.</p>
        <div class="row"><label>E-mail</label></div><input type="email" id="lgE" required autocomplete="username">
        <div class="row"><label>Mot de passe</label></div><input type="password" id="lgP" required autocomplete="current-password">
        <div class="err">${esc(error || "")}</div>
        <a href="#" id="lgReset" style="font-size:12px">Mot de passe oublié ?</a>
      </div>
      <div class="df"><button class="btn primary" type="submit">Se connecter</button></div></form>`;
    d.addEventListener("cancel", (e) => e.preventDefault(), { once: true });
    $("#lgReset", d).onclick = async (e) => {
      e.preventDefault();
      const em = $("#lgE", d).value.trim();
      if (!em) { $(".err", d).textContent = "Tapez d'abord votre adresse e-mail."; return; }
      try { await store.resetPassword(em); $(".err", d).textContent = "E-mail de réinitialisation envoyé."; }
      catch { $(".err", d).textContent = "Envoi impossible (adresse inconnue ?)."; }
    };
    $("form", d).onsubmit = () => { const v = { email: $("#lgE", d).value.trim(), password: $("#lgP", d).value }; d.close(); resolve(v); };
    d.showModal();
  });
}
function renderUser() {
  const box = $("#userBox");
  if (store.mode === "sync") {
    box.innerHTML = `<button class="btn outline" id="pcBtn" title="Synchronisation avec le PC (même Wi-Fi)">${icon("wifi", 18)}<span class="sync warn" id="sync">${esc(store.state)}</span></button>`;
    $("#pcBtn").onclick = dlgPc;
    if (!store.configured && !localStorage.getItem("rotations-pc-asked")) { localStorage.setItem("rotations-pc-asked", "1"); setTimeout(dlgPc, 400); }
    return;
  }
  if (store.mode === "local") {
    box.innerHTML = `<span class="sync warn" title="Firebase non configuré : données enregistrées dans ce navigateur uniquement">Mode local</span>`;
  } else {
    box.innerHTML = `<span class="sync" id="sync">connexion…</span><span>${esc(store.user.email)}</span>
      <button class="btn" id="logout" title="Se déconnecter">${icon("exit", 18)}</button>`;
    $("#logout").onclick = () => store.logout();
  }
}
function setSync(s) {
  const el = $("#sync"); if (!el) return;
  let txt = s;
  if (store.mode === "sync") txt = s === "synchronisé" ? "Synchronisé avec le PC" : s === "non configuré" ? "PC non relié" : s.charAt(0).toUpperCase() + s.slice(1);
  el.textContent = txt;
  el.className = "sync" + (s.startsWith("erreur") ? " err" : s === "synchronisé" ? "" : " warn");
}
function dlgPc() {
  const c = store.cfg;
  const d = openDlg("Synchronisation avec le PC", "wifi", `
    <p style="margin-top:0">La tablette et le PC doivent être sur le <b>même Wi-Fi</b>, avec le logiciel ouvert sur le PC.<br>
    Sur le PC : menu <b>Synchronisation → Tablette / Wi-Fi</b> affiche l'adresse et le code.</p>
    <div class="row"><label>Adresse du PC</label><input id="pcU" value="${esc((c.url || "").replace(/^http:\/\//, ""))}" placeholder="192.168.1.20:8765" style="width:200px" ${store.servedByPc ? "disabled" : ""}></div>
    <div class="row"><label>Code</label><input id="pcC" value="${esc(c.code || "")}" inputmode="numeric" style="width:200px"></div>
    <div class="row"><label>Nom de cet appareil</label><input id="pcN" value="${esc(c.device || "")}" style="width:200px"></div>
    ${isAndroid() ? `<button class="btn outline" id="pcScan">${icon("cities", 16)} Rechercher le PC sur le Wi-Fi</button><div id="pcFound"></div>` : ""}
    <div class="err" id="pcMsg"></div>`,
    `${c.url && !store.servedByPc ? `<button class="btn outline" id="pcOff">Délier</button>` : ""}<span style="flex:1"></span>
     <button class="btn outline" id="no">Fermer</button><button class="btn primary" id="yes">Tester et enregistrer</button>`);
  const msg = (t, ok) => { const m = $("#pcMsg", d); m.textContent = t; m.style.color = ok ? "#1e8449" : "#b03a2e"; };
  $("#no", d).onclick = () => d.close();
  if ($("#pcOff", d)) $("#pcOff", d).onclick = () => { store.disconnect(); d.close(); };
  if ($("#pcScan", d)) $("#pcScan", d).onclick = async () => {
    msg("Recherche en cours (quelques secondes)…", true);
    const found = await store.scan();
    if (!found.length) { msg("Aucun PC trouvé. Vérifiez le Wi-Fi et que le logiciel est ouvert sur le PC."); return; }
    msg(found.length + " PC trouvé(s) : touchez pour choisir.", true);
    $("#pcFound", d).innerHTML = found.map((f) => `<button class="btn outline" data-ip="${esc(f.ip)}">${icon("wifi", 16)} ${esc(f.pc || "PC")} – ${esc(f.ip)}</button>`).join(" ");
    $("#pcFound", d).onclick = (e) => { const b = e.target.closest("button"); if (b) $("#pcU", d).value = b.dataset.ip + ":8765"; };
  };
  $("#yes", d).onclick = async () => {
    const url = store.servedByPc ? c.url : $("#pcU", d).value, code = $("#pcC", d).value;
    if (!url.trim() || !code.trim()) { msg("Indiquez l'adresse et le code affichés sur le PC."); return; }
    msg("Connexion…", true);
    const r = await store.ping(url, code);
    if (!r.ok) { msg(r.msg); return; }
    store.setPc(url, code, $("#pcN", d).value.trim() || c.device);
    msg("Relié à " + r.pc + ". Synchronisation en cours.", true);
    setTimeout(() => d.close(), 900);
  };
}
function conflictDlg(srv) {
  return new Promise((resolve) => {
    const d = openDlg("Modifications des deux côtés", "warning", `
      <p style="margin-top:0">Le programme a été modifié <b>sur le PC</b> (par ${esc(srv.modifiePar || "le PC")}) <b>et sur cet appareil</b> depuis la dernière synchronisation.</p>
      <p>Quelle version garder ? L'autre est conservée dans les sauvegardes.</p>`,
      `<button class="btn outline" id="kPc">${icon("open", 16)} Prendre la version du PC</button>
       <button class="btn primary" id="kMe">${icon("save", 16)} Garder la version de cet appareil</button>`);
    d.oncancel = (e) => e.preventDefault();
    const backup = (dict) => { try { localStorage.setItem(`programme-rotations-web-v1-sauvegarde-conflit-${new Date().toISOString().slice(0, 16)}`, JSON.stringify(dict)); } catch { } };
    $("#kPc", d).onclick = () => { backup(C.toDict(prog)); d.close(); resolve("pc"); };
    $("#kMe", d).onclick = () => { try { backup(JSON.parse(srv.json)); } catch { } d.close(); resolve("me"); };
  });
}

// ------------------------------------------------------------------ barre d'outils
function buildToolbar() {
  const items = [
    ["auto", "Compléter", "Compléter automatiquement toutes les cases vides (F5)", doAutocomplete],
    ["print", "Imprimer", "Imprimer le tableau affiché (Ctrl+P)", () => doPrint(false)],
    ["pdf", "Imprimer tout", "Imprimer / PDF de tous les avions", () => doPrint(true)],
    ["send", "Envoyer", "Envoyer le programme par WhatsApp ou e-mail", dlgSend], "|",
    ["cities", "Fréquences", "Compteur : nombre de fréquences par jour et par destination", dlgFrequencies],
    ["plane", "Ajouter un avion", "Ajouter un avion (nom, base, type, programme de départ)", dlgAddAircraft],
    ["copy", "Cloner", "Copier le programme affiché sur d'autres avions", dlgClone], "|",
    ["undo", "Annuler", "Annuler (Ctrl+Z)", undo], ["redo", "Rétablir", "Rétablir (Ctrl+Y)", redo], "|",
    ["clock", "Durées de vol", "Durées de vol de l'avion affiché", dlgDurations],
    ["cities", "Villes", "Villes proposées dans les listes", dlgCities],
    ["settings", "Paramètres", "Paramètres", dlgSettings],
    ["folder", "Fichier & avions", "Ajouter un avion, importer / exporter, sauvegardes…", dlgMore],
    ["help", "Aide", "Mode d'emploi (F1)", dlgHelp],
  ];
  $("#toolbar").innerHTML = items.map((it, i) => it === "|" ? `<span class="sep"></span>`
    : `<button data-i="${i}" title="${esc(it[2])}">${icon(it[0])}<span>${esc(it[1])}</span></button>`).join("");
  $("#toolbar").onclick = (e) => { const b = e.target.closest("button"); if (b) items[+b.dataset.i][3](); };
  document.addEventListener("keydown", (e) => {
    const inInput = /INPUT|TEXTAREA/.test(document.activeElement?.tagName) && !document.activeElement.closest(".grid");
    if (e.key === "F5") { e.preventDefault(); doAutocomplete(); }
    else if (e.key === "F1") { e.preventDefault(); dlgHelp(); }
    else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "p") { e.preventDefault(); doPrint(false); }
    else if ((e.ctrlKey || e.metaKey) && !inInput && e.key.toLowerCase() === "z") { e.preventDefault(); undo(); }
    else if ((e.ctrlKey || e.metaKey) && !inInput && e.key.toLowerCase() === "y") { e.preventDefault(); redo(); }
  });
}

// ------------------------------------------------------------------ rendu
const A = () => C.view(prog.aircraft[cur], curWeek);
function renderAll(keepFocus = false) {
  const focused = keepFocus && document.activeElement?.dataset?.cell;
  if (curWeek && !C.weeks(prog).includes(curWeek)) setWeek(null, false);
  renderWeekBar(); renderTabs(); renderHead(); buildGrid(); renderChecks();
  if (focused) { const el = document.querySelector(`[data-cell="${focused}"]`); el?.focus(); }
}
// ------------------------------------------------------------------ semaines datées / historique
function setWeek(w, render = true) {
  curWeek = w || null;
  if (curWeek) localStorage.setItem("rotations-semaine", curWeek); else localStorage.removeItem("rotations-semaine");
  if (render) renderAll();
}
function renderWeekBar() {
  const ws = C.weeks(prog).reverse(), tw = C.thisWeek();
  const lab = (w) => C.weekLabel(w) + (w === tw ? " (cette semaine)" : w && w < tw ? " (historique)" : "");
  $("#weekbar").innerHTML = `<b>Programme :</b>
    <button class="arrow" id="wPrev" title="Semaine précédente">◀</button>
    <select id="wSel" class="${curWeek ? "dated" : ""}">${[null, ...ws].map((w) => `<option value="${w || ""}" ${w === curWeek ? "selected" : ""}>${esc(lab(w))}</option>`).join("")}</select>
    <button class="arrow" id="wNext" title="Semaine suivante">▶</button>
    <button class="btn outline" id="wNew">${icon("clock", 16)} Nouvelle semaine</button>
    <button class="btn outline" id="wHist">${icon("folder", 16)} Historique</button>`;
  $("#wSel").onchange = (e) => setWeek(e.target.value || null);
  const step = (n) => { const t = curWeek ? C.shiftWeek(curWeek, n) : tw; if (C.weeks(prog).includes(t)) setWeek(t); else dlgNewWeek(t, curWeek); };
  $("#wPrev").onclick = () => step(-1);
  $("#wNext").onclick = () => step(1);
  $("#wNew").onclick = () => dlgNewWeek(null, curWeek);
  $("#wHist").onclick = dlgHistory;
}
// ------------------------------------------------------------------ compteur de fréquences
function dlgFrequencies() {
  const fams = [...new Set(prog.aircraft.map((a) => C.family(a.title)))].filter((f) => prog.aircraft.filter((a) => C.family(a.title) === f).length > 1);
  const opts = [["all", "Tous les avions"], ...fams.map((f) => [`f:${f}`, `Type ${f}`]), ...prog.aircraft.map((a, i) => [`a:${i}`, a.title])];
  const d = openDlg("Fréquences par jour et par destination", "cities", `
    <div class="row"><label>Avions</label><select id="fqW" style="font:inherit;padding:5px;max-width:320px">${opts.map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select></div>
    <div id="fqT" style="overflow:auto"></div>
    <p style="font-size:12px;color:#555">${esc(C.weekLabel(curWeek))} – une fréquence = une rotation qui dessert la destination (la base n'est pas comptée). Touchez un chiffre pour le détail.</p>
    <div id="fqD" style="font-size:13px;min-height:18px"></div>`,
    `<button class="btn primary" id="ok">Fermer</button>`);
  const draw = () => {
    const v = $("#fqW", d).value;
    const list = C.views(prog, curWeek).filter((a, i) => v === "all" || (v.startsWith("f:") ? C.family(a.title) === v.slice(2) : i === +v.slice(2)));
    const f = C.frequencies(list);
    const head = `<tr><th>Destination</th>${C.DAYS.map((_, di) => `<th>${esc(C.dayLabel(curWeek, di))}</th>`).join("")}<th>Semaine</th></tr>`;
    const body = f.rows.map((r, ri) => `<tr><td><b>${esc(r.dest)}</b></td>${r.counts.slice(0, 7).map((n, di) => `<td class="fq${n ? " on" : ""}" data-r="${ri}" data-d="${di}">${n || ""}</td>`).join("")}<td class="fq tot">${r.counts[7]}</td></tr>`).join("");
    const tot = `<tr class="fqtot"><td>TOTAL</td>${f.totals.map((n) => `<td>${n}</td>`).join("")}</tr>`;
    $("#fqT", d).innerHTML = `<table class="hist fqtab">${head}${body}${tot}</table>`;
    $("#fqT", d).onclick = (e) => { const c = e.target.closest("td[data-r]"); if (!c) return; const r = f.rows[+c.dataset.r];
      $("#fqD", d).innerHTML = `<b>${esc(r.dest)} – ${esc(C.DAYS[+c.dataset.d])}</b> : ${r.details[+c.dataset.d].map(esc).join(" · ") || "aucune"}`; };
  };
  $("#fqW", d).onchange = draw;
  $("#ok", d).onclick = () => d.close();
  draw();
}

// ------------------------------------------------------------------ ajouter un avion / cloner
function dlgAddAircraft() {
  const list = prog.aircraft, n = list.filter((a) => C.family(a.title) === "ERJ.145").length + 1;
  const d = openDlg("Ajouter un avion", "plane", `
    <div class="row"><label>Nom du tableau</label><input id="naN" value="ERJ.145 - AVION ${n}" style="width:240px;text-transform:uppercase"></div>
    <div class="row"><label>Base</label><input id="naB" value="${esc(C.original(A()).base)}" style="width:240px;text-transform:uppercase"></div>
    <div class="row"><label>Type d'avion (reprend ses durées de vol)</label><select id="naM" style="font:inherit;padding:5px;width:240px"><option value="-1">Aucun</option>${list.map((a, i) => `<option value="${i}" ${i === cur ? "selected" : ""}>${esc(a.title)}</option>`).join("")}</select></div>
    <div class="row"><label>Programme de départ</label><select id="naP" style="font:inherit;padding:5px;width:240px"><option value="-1">Vide</option>${list.map((a, i) => `<option value="${i}">Copie de ${esc(a.title)}</option>`).join("")}</select></div>
    <div class="row"><label><input type="checkbox" id="naR" style="width:auto"> Recalculer les heures avec les durées de vol du nouvel avion</label></div>`,
    `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">${icon("plane", 16)} Ajouter</button>`);
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => {
    const name = C.up($("#naN", d).value);
    if (!name) return; if (list.some((a) => C.up(a.title) === name)) { alert(`« ${name} » existe déjà.`); return; }
    snapshot();
    const m = +$("#naM", d).value, p = +$("#naP", d).value;
    const a = C.newAircraft(prog, name, $("#naB", d).value, m >= 0 ? list[m] : null);
    const missing = p >= 0 ? C.cloneDays(prog, list[p], a, $("#naR", d).checked) : [];
    d.close(); cur = prog.aircraft.length - 1; setWeek(null, false); renderAll(); scheduleSave();
    toast(`Avion « ${name} » ajouté` + (missing.length ? " – heures à compléter" : ""));
  };
}
function dlgClone() {
  const src = A(), others = prog.aircraft.map((a, i) => [a, i]).filter(([a]) => a !== C.original(src));
  if (!others.length) { infoDlg("Cloner", "Il n'y a pas d'autre avion : ajoutez d'abord un avion."); return; }
  const d = openDlg("Cloner le programme", "copy", `
    <p style="margin-top:0">Copier <b>${esc(src.title)}</b> – <b>${esc(C.weekLabel(curWeek))}</b> sur les avions cochés (leur ${esc(C.weekShort(curWeek))} sera remplacé) :</p>
    ${others.map(([a, i]) => `<div class="row"><label><input type="checkbox" data-i="${i}" style="width:auto"> ${esc(a.title)}</label></div>`).join("")}
    <div class="row"><label><input type="checkbox" id="clR" style="width:auto"> Recalculer les heures avec les durées de vol de chaque avion</label></div>`,
    `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">${icon("copy", 16)} Cloner</button>`);
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => {
    const sel = [...d.querySelectorAll("input[data-i]:checked")].map((x) => prog.aircraft[+x.dataset.i]);
    if (!sel.length) { alert("Cochez au moins un avion."); return; }
    snapshot();
    const missing = []; for (const a of sel) missing.push(...C.cloneDays(prog, src, C.view(a, curWeek), $("#clR", d).checked));
    d.close(); renderAll(); scheduleSave();
    infoDlg("Cloner", `Programme copié sur : ${sel.map((a) => esc(a.title)).join(", ")}${missing.length ? "<br><br>À compléter :<ul>" + [...new Set(missing)].map((x) => `<li>${esc(x)}</li>`).join("") + "</ul>" : ""}`);
  };
}

function dlgNewWeek(proposed, preSource) {
  const ws = C.weeks(prog);
  if (!proposed) proposed = !ws.length || ws[ws.length - 1] < C.thisWeek() ? C.thisWeek() : C.shiftWeek(ws[ws.length - 1], 1);
  const srcs = [[null, "Programme type (sans date)"], ...ws.slice().reverse().map((w) => [w, "Copie de la " + C.weekShort(w)]), ["vide", "Vide"]];
  const d = openDlg("Nouvelle semaine datée", "clock", `
    <div class="row"><label>Semaine commençant le</label><input type="date" id="nwD" value="${proposed}" style="width:170px"></div>
    <div class="row"><label>Remplir à partir de</label><select id="nwS" style="font:inherit;padding:5px;max-width:300px">${srcs.map(([k, l]) => `<option value="${k ?? ""}" ${k === (preSource ?? null) ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></div>
    <p style="font-size:12px;color:#555">Le lundi de la semaine choisie est retenu.</p>`,
    `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">Créer</button>`);
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => {
    const v = $("#nwD", d).value; if (!v) return;
    const [y, m, dd] = v.split("-").map(Number), w = C.monday(new Date(y, m - 1, dd));
    if (C.weeks(prog).includes(w) && !confirm(C.weekLabel(w) + " existe déjà. La remplacer ? (Annuler possible)")) { d.close(); setWeek(w); return; }
    snapshot(); C.createWeek(prog, w, $("#nwS", d).value || null); d.close(); setWeek(w); scheduleSave(); toast(C.weekLabel(w) + " créée");
  };
}
function dlgHistory() {
  const rows = [null, ...C.weeks(prog).reverse()], tw = C.thisWeek();
  const stats = (w) => { let t = 0, b = 0; for (const a of C.views(prog, w)) { t += C.weekTotal(a); b += C.weekBloc(a); } return [C.fmtDuration(t), C.fmtDuration(b)]; };
  const d = openDlg("Historique des programmes", "folder", `<table class="hist"><tr><th>Programme</th><th>Total</th><th>Bloc</th><th></th></tr>${rows.map((w, i) => {
      const [t, b] = stats(w);
      return `<tr class="${w === curWeek ? "sel" : ""}"><td><b>${esc(C.weekLabel(w))}</b>${w === tw ? " (cette semaine)" : ""}</td><td>${t}</td><td>${b}</td>
        <td><button class="btn outline" data-a="open" data-i="${i}">Ouvrir</button> <button class="btn outline" data-a="send" data-i="${i}" title="Envoyer / PDF">${icon("send", 14)}</button>
        ${w ? `<button class="btn outline" data-a="dup" data-i="${i}" title="Dupliquer vers une autre semaine">${icon("copy", 14)}</button> <button class="btn outline" data-a="del" data-i="${i}" title="Supprimer">${icon("clear", 14)}</button>` : `<button class="btn outline" data-a="dup" data-i="${i}" title="Créer une semaine à partir du programme type">${icon("copy", 14)}</button>`}</td></tr>`;
    }).join("")}</table>`, `<button class="btn primary" id="ok">Fermer</button>`);
  $("#ok", d).onclick = () => d.close();
  $(".db", d).onclick = (e) => {
    const b = e.target.closest("button[data-a]"); if (!b) return;
    const w = rows[+b.dataset.i];
    if (b.dataset.a === "open") { d.close(); setWeek(w); }
    else if (b.dataset.a === "send") { d.close(); setWeek(w); dlgSend(); }
    else if (b.dataset.a === "dup") { d.close(); dlgNewWeek(null, w); }
    else if (b.dataset.a === "del" && confirm(`Supprimer ${C.weekLabel(w)} ? (Annuler possible)`)) { snapshot(); C.deleteWeek(prog, w); if (curWeek === w) setWeek(null, false); renderAll(); scheduleSave(); d.close(); dlgHistory(); }
  };
}

function renderTabs() {
  $("#tabs").innerHTML = prog.aircraft.map((a, i) => `<button class="${i === cur ? "on" : ""}" data-i="${i}">${esc(a.title)}</button>`).join("");
  $("#tabs").onclick = (e) => { const b = e.target.closest("button"); if (b) { cur = +b.dataset.i; renderAll(); } };
}
function renderHead() {
  const a = A();
  $("#head").innerHTML = `
    <label>Titre <input id="hTitle" value="${esc(a.title)}" style="width:230px"></label>
    <label>Base <input id="hBase" value="${esc(a.base)}" style="width:120px;text-transform:uppercase"></label>
    <label title="Alerte si l'escale est plus courte">Escale mini <input class="num" id="hEsc" type="number" min="0" step="5" value="${a.escale_min}"> mn</label>
    <label title="Alerte si l'escale à la base est plus courte">Esc. base mini <input class="num" id="hBaseMin" type="number" min="0" step="5" value="${a.base_min}"> mn</label>
    <span class="total" id="hTotal"></span>`;
  const bind = (id, f) => { $(id).onchange = (e) => { snapshot(); f(e.target.value); changed(); if (id === "#hTitle") renderTabs(); }; };
  const o = C.original(a);   // les réglages de l'avion valent pour toutes les semaines
  bind("#hTitle", (v) => { o.title = a.title = v.trim() || a.title; });
  bind("#hBase", (v) => { o.base = a.base = C.up(v); });
  bind("#hEsc", (v) => { o.escale_min = a.escale_min = Math.max(0, parseInt(v, 10) || 0); });
  bind("#hBaseMin", (v) => { o.base_min = a.base_min = Math.max(0, parseInt(v, 10) || 0); });
}
function buildGrid() {
  let h = "<thead><tr>" + COLS.map((c) => `<th class="${c[3] || ""}">${c[0]}</th>`).join("") + "</tr></thead><tbody>";
  C.DAYS.forEach((d, di) => {
    for (let ri = 0; ri < C.ROTATIONS_PER_DAY; ri++) {
      h += `<tr class="${ri === 0 ? "first" : ""}">`;
      COLS.forEach(([, attr, kind, cls], ci) => {
        if (kind === "day") {
          if (ri === 0) h += `<td class="day" rowspan="3">${d}${curWeek ? `<small>${C.dmyOf(curWeek, di)}</small>` : ""}
            <button class="btn" data-act="copy" data-di="${di}" title="Copier ${d}">${icon("copy", 15)}</button><button class="btn" data-act="paste" data-di="${di}" title="Coller sur ${d}">${icon("paste", 15)}</button><button class="btn" data-act="clear" data-di="${di}" title="Vider ${d}">${icon("clear", 15)}</button></td>`;
        } else if (kind === "num") h += `<td class="num">${ri + 1}</td>`;
        else if (kind === "calc") {
          if (attr === "total" && ri > 0) return;
          h += `<td class="calc ${cls}" id="c-${attr}-${di}-${ri}" ${attr === "total" ? 'rowspan="3"' : ""}></td>`;
        } else {
          const extra = kind === "esc" || kind === "gap" ? `calc ${cls}` : "";
          h += `<td class="${extra}" id="td-${di}-${ri}-${ci}"><input data-cell="${di}-${ri}-${ci}" data-di="${di}" data-ri="${ri}" data-attr="${attr}" data-kind="${kind}" autocomplete="off" autocorrect="off" autocapitalize="characters" spellcheck="false"${kind === "time" || kind === "esc" || kind === "gap" ? ' inputmode="numeric"' : ""}></td>`;
        }
      });
      h += "</tr>";
    }
  });
  $("#grid").innerHTML = h + "</tbody>";
  updateGrid();
}
function updateGrid() {
  const a = A();
  a.days.forEach((rots, di) => {
    const dc = C.computeDay(rots);
    rots.forEach((r, ri) => {
      const c = dc.rotations[ri];
      COLS.forEach(([, attr, kind], ci) => {
        if (kind === "calc") {
          const td = document.getElementById(`c-${attr}-${di}-${ri}`); if (!td) return;
          let txt = "";
          if (attr === "duree") txt = c.duree !== null ? C.fmtDuration(c.duree) : "";
          else txt = dc.total ? C.fmtDuration(dc.total) : "";
          td.textContent = txt; td.classList.toggle("has", !!txt);
          if (attr === "total") { let mx = null; try { mx = C.parseTime(prog.settings.max_jour); } catch { } td.classList.toggle("bad", mx !== null && dc.total > mx); }
          return;
        }
        const td = document.getElementById(`td-${di}-${ri}-${ci}`); if (!td) return;
        const inp = td.firstElementChild;
        let v = "";
        if (kind === "esc") v = c.escale !== null ? `${c.escale} mn` : "";
        else if (kind === "gap") v = c.ecart !== null ? `${c.ecart} mn` : "";
        else v = r[attr] || "";
        if (document.activeElement !== inp) inp.value = v;
        else if (!inp._dirty && inp.value !== v) { inp.value = v; inp.placeholder = ""; inp.select(); }
        if (kind === "esc" || kind === "gap") {
          inp.disabled = !v; td.classList.toggle("has", !!v);
          td.classList.toggle("bad", kind === "esc" ? c.escale !== null && c.escale < a.escale_min : c.ecart !== null && c.ecart < a.base_min);
          inp.title = v ? "Modifiable : tapez les minutes (ex. 60)" : "";
        }
        if (kind === "time") td.classList.toggle("bad-time", !!v && C.safeTime(v) === null);
        if (attr === "escale") {     // même destination au même moment qu'un avion du même type
          const cf = v ? C.conflicts(prog, a, di, ri) : [];
          td.classList.toggle("bad-time", cf.length > 0);
          td.title = cf.length ? `Même destination au même moment que ${cf[0][0]} (${cf[0][2]}–${cf[0][3]})` : "";
        }
      });
    });
  });
  $("#hTotal").textContent = `Total semaine : ${C.fmtDuration(C.weekTotal(a))} · Bloc de vol : ${C.fmtDuration(C.weekBloc(a))}`;
}
function renderChecks() {
  const w = C.check(prog, A());
  $("#checks").innerHTML = w.length ? w.map((m) => `<li>${icon("warning", 16)}<span>${esc(m)}</span></li>`).join("")
    : `<li class="ok">${icon("ok", 16)}<span>Aucun problème détecté</span></li>`;
}

// ------------------------------------------------------------------ saisie
// Liste déroulante de propositions (faite maison : fonctionne au doigt sur tablette Android,
// où la liste standard « datalist » du navigateur ne s'affiche pas).
const sugg = { box: null, inp: null, items: [], shown: [], idx: -1 };
function suggBox() {
  if (sugg.box) return sugg.box;
  const b = document.createElement("div");
  b.id = "suggBox"; b.setAttribute("role", "listbox");
  // pointerdown + preventDefault : la case garde le focus pendant le choix
  b.addEventListener("pointerdown", (e) => e.preventDefault());
  b.addEventListener("mousedown", (e) => e.preventDefault());
  b.addEventListener("click", (e) => { const it = e.target.closest("button"); if (it) pickSugg(sugg.shown[+it.dataset.i]); });
  document.body.appendChild(b);
  window.addEventListener("scroll", placeSugg, true);
  window.addEventListener("resize", placeSugg);
  window.visualViewport?.addEventListener("resize", placeSugg);
  window.visualViewport?.addEventListener("scroll", placeSugg);
  return (sugg.box = b);
}
function showSugg(inp) {
  const b = suggBox();
  if (sugg.inp !== inp) return;
  const t = (inp.value || "").trim().toUpperCase();
  let list = sugg.items;
  if (inp._dirty && t) {
    const starts = list.filter((x) => x.toUpperCase().startsWith(t));
    list = [...starts, ...list.filter((x) => !starts.includes(x) && x.toUpperCase().includes(t))];
  }
  sugg.shown = list.slice(0, 40);
  if (!sugg.shown.length) { hideSugg(); return; }
  sugg.idx = -1;
  b.innerHTML = sugg.shown.map((x, i) => `<button type="button" role="option" data-i="${i}" class="${i === 0 ? "first" : ""}${x === inp.value ? " cur" : ""}">${esc(x)}</button>`).join("");
  b.style.display = "block";
  placeSugg();
}
function placeSugg() {
  const b = sugg.box, inp = sugg.inp;
  if (!b || b.style.display !== "block" || !inp) return;
  const r = inp.getBoundingClientRect(), vv = window.visualViewport;
  const viewH = vv ? vv.height + vv.offsetTop : window.innerHeight;
  if (r.bottom < 0 || r.top > viewH) { b.style.display = "none"; return; }
  const below = viewH - r.bottom - 8, above = r.top - 8;
  const maxH = Math.min(320, Math.max(below, above));
  b.style.maxHeight = maxH + "px";
  b.style.minWidth = Math.max(r.width, 170) + "px";
  b.style.left = Math.max(4, Math.min(r.left, window.innerWidth - Math.max(r.width, 170) - 8)) + "px";
  if (below >= Math.min(220, b.scrollHeight) || below >= above) { b.style.top = r.bottom + 2 + "px"; b.style.bottom = "auto"; }
  else { b.style.top = "auto"; b.style.bottom = (window.innerHeight - r.top + 2) + "px"; }
}
function hideSugg() { if (sugg.box) sugg.box.style.display = "none"; sugg.idx = -1; }
function pickSugg(val) {
  const inp = sugg.inp; if (!inp || val === undefined) return;
  inp.value = val; inp._dirty = true;
  hideSugg();
  commit(inp);
  moveNext(inp, 1);
}
function onFocus(e) {
  const inp = e.target; if (!inp.dataset?.cell) return;
  const { di, ri, attr } = inp.dataset;
  let items = [];
  try { items = C.suggest(prog, A(), +di, +ri, attr); } catch (err) { console.error(err); }
  sugg.inp = inp; sugg.items = items;
  inp._dirty = false;
  inp.placeholder = !inp.value && items.length ? items[0] : "";
  inp.select();
  showSugg(inp);
}
function onBlur(e) {
  if (e.target === sugg.inp) setTimeout(() => { if (document.activeElement !== sugg.inp) { hideSugg(); sugg.inp = null; } }, 120);
}
function onInput(e) { e.target._dirty = true; if (e.target === sugg.inp) showSugg(e.target); }
function onKey(e) {
  const inp = e.target; if (!inp.dataset?.cell) return;
  const open = sugg.box && sugg.box.style.display === "block" && sugg.inp === inp;
  if (open && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
    e.preventDefault();
    const n = sugg.shown.length;
    sugg.idx = (sugg.idx + (e.key === "ArrowDown" ? 1 : -1) + n) % n;
    [...sugg.box.children].forEach((c, i) => c.classList.toggle("on", i === sugg.idx));
    sugg.box.children[sugg.idx]?.scrollIntoView({ block: "nearest" });
    return;
  }
  if (e.key === "Enter") {
    e.preventDefault();
    if (open && sugg.idx >= 0) return pickSugg(sugg.shown[sugg.idx]);
    if (!inp.value && inp.placeholder) inp.value = inp.placeholder;
    hideSugg();
    commit(inp);
    moveNext(inp, e.shiftKey ? -1 : 1);
  } else if (e.key === "Escape") {
    if (open) { hideSugg(); return; }
    updateGrid(); inp.blur();
  }
}
function moveNext(inp, dir) {
  const all = [...document.querySelectorAll(".grid input:not([disabled])")];
  const i = all.indexOf(inp); all[i + dir]?.focus();
}
async function commit(inp) {
  const { di: d, ri: r, attr, kind } = inp.dataset, di = +d, ri = +r, a = A();
  inp._dirty = false;
  let v = inp.value.trim();
  inp.placeholder = "";
  if (kind === "esc" || kind === "gap") {
    let m = null; try { m = C.parseMinutes(v); } catch { }
    if (m === null || m < 0 || m > 1440) { status(`« ${v} » : tapez un nombre de minutes (ex. 60)`); updateGrid(); return; }
    const c = C.computeDay(a.days[di]).rotations[ri];
    if (m === (kind === "esc" ? c.escale : c.ecart)) return;
    snapshot();
    (kind === "esc" ? C.setEscale : C.setGap)(prog, a, di, ri, m);
    return changed();
  }
  if (kind === "time") { try { v = C.normalizeTime(v); } catch { status(`« ${v} » n'est pas une heure valide (format HH:MM, ex. 07:30)`); } }
  else v = C.up(v);
  if ((a.days[di][ri][attr] || "") === v) { updateGrid(); return; }
  snapshot();
  let missing = C.applyChange(prog, a, di, ri, attr, v);
  if (await askMissingDurations(a, di, ri)) missing = C.applyChange(prog, a, di, ri, "escale", a.days[di][ri].escale);
  changed();
  if (missing.length) status("À compléter : " + missing.join(" ; "));
}
async function askMissingDurations(a, di, ri) {
  let asked = false;
  for (const [x, y] of C.missingDurations(prog, a, di, ri)) {
    const rev = C.getDuration(prog, a, `${y}>${x}`);
    const v = await promptDlg("Nouveau trajet", `Durée de vol <b>${esc(x)} → ${esc(y)}</b> pour « ${esc(a.title)} » ?<br><small>Format HH:MM – enregistrée pour la suite.</small>`,
      rev !== null ? C.fmtDuration(rev) : "01:00", (t) => { try { return C.parseTime(t) ? null : "Durée nulle"; } catch { return "Format attendu : HH:MM (ex. 01:10)"; } });
    if (v === null) return asked;
    a.durations[`${x}>${y}`] = C.parseTime(v); asked = true;
  }
  return asked;
}
function onDayButton(e) {
  const b = e.target.closest("button[data-act]"); if (!b) return;
  const di = +b.dataset.di, a = A();
  if (b.dataset.act === "copy") { clipDay = JSON.parse(JSON.stringify(a.days[di])); toast(`${C.DAYS[di]} copié`); return; }
  if (b.dataset.act === "paste") { if (!clipDay) return toast("Copiez d'abord un jour"); snapshot(); a.days[di] = JSON.parse(JSON.stringify(clipDay)); }
  if (b.dataset.act === "clear") { if (!confirm(`Vider tout le ${C.DAYS[di]} ?`)) return; snapshot(); a.days[di] = [C.R(), C.R(), C.R()]; }
  changed();
}

// ------------------------------------------------------------------ état, annuler, sauvegarde
function snapshot() { undoStack.push(JSON.stringify(C.toDict(prog))); if (undoStack.length > 100) undoStack.shift(); redoStack = []; }
function viewProg() { return { ...prog, aircraft: C.views(prog, curWeek) }; }
function restore(json) { prog = C.fromDict(JSON.parse(json)); cur = Math.min(cur, prog.aircraft.length - 1); renderAll(); scheduleSave(); }
function undo() { if (!undoStack.length) return toast("Rien à annuler"); redoStack.push(JSON.stringify(C.toDict(prog))); restore(undoStack.pop()); }
function redo() { if (!redoStack.length) return; undoStack.push(JSON.stringify(C.toDict(prog))); restore(redoStack.pop()); }
function changed() {
  updateGrid(); renderChecks(); scheduleSave();
  const inp = sugg.inp;   // la case active : propositions recalculées avec les nouvelles données
  if (inp && document.activeElement === inp && !inp._dirty) {
    try { sugg.items = C.suggest(prog, A(), +inp.dataset.di, +inp.dataset.ri, inp.dataset.attr); } catch { }
    showSugg(inp);
  }
}
function scheduleSave() {
  clearTimeout(saveTimer);
  status("Modifications en cours d'enregistrement…");
  saveTimer = setTimeout(async () => {
    try { await store.save(C.toDict(prog)); saveTimer = null; status("Enregistré " + new Date().toLocaleTimeString("fr-FR")); }
    catch (e) { saveTimer = null; console.error(e); status("ERREUR d'enregistrement : " + e.message); toast("Enregistrement impossible – vérifiez la connexion"); }
  }, 600);
}
function flushSave() {
  if (!saveTimer) return;
  clearTimeout(saveTimer); saveTimer = null;
  store.save(C.toDict(prog)).catch((e) => console.error(e));
}
function status(m) { $("#status").textContent = m; }
let toastT; function toast(m) { const t = $("#toast"); t.textContent = m; t.classList.add("show"); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove("show"), 3000); }

// ------------------------------------------------------------------ actions
function doAutocomplete() {
  snapshot();
  const m = C.autocomplete(prog, A());
  changed();
  if (m.length) infoDlg("Compléter automatiquement", "Complété. Reste à saisir à la main :<ul>" + m.map((x) => `<li>${esc(x)}</li>`).join("") + "</ul>");
  else toast("Programme complété");
}
function doPrint(all) {
  renderPrint(all ? C.views(prog, curWeek) : [A()]);
  if (isAndroid()) setTimeout(() => window.AndroidBridge.print("Programme des rotations"), 150);
  else setTimeout(() => window.print(), 50);
}
function renderPrint(list) {
  const PAT = ["pb", "pg", "pw", "pw", "py", "pw", "pw"];
  const st = (u, s, t) => u && (C.up(s) || t) ? `${C.up(s)} : ${C.safeTime(t) !== null ? C.fmtTime(C.safeTime(t)) : (t || "")}` : "";
  $("#print").innerHTML = list.map((a) => {
    const wk = C.weekTotal(a);
    const ttl = a.week ? `${a.title} – ${C.weekShort(a.week).toUpperCase()}` : a.title;
    let h = `<div class="page"><table class="sheet"><colgroup>${"<col>".repeat(21)}<col style="width:11mm"></colgroup>
      <tr class="title"><td class="pw"><div class="wk">${wk ? C.fmtDuration(wk) : ""}</div></td><td colspan="21" class="t" style="${a.week ? "font-size:15pt" : ""}">${esc(ttl)}</td></tr>`;
    a.days.forEach((rots, di) => {
      const dc = C.computeDay(rots);
      h += `<tr class="d">`;
      for (const ri of [2, 1, 0]) {
        const r = rots[ri], c = dc.rotations[ri], u = C.isUsed(r);
        const blue = ri === 2 ? [dc.total ? C.fmtDuration(dc.total) : "", "tot"] : [c.ecart !== null ? `Escale ${c.ecart}mn` : "", ""];
        const cells = [blue, [c.duree !== null ? C.fmtDuration(c.duree) : "", "big"], [st(u, r.arrivee, r.h_arrivee), ""],
          [st(u, r.escale, r.h_redepart), ""], [c.escale !== null ? `Escale ${c.escale}mn` : "", ""],
          C.up(r.remarque) ? [C.up(r.remarque), C.up(r.remarque).length > 16 ? "note" : "big"] : [st(u, r.escale, r.h_arrivee_escale), ""], [st(u, r.depart, r.h_depart), ""]];
        cells.forEach(([t, k], i) => { h += `<td class="${PAT[i]}${i === 0 ? " sep" : ""}"><div class="vt ${t && k ? k : ""}">${esc(t)}</div></td>`; });
      }
      h += `<td class="dayc pw"><div class="vt dayn">${C.dayLabel(a.week, di)}</div></td></tr>`;
    });
    return h + "</table></div>";
  }).join("");
}

// ------------------------------------------------------------------ boîtes de dialogue
function openDlg(title, ic, body, buttons) {
  const d = $("#dlg");
  d.innerHTML = `<div class="dh">${icon(ic)} ${esc(title)}</div><div class="db">${body}</div><div class="df">${buttons}</div>`;
  d.showModal(); return d;
}
function infoDlg(title, html) { const d = openDlg(title, "help", html, `<button class="btn primary" id="ok">OK</button>`); $("#ok", d).onclick = () => d.close(); }
function promptDlg(title, html, value, validate) {
  return new Promise((resolve) => {
    const d = openDlg(title, "clock", `<p>${html}</p><input id="pv" value="${esc(value)}"><div class="err"></div>`,
      `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">OK</button>`);
    const inp = $("#pv", d); inp.select();
    const ok = () => { const err = validate(inp.value); if (err) { $(".err", d).textContent = err; return; } d.close(); resolve(inp.value); };
    $("#yes", d).onclick = ok; inp.onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); ok(); } };
    $("#no", d).onclick = () => { d.close(); resolve(null); };
    d.oncancel = () => resolve(null);
  });
}
function dlgDurations() {
  const a = A(), keys = Object.keys(a.durations).sort();
  const row = (x = "", y = "", t = "") => `<tr><td><input value="${esc(x)}" style="text-transform:uppercase"></td><td><input value="${esc(y)}" style="text-transform:uppercase"></td><td><input value="${esc(t)}" placeholder="HH:MM"></td><td><button class="btn" data-del>${icon("clear", 16)}</button></td></tr>`;
  const d = openDlg(`Durées de vol – ${a.title}`, "clock",
    `<p style="margin-top:0">Utilisées pour remplir les heures. Si un trajet manque, celui d'un avion du même type est utilisé.</p>
     <table><thead><tr><th>De</th><th>Vers</th><th>Durée</th><th></th></tr></thead><tbody id="dt">${keys.map((k) => row(...k.split(">"), C.fmtDuration(a.durations[k]))).join("")}</tbody></table>
     <div class="err"></div>`,
    `<button class="btn outline" id="add">${icon("clock", 16)} Ajouter un trajet</button><span style="flex:1"></span><button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">Enregistrer</button>`);
  $("#dt", d).onclick = (e) => { if (e.target.closest("[data-del]")) e.target.closest("tr").remove(); };
  $("#add", d).onclick = () => { $("#dt", d).insertAdjacentHTML("beforeend", row()); $("#dt tr:last-child input", d).focus(); };
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => {
    const out = {};
    for (const [i, tr] of [...$("#dt", d).rows].entries()) {
      const [x, y, t] = [...tr.querySelectorAll("input")].map((el) => el.value.trim());
      if (!x && !y && !t) continue;
      let m = null; try { m = C.parseTime(t); } catch { }
      if (!x || !y || !m) { $(".err", d).textContent = `Ligne ${i + 1} invalide : indiquez De, Vers et une durée HH:MM.`; return; }
      out[`${C.up(x)}>${C.up(y)}`] = m;
    }
    snapshot(); C.original(a).durations = out; d.close(); renderAll(); scheduleSave();
  };
}
function dlgCities() {
  let list = [...prog.stations];
  const d = openDlg("Villes proposées", "cities", `<p style="margin-top:0">Proposées dans les listes, en plus de celles déjà utilisées.</p>
    <ul class="list-cities" id="cl"></ul><div class="row"><input id="nc" placeholder="Nouvelle ville (ex. TENERIFE)" style="flex:1;width:auto;text-transform:uppercase">
    <button class="btn outline" id="add">${icon("cities", 16)} Ajouter</button></div>`,
    `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">Enregistrer</button>`);
  const draw = () => { $("#cl", d).innerHTML = list.map((s, i) => `<li>${icon("cities", 14)} ${esc(s)}<button data-i="${i}" title="Supprimer">${icon("clear", 14)}</button></li>`).join(""); };
  draw();
  $("#cl", d).onclick = (e) => { const b = e.target.closest("button"); if (b) { list.splice(+b.dataset.i, 1); draw(); } };
  const add = () => { const v = C.up($("#nc", d).value); if (v && !list.includes(v)) { list.push(v); draw(); } $("#nc", d).value = ""; };
  $("#add", d).onclick = add; $("#nc", d).onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); add(); } };
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => { snapshot(); prog.stations = list; d.close(); changed(); };
}
function dlgSettings() {
  const s = prog.settings;
  const d = openDlg("Paramètres", "settings", `
    <div class="row"><label>Escale par défaut à l'escale (jaune)</label><input id="s1" type="number" min="0" value="${s.escale_defaut}"> mn</div>
    <div class="row"><label>Escale par défaut à la base (bleu)</label><input id="s2" type="number" min="0" value="${s.escale_base}"> mn</div>
    <div class="row"><label>Total maximum par jour</label><input id="s3" value="${esc(s.max_jour)}" placeholder="HH:MM"></div>
    <p style="font-size:12px;color:#555">Les escales minimum (alertes) se règlent en haut de l'onglet de chaque avion.</p><div class="err"></div>`,
    `<button class="btn outline" id="no">Annuler</button><button class="btn primary" id="yes">Enregistrer</button>`);
  $("#no", d).onclick = () => d.close();
  $("#yes", d).onclick = () => {
    let mj; try { mj = C.normalizeTime($("#s3", d).value); } catch { $(".err", d).textContent = "Total maximum : format HH:MM (ex. 13:00)"; return; }
    snapshot();
    s.escale_defaut = Math.max(0, +$("#s1", d).value || 0); s.escale_base = Math.max(0, +$("#s2", d).value || 0); s.max_jour = mj || "13:00";
    d.close(); changed();
  };
}
function dlgMore() {
  const acts = [
    ["plane", "Ajouter un avion", dlgAddAircraft],
    ["copy", "Cloner le programme sur d'autres avions", dlgClone],
    ["clear", "Supprimer l'avion affiché", () => { if (prog.aircraft.length > 1 && confirm(`Supprimer « ${A().title} » ? (Annuler possible)`)) { snapshot(); prog.aircraft.splice(cur, 1); cur = 0; renderAll(); scheduleSave(); } }],
    ["clear", "Vider le programme de l'avion affiché", () => { if (confirm(`Vider « ${A().title} » (${C.weekShort(curWeek)}) ?`)) { snapshot(); const a = A(); a.days.forEach((_, di) => { a.days[di] = [C.R(), C.R(), C.R()]; }); changed(); } }],
    ["learn", "Apprendre les durées depuis le programme affiché", () => { snapshot(); const n = C.learnDurations(prog, A()); changed(); toast(`${n} trajets appris`); }],
    ["auto", "Semaine type Dakhla Air Connect : compléter les jours vides", () => { snapshot(); const r = C.applySemaineType(viewProg(), false); renderAll(); scheduleSave(); infoDlg("Semaine type", r.length ? "Jours ajoutés :<ul>" + r.map((x) => `<li>${esc(x)}</li>`).join("") + "</ul>" : "Aucun jour vide à compléter."); }],
    ["auto", "Semaine type Dakhla Air Connect : remplacer les 4 avions", () => { if (confirm("Remplacer les 7 jours des 4 avions par la semaine type ? (Annuler possible)")) { snapshot(); C.applySemaineType(viewProg(), true); renderAll(); scheduleSave(); toast("Semaine type appliquée"); } }],
    ["paste", "Charger les feuilles papier dans les avions vides", () => { snapshot(); const r = C.fillEmptyFromDefaults(prog); renderAll(); scheduleSave(); toast(r.length ? "Chargé : " + r.join(", ") : "Aucun avion vide"); }],
    ["save", "Exporter le programme (fichier .json, compatible logiciel Windows)", exportJson],
    ["open", "Importer un programme (.json du logiciel Windows ou du site)", importJson],
    ["folder", "Restaurer une sauvegarde", dlgBackups],
    ["undo", "Revenir au programme par défaut (programme type)", () => { if (confirm("Remplacer le programme type des avions par le programme par défaut ? (semaines datées conservées – Annuler possible)")) { snapshot(); const def = C.factoryProgram(); for (const d of def.aircraft) { const a = prog.aircraft.find((x) => C.up(x.title) === C.up(d.title)); if (!a) prog.aircraft.push(d); else { a.days.forEach((_, di) => { a.days[di] = d.days[di]; }); Object.assign(a.durations, d.durations); } } setWeek(null, false); renderAll(); scheduleSave(); } }],
  ];
  const d = openDlg("Fichier & avions", "folder", acts.map((a, i) => `<div><button class="btn" data-i="${i}" style="width:100%;justify-content:flex-start">${icon(a[0])} ${esc(a[1])}</button></div>`).join(""),
    `<button class="btn primary" id="ok">Fermer</button>`);
  $(".db", d).onclick = (e) => { const b = e.target.closest("button"); if (b) { d.close(); acts[+b.dataset.i][2](); } };
  $("#ok", d).onclick = () => d.close();
}
// ------------------------------------------------------------------ envoi WhatsApp / e-mail
// Seul le PDF final (le tableau en couleurs) est envoyé : pas de texte à la place.
const SEND_KEY = "rotations-envoi";
function canShareFiles() {
  if (isAndroid() || !navigator.canShare) return false;
  try { return navigator.canShare({ files: [new File(["x"], "x.pdf", { type: "application/pdf" })] }); } catch { return false; }
}
function dlgSend() {
  let cfg = {}; try { cfg = JSON.parse(localStorage.getItem(SEND_KEY) || "{}"); } catch { }
  const wks = [null, ...C.weeks(prog).reverse()];
  const d = openDlg("Envoyer le tableau en PDF", "send", `
    <div class="row"><label>Programme</label><select id="sWk" style="font:inherit;font-size:15px;padding:6px;max-width:330px">${wks.map((w) => `<option value="${w || ""}" ${w === curWeek ? "selected" : ""}>${esc(C.weekLabel(w))}</option>`).join("")}</select></div>
    <div class="send-scope">
      <label><input type="radio" name="sc" value="cur" ${cfg.scope !== "all" ? "checked" : ""}> ${esc(A().title)}</label>
      <label><input type="radio" name="sc" value="all" ${cfg.scope === "all" ? "checked" : ""}> Tous les avions (${prog.aircraft.length} pages)</label>
    </div>
    <div class="send-big">
      <button class="btn big wa" id="sWa">${icon("whatsapp", 34)}<span>WhatsApp</span></button>
      <button class="btn big mail" id="sMail">${icon("mail", 34)}<span>E-mail</span></button>
    </div>
    <p class="send-note">Le PDF du tableau (même présentation que l'impression) est joint automatiquement.</p>`,
    `<button class="btn outline" id="sPdf">${icon("pdf", 16)} ${isAndroid() ? "Autre application…" : "Télécharger le PDF"}</button><span style="flex:1"></span>
     <button class="btn outline" id="sNo">Fermer</button>`);
  const go = (target) => {
    cfg = { scope: $('input[name="sc"]:checked', d).value };
    localStorage.setItem(SEND_KEY, JSON.stringify(cfg));
    d.close();
    sendProgramme(target, cfg.scope === "all", $("#sWk", d).value || null).catch((e) => { console.error(e); infoDlg("Envoi", "Envoi impossible : " + esc(e.message)); });
  };
  $("#sWa", d).onclick = () => go("whatsapp");
  $("#sMail", d).onclick = () => go("email");
  $("#sPdf", d).onclick = () => go("pdf");
  $("#sNo", d).onclick = () => d.close();
}
async function sendProgramme(target, all, week = curWeek) {
  const list = all ? C.views(prog, week) : [C.view(prog.aircraft[cur], week)];
  const label = all ? "tous les avions" : list[0].title, when = C.weekShort(week);
  const subject = `Programme des rotations – ${label} – ${when}`;
  const name = `Programme_${label.replace(/[^A-Za-z0-9]+/g, "_")}_${week ? "semaine_" + week : "type"}.pdf`;
  const pdf = buildPdf(list, subject);
  const body = `Bonjour,\n\nVeuillez trouver ci-joint le programme des rotations (${label}, ${when}) en PDF.\n\nCordialement`;
  if (isAndroid()) {
    // WhatsApp : uniquement le fichier PDF (sans texte, sinon WhatsApp peut n'envoyer que le texte)
    window.AndroidBridge.shareFile(name, toBase64(pdf), "application/pdf", target === "email" ? body : "",
      target === "pdf" ? "autre" : target, "[]", target === "email" ? subject : "");
    return;
  }
  const file = new File([pdf], name, { type: "application/pdf" });
  if (canShareFiles() && target !== "pdf") {
    try { await navigator.share({ files: [file], title: subject }); return; }
    catch (e) { if (e.name === "AbortError") return; }
  }
  const a = document.createElement("a"); a.href = URL.createObjectURL(file); a.download = name; a.click();
  if (target === "whatsapp") {
    window.open("https://web.whatsapp.com/", "_blank");
    infoDlg("Envoyer par WhatsApp", `Le PDF <b>${esc(name)}</b> est téléchargé.<br><br>Dans WhatsApp : ouvrez la conversation → trombone (joindre) → Document → choisissez ce fichier.`);
  } else if (target === "email") {
    location.href = `mailto:?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`;
    infoDlg("Envoyer par e-mail", `Le PDF <b>${esc(name)}</b> est téléchargé : joignez-le au message qui s'ouvre (trombone).`);
  }
}

function exportJson() {
  if (isAndroid()) { window.AndroidBridge.shareText(`programme_rotations_${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(C.toDict(prog))); return; }
  const blob = new Blob([JSON.stringify(C.toDict(prog), null, 1)], { type: "application/json" });
  const a = document.createElement("a"); a.href = URL.createObjectURL(blob);
  a.download = `programme_rotations_${new Date().toISOString().slice(0, 10)}.json`; a.click();
}
function importJson() {
  const inp = document.createElement("input"); inp.type = "file"; inp.accept = ".json,application/json";
  inp.onchange = async () => {
    try { const p = C.fromDict(JSON.parse(await inp.files[0].text())); snapshot(); prog = p; C.addMissingDefaults(prog); cur = 0; renderAll(); scheduleSave(); toast("Programme importé"); }
    catch (e) { alert("Fichier illisible : " + e.message); }
  };
  inp.click();
}
async function dlgBackups() {
  let list = [];
  try { list = await store.backups(); } catch (e) { return alert("Lecture des sauvegardes impossible : " + e.message); }
  const d = openDlg("Restaurer une sauvegarde", "folder", list.length ? list.map((b, i) => `<div><button class="btn" data-i="${i}" style="width:100%;justify-content:flex-start">${icon("open", 16)} ${esc(b.label)}</button></div>`).join("") : "Aucune sauvegarde pour le moment.",
    `<button class="btn primary" id="ok">Fermer</button>`);
  $(".db", d).onclick = (e) => {
    const b = e.target.closest("button"); if (!b || !confirm("Remplacer le programme actuel par cette sauvegarde ? (Annuler possible)")) return;
    snapshot(); prog = C.fromDict(list[+b.dataset.i].load()); cur = 0; d.close(); renderAll(); scheduleSave(); toast("Sauvegarde restaurée");
  };
  $("#ok", d).onclick = () => d.close();
}
function dlgHelp() {
  infoDlg("Mode d'emploi", `
  <b>Une ligne = une rotation</b> : Départ → Escale (arrivée, temps d'escale, redépart) → Arrivée.<br><br>
  <b>Saisie rapide</b> : cliquez dans une case, une liste de propositions s'affiche (les plus probables en premier).
  La proposition grisée est acceptée avec <b>Entrée</b>. Choisissez seulement la <b>destination</b> (colonne « Escale ») :
  départ, arrivée, heures et escale se remplissent tout seuls.<br><br>
  <b>Heures</b> au format HH:MM (0700, 7h00 → 07:00). Modifier une heure décale les heures suivantes.<br>
  <b>Escales modifiables</b> : colonnes jaune et bleue, tapez les minutes (ex. 75).<br>
  <b>Nouvelle ville</b> : la durée de vol est demandée une seule fois.<br><br>
  <b>Envoyer</b> : bouton « Envoyer » → WhatsApp ou e-mail, avec le tableau en PDF.<br>
  <b>Semaine type</b> : Fichier &amp; avions → compléter les jours vides ou tout remplacer.<br><br>
  <b>Semaines datées</b> : barre « Programme » en haut : programme type (sans date) ou une semaine datée ; ◀ ▶ pour changer de semaine ;
  « Nouvelle semaine » (copie du programme type, d'une autre semaine ou vide) ; « Historique » pour revoir, envoyer, dupliquer ou supprimer une semaine.<br><br>
  <b>Raccourcis</b> : F5 compléter · Ctrl+P imprimer · Ctrl+Z / Ctrl+Y annuler / rétablir · Entrée case suivante.<br>
  Pour un PDF : Imprimer → destination « Enregistrer au format PDF ».<br><br>
  ${store.mode === "sync" ? "<b>Synchronisation Wi-Fi</b> : les données sont gardées sur cet appareil et échangées automatiquement avec le PC quand il est joignable (bouton Wi-Fi en haut à droite)." : store.mode === "local" ? "<b>Mode local</b> : les données sont dans ce navigateur uniquement." : "<b>En ligne</b> : enregistrement automatique et partagé, mise à jour en temps réel entre les utilisateurs."}`);
}

start();

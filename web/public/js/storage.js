// Enregistrement : mode local (navigateur) ou Firebase (Firestore + connexion).
import { firebaseConfig, DOCUMENT_PROGRAMME } from "./firebase-config.js";

const SDK = "https://www.gstatic.com/firebasejs/10.12.5";
const LOCAL_KEY = "programme-rotations-web-v1";

export class LocalStore {
  constructor() { this.mode = "local"; }
  async init() { return { user: null }; }
  load() { const s = localStorage.getItem(LOCAL_KEY); return s ? JSON.parse(s) : null; }
  async save(dict, _opts) {
    localStorage.setItem(LOCAL_KEY, JSON.stringify(dict));
    // copie de sécurité quotidienne (7 derniers jours)
    const day = new Date().toISOString().slice(0, 10), k = `${LOCAL_KEY}-sauvegarde-${day}`;
    if (!localStorage.getItem(k)) {
      localStorage.setItem(k, JSON.stringify(dict));
      Object.keys(localStorage).filter((x) => x.startsWith(`${LOCAL_KEY}-sauvegarde-`)).sort().slice(0, -7).forEach((x) => localStorage.removeItem(x));
    }
  }
  subscribe() { return () => {}; }
  backups() {
    return Object.keys(localStorage).filter((x) => x.startsWith(`${LOCAL_KEY}-sauvegarde-`)).sort().reverse()
      .map((k) => ({ label: k.slice(`${LOCAL_KEY}-sauvegarde-`.length).replace("conflit-", "avant conflit ").replace("T", " "), load: () => JSON.parse(localStorage.getItem(k)) }));
  }
}

export class FirebaseStore {
  constructor() { this.mode = "firebase"; this.user = null; this._historySaved = false; }

  async init(askLogin) {
    const [{ initializeApp }, auth, fs] = await Promise.all([
      import(`${SDK}/firebase-app.js`), import(`${SDK}/firebase-auth.js`), import(`${SDK}/firebase-firestore.js`)]);
    this.fs = fs; this.authMod = auth;
    const app = initializeApp(firebaseConfig);
    this.auth = auth.getAuth(app);
    await auth.setPersistence(this.auth, auth.browserLocalPersistence);
    try {
      this.db = fs.initializeFirestore(app, { localCache: fs.persistentLocalCache({ tabManager: fs.persistentMultipleTabManager() }) });
    } catch { this.db = fs.getFirestore(app); }
    this.ref = fs.doc(this.db, DOCUMENT_PROGRAMME);
    this.user = await new Promise((res) => { const off = auth.onAuthStateChanged(this.auth, (u) => { off(); res(u); }); });
    while (!this.user) {
      const { email, password } = await askLogin(this._lastError);
      try { this.user = (await auth.signInWithEmailAndPassword(this.auth, email, password)).user; this._lastError = null; }
      catch (e) { this._lastError = loginError(e); }
    }
    return { user: this.user };
  }
  async logout() { await this.authMod.signOut(this.auth); location.reload(); }
  async resetPassword(email) { await this.authMod.sendPasswordResetEmail(this.auth, email); }

  async loadRemote() {
    const snap = await this.fs.getDoc(this.ref);
    return snap.exists() ? JSON.parse(snap.data().json) : null;
  }
  async save(dict) {
    const { setDoc, serverTimestamp, collection, addDoc } = this.fs;
    const json = JSON.stringify(dict);
    this._lastSent = json;
    await setDoc(this.ref, { json, modifiePar: this.user.email, modifieLe: serverTimestamp() });
    if (!this._historySaved) {   // une copie d'historique par session
      this._historySaved = true;
      await addDoc(collection(this.ref, "historique"), { json, modifiePar: this.user.email, modifieLe: serverTimestamp() });
    }
  }
  subscribe(onRemote, onStatus) {
    return this.fs.onSnapshot(this.ref, { includeMetadataChanges: true }, (snap) => {
      onStatus(snap.metadata.hasPendingWrites ? "en attente" : snap.metadata.fromCache ? "hors ligne" : "synchronisé");
      if (!snap.exists() || snap.metadata.hasPendingWrites) return;
      const d = snap.data();
      if (d.json !== this._lastSent) onRemote(JSON.parse(d.json), d.modifiePar);
    }, (err) => onStatus("erreur : " + (err.code === "permission-denied" ? "accès refusé (adresse non autorisée)" : err.message)));
  }
  async backups() {
    const { collection, getDocs, query, orderBy, limit } = this.fs;
    const snap = await getDocs(query(collection(this.ref, "historique"), orderBy("modifieLe", "desc"), limit(30)));
    return snap.docs.map((d) => {
      const v = d.data(), ts = v.modifieLe?.toDate?.();
      return { label: `${ts ? ts.toLocaleString("fr-FR") : "?"} – ${v.modifiePar}`, load: () => JSON.parse(v.json) };
    });
  }
}

function loginError(e) {
  const c = e?.code || "";
  if (c.includes("invalid-credential") || c.includes("wrong-password") || c.includes("user-not-found")) return "Adresse e-mail ou mot de passe incorrect.";
  if (c.includes("too-many-requests")) return "Trop d'essais. Réessayez dans quelques minutes.";
  if (c.includes("network")) return "Pas de connexion internet.";
  return "Connexion impossible : " + (e?.message || c);
}

// ---------------------------------------------------------------------------
// Synchronisation avec le logiciel Windows sur le même Wi-Fi
// (tablette Android ou navigateur) : données gardées localement + échange
// avec le PC dès qu'il est joignable.
// ---------------------------------------------------------------------------
const SYNC_KEY = `${LOCAL_KEY}-pc`;
export const isAndroid = () => typeof window !== "undefined" && !!window.AndroidBridge;

let bridgeSeq = 0;
const bridgeWait = new Map();
if (typeof window !== "undefined") {
  window.__bridgeCb = (id, status, text) => { const f = bridgeWait.get(id); if (f) { bridgeWait.delete(id); f({ status, text }); } };
}
function bridgeCall(fn, ...args) {
  return new Promise((resolve) => { const id = `r${++bridgeSeq}`; bridgeWait.set(id, resolve); window.AndroidBridge[fn](id, ...args); });
}
export async function http(method, url, headers = {}, body = "") {
  if (isAndroid()) return bridgeCall("request", method, url, JSON.stringify(headers), body || "");
  const ctl = new AbortController(), t = setTimeout(() => ctl.abort(), 6000);
  try {
    const r = await fetch(url, { method, headers, body: body || undefined, signal: ctl.signal, cache: "no-store" });
    return { status: r.status, text: await r.text() };
  } catch (e) { return { status: 0, text: String(e) }; } finally { clearTimeout(t); }
}
export function normalizeUrl(addr) {
  let a = String(addr || "").trim().replace(/\/+$/, "");
  if (!a) return "";
  if (!/^https?:\/\//i.test(a)) a = "http://" + a;
  if (!/:\d+$/.test(a.replace(/^https?:\/\//i, ""))) a += ":8765";
  return a;
}

export class SyncStore extends LocalStore {
  constructor() {
    super();
    this.mode = "sync";
    try { this.cfg = JSON.parse(localStorage.getItem(SYNC_KEY) || "{}"); } catch { this.cfg = {}; }
    this.cfg.rev ??= 0; this.cfg.dirty ??= false;
    this.cfg.device ||= isAndroid() ? "Tablette" : "Navigateur";
    this.saves = 0; this.busy = false; this.paused = false; this.state = "non configuré";
  }
  saveCfg() { localStorage.setItem(SYNC_KEY, JSON.stringify(this.cfg)); }
  get configured() { return !!(this.cfg.url && this.cfg.code); }
  async init() {
    // Page servie directement par le PC : l'adresse est connue
    if (!isAndroid() && /^https?:$/.test(location.protocol)) {
      const r = await http("GET", "/api/ping");
      if (r.status === 200) { try { if (JSON.parse(r.text).app === "rotations") { this.cfg.url = location.origin; this.servedByPc = true; this.saveCfg(); } } catch { } }
    }
    return { user: null };
  }
  async save(dict, opts = {}) {
    await super.save(dict);
    if (opts.initial) return;
    this.cfg.dirty = true; this.saves++; this.saveCfg();
    clearTimeout(this._kick); this._kick = setTimeout(() => this.tick(), 400);
  }
  setPc(url, code, device) {
    const u = normalizeUrl(url);
    if (u !== this.cfg.url) this.cfg.rev = 0;      // nouveau PC : on repart de sa version
    this.cfg.url = u; this.cfg.code = String(code || "").trim(); if (device) this.cfg.device = device;
    this.saveCfg(); this.tick();
  }
  disconnect() { this.cfg.url = ""; this.cfg.rev = 0; this.saveCfg(); this.status("non configuré"); }
  headers() { return { "X-Code": this.cfg.code, "X-Appareil": encodeURIComponent(this.cfg.device || ""), "Content-Type": "application/json" }; }
  status(s) { this.state = s; this.onStatus?.(s); }
  subscribe(onRemote, onStatus, onConflict) {
    this.onRemote = onRemote; this.onStatus = onStatus; this.onConflict = onConflict;
    this.tick(); this._loop = setInterval(() => this.tick(), 2500);
    return () => clearInterval(this._loop);
  }
  async ping(url, code) {
    const u = normalizeUrl(url);
    const p = await http("GET", `${u}/api/ping`);
    if (p.status !== 200) return { ok: false, msg: "PC injoignable à cette adresse (même Wi-Fi ? logiciel ouvert ?)" };
    const g = await http("GET", `${u}/api/programme?rev=-1`, { "X-Code": String(code || "").trim() });
    if (g.status === 401) return { ok: false, msg: "Code incorrect" };
    if (g.status !== 200) return { ok: false, msg: "Réponse inattendue du PC (" + g.status + ")" };
    try { return { ok: true, pc: JSON.parse(p.text).pc }; } catch { return { ok: true, pc: "PC" }; }
  }
  async tick() {
    if (this.busy || this.paused) return;
    if (!this.configured) { this.status("non configuré"); return; }
    this.busy = true;
    try {
      const base = this.cfg.url;
      if (this.cfg.dirty) {
        const savesAtSend = this.saves, dict = this.load();
        const r = await http("PUT", `${base}/api/programme`, this.headers(),
          JSON.stringify({ baseRev: this.cfg.rev, json: JSON.stringify(dict), appareil: this.cfg.device, force: !!this.cfg.force }));
        if (base !== this.cfg.url) return;
        if (r.status === 200) {
          this.cfg.rev = JSON.parse(r.text).rev; this.cfg.force = false;
          if (this.saves === savesAtSend) this.cfg.dirty = false;
          this.saveCfg(); this.status("synchronisé");
        } else if (r.status === 409) {
          const srv = JSON.parse(r.text);
          let same = false;   // mêmes avions des deux côtés (ex. même mise à jour faite sur le PC et ici) : pas de question
          try { same = JSON.stringify(JSON.parse(srv.json).aircraft) === JSON.stringify(this.load().aircraft); } catch { }
          if (same) { await this.applyRemote(srv); this.status("synchronisé"); return; }
          this.paused = true;
          const choice = await this.onConflict(srv);
          this.paused = false;
          if (choice === "pc") await this.applyRemote(srv);
          else { this.cfg.force = true; this.saveCfg(); setTimeout(() => this.tick(), 50); }
        } else this.fail(r);
      } else {
        const r = await http("GET", `${base}/api/programme?rev=${this.cfg.rev}`, this.headers());
        if (base !== this.cfg.url) return;
        if (r.status === 200) {
          const j = JSON.parse(r.text);
          if (j.changed && !this.cfg.dirty) await this.applyRemote(j);
          this.status("synchronisé");
        } else this.fail(r);
      }
    } catch (e) { console.error(e); this.status("erreur : " + e.message); }
    finally { this.busy = false; }
  }
  async applyRemote(j) {
    const dict = JSON.parse(j.json);
    await super.save(dict);
    this.cfg.rev = j.rev; this.cfg.dirty = false; this.cfg.force = false; this.saveCfg();
    this.onRemote?.(dict, j.modifiePar || "le PC");
  }
  fail(r) {
    if (r.status === 401) this.status("erreur : code incorrect");
    else this.status(this.cfg.dirty ? "PC injoignable – modifications gardées ici" : "PC injoignable");
  }
  async scan() {
    if (!isAndroid()) return [];
    const r = await bridgeCall("scan", 8765);
    try { return JSON.parse(r.text); } catch { return []; }
  }
}

export const createStore = () => (firebaseConfig ? new FirebaseStore() : new SyncStore());

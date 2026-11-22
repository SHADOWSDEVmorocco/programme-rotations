// Génération du tableau en PDF (A4 portrait), même design que l'impression papier
// et que le logiciel Windows. Sans bibliothèque externe : utilisable hors ligne sur la tablette.
import * as C from "./core.js";
import { WIDTHS } from "./pdf_metrics.js";

const MM = 72 / 25.4, W = 595.2756, H = 841.8898;
const M_TOP = 14 * MM, M_BOTTOM = 12 * MM, M_LEFT = 12 * MM, M_RIGHT = 10 * MM, TITLE_H = 14 * MM, DAY_W = 11 * MM;
const RGB = (hex) => [1, 3, 5].map((i) => (parseInt(hex.substr(i, 2), 16) / 255).toFixed(3)).join(" ");
const BLUE = RGB("#29a8e0"), GREY = RGB("#c4c7c5"), YELLOW = RGB("#f2c21b"), WHITE = "1 1 1", DARK = RGB("#1c2733");
const PATTERN = [BLUE, GREY, WHITE, WHITE, YELLOW, WHITE, WHITE];
const FONTS = { "Helvetica-Bold": "F1", "Times-Roman": "F2" };

// WinAnsi (Windows-1252) : caractères latins + tiret demi-cadratin
function ansi(text) {
  let out = "";
  for (const ch of String(text)) {
    let c = ch.charCodeAt(0);
    if (ch === "–") c = 0x96; else if (ch === "—") c = 0x97; else if (ch === "’") c = 0x92;
    else if (c > 255) c = 63; // « ? »
    const s = String.fromCharCode(c);
    out += s === "(" || s === ")" || s === "\\" ? "\\" + s : s;
  }
  return out;
}
const width = (text, font, size) => [...String(text)].reduce((s, ch) => s + (WIDTHS[font][ch] ?? 556), 0) * size / 1000;
const n = (x) => (Math.round(x * 100) / 100).toString();

function splitTwo(text) {
  const cut = [...text.matchAll(/ \/ | /g)].map((m) => m.index);
  if (!cut.length) return null;
  const mid = text.length / 2, i = cut.reduce((a, b) => (Math.abs(b - mid) < Math.abs(a - mid) ? b : a));
  const sep = text.startsWith(" / ", i) ? " / " : " ";
  return [text.slice(0, i) + (sep === " / " ? " /" : ""), text.slice(i + sep.length)];
}
function vtext(ops, x, y, w, h, text, size, font = "Helvetica-Bold", underline = false) {
  if (!text) return;
  const lines = width(text, font, size) > h - 3 * MM && width(text, font, 7) > h - 3 * MM ? splitTwo(text) : null;
  if (lines && w > 2.2 * 7) {
    let sz = Math.min(size, 8);
    while (sz > 4 && Math.max(...lines.map((l) => width(l, font, sz))) > h - 3 * MM) sz -= 0.5;
    lines.forEach((l, i) => {
      const tw = width(l, font, sz), px = x + w / 2 + (i === 0 ? 0.55 : -0.55) * sz * 1.05 - sz * 0.35, py = y + h / 2 + tw / 2;
      ops.push(`BT /${FONTS[font]} ${n(sz)} Tf 0 g 0 -1 1 0 ${n(px)} ${n(py)} Tm (${ansi(l)}) Tj ET`);
    });
    return;
  }
  while (size > 4 && width(text, font, size) > h - 3 * MM) size -= 0.5;
  const tw = width(text, font, size), cx = x + w / 2, cy = y + h / 2;
  const px = cx - size * 0.35, py = cy + tw / 2;           // rotation -90° : lecture de haut en bas
  ops.push(`BT /${FONTS[font]} ${n(size)} Tf 0 g 0 -1 1 0 ${n(px)} ${n(py)} Tm (${ansi(text)}) Tj ET`);
  if (underline) ops.push(`0.6 w 0 G ${n(px - 1.5)} ${n(cy + tw / 2)} m ${n(px - 1.5)} ${n(cy - tw / 2)} l S`);
}
const st = (u, s, t) => (u && (C.up(s) || t) ? `${C.up(s)} : ${C.safeTime(t) !== null ? C.fmtTime(C.safeTime(t)) : (t || "")}` : "");

function pageOps(ac) {
  const ops = [], x0 = M_LEFT, yTop = H - M_TOP, tw = W - M_LEFT - M_RIGHT;
  const colW = (tw - DAY_W) / 21, dayH = (H - M_TOP - M_BOTTOM - TITLE_H) / C.DAYS.length, yt = yTop - TITLE_H;
  ops.push("0 G 0.6 w");
  ops.push(`${n(x0)} ${n(yt)} ${n(colW)} ${n(TITLE_H)} re S ${n(x0 + colW)} ${n(yt)} ${n(tw - colW)} ${n(TITLE_H)} re S`);
  const title = ac.week ? `${ac.title}  –  ${C.weekShort(ac.week).toUpperCase()}` : ac.title;
  let tsize = 22;
  while (tsize > 10 && width(title, "Times-Roman", tsize) > tw - colW - 8) tsize -= 0.5;
  ops.push(`BT /F2 ${n(tsize)} Tf ${RGB("#333333")} rg 1 0 0 1 ${n(x0 + colW + (tw - colW) / 2 - width(title, "Times-Roman", tsize) / 2)} ${n(yt + TITLE_H / 2 - tsize * 0.32)} Tm (${ansi(title)}) Tj ET`);
  const wk = C.weekTotal(ac);
  if (wk) vtext(ops, x0, yt, colW, TITLE_H, C.fmtDuration(wk), 7);
  C.DAYS.forEach((day, di) => {
    const y = yt - (di + 1) * dayH, dc = C.computeDay(ac.days[di]);
    [2, 1, 0].forEach((ri, bi) => {
      const r = ac.days[di][ri], c = dc.rotations[ri], u = C.isUsed(r);
      const blue = ri === 2 ? [dc.total ? C.fmtDuration(dc.total) : "", true] : [c.ecart !== null ? `Escale ${c.ecart}mn` : "", false];
      const cells = [blue, [c.duree !== null ? C.fmtDuration(c.duree) : "", true], [st(u, r.arrivee, r.h_arrivee), false],
        [st(u, r.escale, r.h_redepart), false], [c.escale !== null ? `Escale ${c.escale}mn` : "", false],
        C.up(r.remarque) ? [C.up(r.remarque), true] : [st(u, r.escale, r.h_arrivee_escale), false], [st(u, r.depart, r.h_depart), false]];
      cells.forEach(([txt, big], k) => {
        const x = x0 + (bi * 7 + k) * colW;
        ops.push(`${PATTERN[k]} rg 0 G 0.6 w ${n(x)} ${n(y)} ${n(colW)} ${n(dayH)} re B`);
        vtext(ops, x, y, colW, dayH, txt, big ? 10 : 8.5, "Helvetica-Bold", ri === 2 && k === 0 && !!txt);
      });
    });
    const xd = x0 + 21 * colW;
    ops.push(`1 1 1 rg 0 G 0.6 w ${n(xd)} ${n(y)} ${n(DAY_W)} ${n(dayH)} re B`);
    vtext(ops, xd, y, DAY_W, dayH, C.dayLabel(ac.week, di), 12, "Times-Roman");
  });
  const yBot = yt - C.DAYS.length * dayH;
  ops.push(`${DARK} RG 2.2 w`);
  for (let b = 0; b < 3; b++) { const xx = x0 + b * 7 * colW; ops.push(`${n(xx)} ${n(yt)} m ${n(xx)} ${n(yBot)} l S`); }
  for (const xx of [x0 + 21 * colW, x0 + tw]) ops.push(`${n(xx)} ${n(yt)} m ${n(xx)} ${n(yBot)} l S`);
  ops.push(`0 G 0.8 w ${n(x0)} ${n(yBot)} m ${n(x0 + tw)} ${n(yBot)} l S`);
  return ops.join("\n");
}

/** Renvoie le PDF (Uint8Array) des avions donnés, une page par avion. */
export function buildPdf(aircraft, title = "Programme des rotations") {
  const objs = [];                       // objets PDF (chaînes latin-1)
  const add = (s) => { objs.push(s); return objs.length; };
  const catalog = add(""), pages = add("");
  const f1 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
  const f2 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>");
  const kids = [];
  for (const ac of aircraft) {
    const content = pageOps(ac);
    const cid = add(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`);
    kids.push(add(`<< /Type /Page /Parent ${pages} 0 R /MediaBox [0 0 ${n(W)} ${n(H)}] /Resources << /Font << /F1 ${f1} 0 R /F2 ${f2} 0 R >> >> /Contents ${cid} 0 R >>`));
  }
  objs[catalog - 1] = `<< /Type /Catalog /Pages ${pages} 0 R >>`;
  objs[pages - 1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(" ")}] /Count ${kids.length} >>`;
  const utf16 = (t) => "<FEFF" + [...String(t)].map((ch) => ch.charCodeAt(0).toString(16).padStart(4, "0")).join("").toUpperCase() + ">";
  const info = add(`<< /Title ${utf16(title)} /Producer (Programme des Rotations) >>`);
  let out = "%PDF-1.4\n%\xE2\xE3\xCF\xD3\n";
  const offsets = [];
  objs.forEach((o, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offsets.map((o) => `${String(o).padStart(10, "0")} 00000 n \n`).join("");
  out += `trailer\n<< /Size ${objs.length + 1} /Root ${catalog} 0 R /Info ${info} 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  const bytes = new Uint8Array(out.length);
  for (let i = 0; i < out.length; i++) bytes[i] = out.charCodeAt(i) & 0xff;
  return bytes;
}
export function toBase64(bytes) {
  let s = ""; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

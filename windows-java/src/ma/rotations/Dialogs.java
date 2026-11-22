package ma.rotations;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.logging.Level;

import static ma.rotations.Core.*;

/** Boîtes de dialogue. */
final class Dialogs {
    private Dialogs() { }

    static JDialog dialog(MainWindow w, String title, String icon) {
        JDialog d = new JDialog(w, title, true);
        d.setIconImage(Icons.get(icon, 24).getImage());
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        return d;
    }
    static JPanel buttons(JComponent... b) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        for (JComponent c : b) p.add(c);
        return p;
    }
    static JButton btn(String text, String icon, Runnable r) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon, 16));
        b.addActionListener(e -> r.run());
        return b;
    }
    static void show(JDialog d, JComponent content, JComponent south, MainWindow w) {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.add(content, BorderLayout.CENTER);
        if (south != null) root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ durées de vol
    static void durations(MainWindow w) {
        Aircraft ac = w.currentAc();
        JDialog d = dialog(w, "Durées de vol – " + ac.title, "clock");
        DefaultTableModel m = new DefaultTableModel(new String[]{"De", "Vers", "Durée (HH:MM)"}, 0);
        new TreeMap<>(ac.durations).forEach((k, v) -> { String[] p = k.split(">", 2); m.addRow(new Object[]{p[0], p.length > 1 ? p[1] : "", fmtDuration(v)}); });
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(470, 440));
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.add(new JLabel("<html>Durées de vol de « " + ac.title + " », utilisées pour remplir les heures.<br>"
                + "Si un trajet manque, celui d'un avion du même type est utilisé.</html>"), BorderLayout.NORTH);
        c.add(sp, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons(btn("Ajouter un trajet", "clock", () -> { m.addRow(new Object[]{"", "", ""}); t.editCellAt(m.getRowCount() - 1, 0); }),
                btn("Supprimer la ligne", "clear", () -> { if (t.getSelectedRow() >= 0) m.removeRow(t.getSelectedRow()); })), BorderLayout.WEST);
        south.add(buttons(btn("Annuler", null, d::dispose), btn("Enregistrer", "save", () -> {
            if (t.isEditing()) t.getCellEditor().stopCellEditing();
            Map<String, Integer> out = new LinkedHashMap<>();
            for (int r = 0; r < m.getRowCount(); r++) {
                String a = up(String.valueOf(m.getValueAt(r, 0))), b = up(String.valueOf(m.getValueAt(r, 1))), du = String.valueOf(m.getValueAt(r, 2)).trim();
                if (a.isEmpty() && b.isEmpty() && du.isEmpty()) continue;
                Integer mn = safeTime(du);
                if (a.isEmpty() || b.isEmpty() || mn == null || mn == 0) { w.warn("Ligne " + (r + 1) + " invalide : indiquez De, Vers et une durée HH:MM."); return; }
                out.put(a + ">" + b, mn);
            }
            w.snapshot(); original(ac).durations = out; d.dispose(); w.rebuildTabs(); w.touch("PC");
        })), BorderLayout.EAST);
        show(d, c, south, w);
    }

    // ------------------------------------------------------------------ ajouter un avion
    static void addAircraft(MainWindow w) {
        JDialog d = dialog(w, "Ajouter un avion", "plane");
        JTextField name = new JTextField("ERJ.145 - AVION " + (w.prog.aircraft.stream().filter(a -> family(a.title).equals("ERJ.145")).count() + 1), 24);
        JTextField base = new JTextField(w.originalAc().base, 12);
        List<Aircraft> list = w.prog.aircraft;
        String[] models = new String[list.size() + 1];
        models[0] = "Aucun (durées de vol à saisir)";
        for (int i = 0; i < list.size(); i++) models[i + 1] = list.get(i).title;
        JComboBox<String> model = new JComboBox<>(models);
        model.setSelectedIndex(Math.max(0, w.tabs.getSelectedIndex()) + 1);
        String[] progs = new String[list.size() + 1];
        progs[0] = "Vide";
        for (int i = 0; i < list.size(); i++) progs[i + 1] = "Copie du programme type de " + list.get(i).title;
        JComboBox<String> start = new JComboBox<>(progs);
        JCheckBox recompute = new JCheckBox("Recalculer les heures avec les durées de vol du nouvel avion", false);
        JPanel f = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4); g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"Nom du tableau", name}, {"Base", base}, {"Type d'avion (reprend ses durées de vol)", model}, {"Programme de départ", start}};
        for (int i = 0; i < rows.length; i++) { g.gridy = i; g.gridx = 0; f.add(new JLabel((String) rows[i][0]), g); g.gridx = 1; f.add((Component) rows[i][1], g); }
        g.gridy = rows.length; g.gridx = 1; f.add(recompute, g);
        g.gridy++; g.gridx = 0; g.gridwidth = 2;
        f.add(new JLabel("<html><small>Les avions dont le nom commence pareil (ex. « ERJ.145 … ») partagent aussi leurs durées de vol.</small></html>"), g);
        show(d, f, buttons(btn("Annuler", null, d::dispose), btn("Ajouter", "plane", () -> {
            String n = up(name.getText());
            if (n.isEmpty()) { w.warn("Indiquez le nom du tableau."); return; }
            for (Aircraft a : list) if (up(a.title).equals(n)) { w.warn("« " + n + " » existe déjà."); return; }
            w.snapshot();
            Aircraft a = newAircraft(w.prog, n, base.getText(), model.getSelectedIndex() > 0 ? list.get(model.getSelectedIndex() - 1) : null);
            List<String> missing = new ArrayList<>();
            if (start.getSelectedIndex() > 0) missing = cloneDays(w.prog, list.get(start.getSelectedIndex() - 1), a, recompute.isSelected());
            d.dispose();
            w.week = null;                                   // le programme type du nouvel avion est affiché
            w.rebuildTabs();
            w.tabs.setSelectedIndex(w.tabs.getTabCount() - 1);
            w.touch("PC");
            w.status("Avion « " + n + " » ajouté." + (missing.isEmpty() ? "" : " À compléter : " + String.join(" ; ", missing)));
        })), w);
    }

    // ------------------------------------------------------------------ cloner le programme
    static void cloneProgram(MainWindow w) {
        Aircraft src = w.currentAc();
        JDialog d = dialog(w, "Cloner le programme", "copy");
        List<JCheckBox> boxes = new ArrayList<>();
        JPanel targets = new JPanel(new GridLayout(0, 1, 0, 4));
        for (Aircraft a : w.prog.aircraft) {
            if (a == original(src)) continue;
            JCheckBox cb = new JCheckBox(a.title);
            cb.putClientProperty("ac", a);
            boxes.add(cb); targets.add(cb);
        }
        if (boxes.isEmpty()) { w.info("Il n'y a pas d'autre avion. Ajoutez d'abord un avion."); return; }
        JCheckBox recompute = new JCheckBox("Recalculer les heures avec les durées de vol de chaque avion (garde les destinations et le 1er départ)");
        JPanel c = new JPanel(new BorderLayout(0, 10));
        c.add(new JLabel("<html>Copier <b>" + src.title + "</b> – <b>" + weekLabel(w.week) + "</b><br>sur les avions cochés (leur " + weekShort(w.week) + " sera remplacé) :</html>"), BorderLayout.NORTH);
        c.add(targets, BorderLayout.CENTER);
        c.add(recompute, BorderLayout.SOUTH);
        show(d, c, buttons(btn("Annuler", null, d::dispose), btn("Cloner", "copy", () -> {
            List<Aircraft> sel = new ArrayList<>();
            for (JCheckBox cb : boxes) if (cb.isSelected()) sel.add((Aircraft) cb.getClientProperty("ac"));
            if (sel.isEmpty()) { w.warn("Cochez au moins un avion."); return; }
            w.snapshot();
            List<String> missing = new ArrayList<>(), names = new ArrayList<>();
            for (Aircraft a : sel) { missing.addAll(cloneDays(w.prog, src, view(a, w.week), recompute.isSelected())); names.add(a.title); }
            d.dispose();
            w.rebuildTabs();
            w.touch("PC");
            w.info("Programme copié sur : " + String.join(", ", names) + (missing.isEmpty() ? "" : "\n\nÀ compléter :\n• " + String.join("\n• ", new LinkedHashSet<>(missing))));
        })), w);
    }

    // ------------------------------------------------------------------ compteur de fréquences
    static void frequencies(MainWindow w) {
        JDialog d = dialog(w, "Fréquences par jour et par destination", "cities");
        // choix : tous les avions, un type d'avion, ou un avion
        List<String> labels = new ArrayList<>(List.of("Tous les avions"));
        List<java.util.function.Predicate<Aircraft>> filters = new ArrayList<>(List.of(a -> true));
        LinkedHashSet<String> fams = new LinkedHashSet<>();
        for (Aircraft a : w.prog.aircraft) fams.add(family(a.title));
        for (String f : fams) {
            long n = w.prog.aircraft.stream().filter(a -> family(a.title).equals(f)).count();
            if (n > 1) { labels.add("Type " + f + " (" + n + " avions)"); filters.add(a -> family(a.title).equals(f)); }
        }
        for (Aircraft a : w.prog.aircraft) { labels.add(a.title); final Aircraft x = a; filters.add(b -> b == x); }
        JComboBox<String> who = new JComboBox<>(labels.toArray(new String[0]));
        String[] cols = new String[9];
        cols[0] = "Destination";
        for (int i = 0; i < 7; i++) cols[i + 1] = dayLabel(w.week, i).replace(" ", "  ");
        cols[8] = "Semaine";
        DefaultTableModel m = new DefaultTableModel(cols, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } };
        Frequencies[] cur = new Frequencies[1];
        JTable t = new JTable(m) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
                if (r < 0 || c < 1 || c > 7 || cur[0] == null || r >= cur[0].counts.size()) return null;
                String dest = new ArrayList<>(cur[0].counts.keySet()).get(r);
                List<String> l = cur[0].details.get(dest).get(c - 1);
                return l.isEmpty() ? null : "<html><b>" + dest + " – " + DAYS[c - 1] + "</b><br>" + String.join("<br>", l) + "</html>";
            }
        };
        t.setRowHeight(26);
        t.getTableHeader().setReorderingAllowed(false);
        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, sel, f, r, c);
                boolean total = r == tb.getRowCount() - 1;
                l.setHorizontalAlignment(c == 0 ? LEFT : CENTER);
                l.setFont(l.getFont().deriveFont(total || c == 0 || c == 8 ? Font.BOLD : Font.PLAIN));
                String s = String.valueOf(v);
                Color bg = Color.WHITE;
                if (total) bg = new Color(0xeef3f7);
                else if (c == 8) bg = new Color(0xe1f2fb);
                else if (c > 0 && !s.isEmpty()) bg = new Color(0xfdf3cf);
                l.setBackground(sel ? new Color(0xcfe9f7) : bg);
                l.setForeground(new Color(0x1c2733));
                return l;
            }
        });
        t.getColumnModel().getColumn(0).setPreferredWidth(140);
        JLabel info = new JLabel();
        Runnable fill = () -> {
            List<Aircraft> sel = new ArrayList<>();
            for (Aircraft a : views(w.prog, w.week)) if (filters.get(who.getSelectedIndex()).test(original(a))) sel.add(a);
            cur[0] = Core.frequencies(sel);
            m.setRowCount(0);
            cur[0].counts.forEach((dest, v) -> {
                Object[] row = new Object[9];
                row[0] = dest;
                for (int i = 0; i < 8; i++) row[i + 1] = v[i] == 0 ? "" : v[i];
                m.addRow(row);
            });
            Object[] tot = new Object[9];
            tot[0] = "TOTAL";
            for (int i = 0; i < 8; i++) tot[i + 1] = cur[0].totals[i];
            m.addRow(tot);
            info.setText("<html>" + weekLabel(w.week) + " – " + cur[0].counts.size() + " destinations. "
                    + "Une fréquence = une rotation qui dessert la destination (la base n'est pas comptée). Survolez un chiffre pour le détail.</html>");
        };
        who.addActionListener(e -> fill.run());
        fill.run();
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(900, Math.min(560, 60 + 26 * (m.getRowCount() + 1))));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(new JLabel("Avions :"), BorderLayout.WEST);
        top.add(who, BorderLayout.CENTER);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.add(top, BorderLayout.NORTH); c.add(sp, BorderLayout.CENTER); c.add(info, BorderLayout.SOUTH);
        show(d, c, buttons(btn("Copier (Excel)", "copy", () -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < m.getColumnCount(); i++) b.append(i > 0 ? "\t" : "").append(m.getColumnName(i).replace("  ", " "));
            for (int r = 0; r < m.getRowCount(); r++) { b.append("\n"); for (int i = 0; i < m.getColumnCount(); i++) b.append(i > 0 ? "\t" : "").append(m.getValueAt(r, i)); }
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(b.toString()), null);
            w.status("Tableau des fréquences copié : collez-le dans Excel (Ctrl+V).");
        }), btn("Fermer", null, d::dispose)), w);
    }

    // ------------------------------------------------------------------ villes
    static void cities(MainWindow w) {
        JDialog d = dialog(w, "Villes proposées", "cities");
        DefaultListModel<String> m = new DefaultListModel<>();
        for (String s : w.prog.stations) m.addElement(s);
        JList<String> l = new JList<>(m);
        l.setCellRenderer((li, v, i, sel, f) -> {
            JLabel lab = new JLabel(v, Icons.get("cities", 16), SwingConstants.LEFT);
            lab.setOpaque(true);
            lab.setBackground(sel ? new Color(0xe1f2fb) : Color.WHITE);
            lab.setBorder(new EmptyBorder(3, 4, 3, 4));
            return lab;
        });
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(360, 340));
        JTextField nf = new JTextField(16);
        Runnable add = () -> { String v = up(nf.getText()); if (!v.isEmpty() && !m.contains(v)) m.addElement(v); nf.setText(""); };
        nf.addActionListener(e -> add.run());
        JPanel addRow = new JPanel(new BorderLayout(6, 0));
        addRow.add(nf, BorderLayout.CENTER);
        addRow.add(btn("Ajouter", "cities", add), BorderLayout.EAST);
        JPanel c = new JPanel(new BorderLayout(0, 8));
        c.add(new JLabel("<html>Ces villes sont proposées dans les listes,<br>en plus de celles déjà utilisées.</html>"), BorderLayout.NORTH);
        c.add(sp, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.add(addRow, BorderLayout.NORTH);
        bottom.add(btn("Supprimer la ville sélectionnée", "clear", () -> { if (l.getSelectedIndex() >= 0) m.remove(l.getSelectedIndex()); }), BorderLayout.SOUTH);
        c.add(bottom, BorderLayout.SOUTH);
        show(d, c, buttons(btn("Annuler", null, d::dispose), btn("Enregistrer", "save", () -> {
            w.snapshot(); w.prog.stations = new ArrayList<>(Collections.list(m.elements())); d.dispose(); w.changed(false);
        })), w);
    }

    // ------------------------------------------------------------------ paramètres
    static void settings(MainWindow w) {
        JDialog d = dialog(w, "Paramètres", "settings");
        Settings s = w.prog.settings;
        JSpinner def = new JSpinner(new SpinnerNumberModel(s.escaleDefaut, 0, 600, 5)), base = new JSpinner(new SpinnerNumberModel(s.escaleBase, 0, 600, 5));
        JTextField max = new JTextField(s.maxJour, 6);
        JPanel f = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4); g.anchor = GridBagConstraints.WEST;
        Object[][] rows = {{"Escale par défaut à l'escale (jaune) – mn", def}, {"Escale par défaut à la base (bleu) – mn", base}, {"Total maximum par jour (HH:MM)", max}};
        for (int i = 0; i < rows.length; i++) {
            g.gridy = i; g.gridx = 0; f.add(new JLabel((String) rows[i][0]), g);
            g.gridx = 1; f.add((Component) rows[i][1], g);
        }
        g.gridy = 3; g.gridx = 0; g.gridwidth = 2;
        f.add(new JLabel("<html><small>Les escales minimum (alertes) se règlent en haut de l'onglet de chaque avion.</small></html>"), g);
        show(d, f, buttons(btn("Annuler", null, d::dispose), btn("Enregistrer", "save", () -> {
            Integer mj = safeTime(max.getText());
            if (mj == null) { w.warn("Total maximum : format HH:MM (ex. 13:00)"); return; }
            w.snapshot();
            s.escaleDefaut = (Integer) def.getValue(); s.escaleBase = (Integer) base.getValue(); s.maxJour = fmtTime(mj);
            d.dispose(); w.changed(false);
        })), w);
    }

    // ------------------------------------------------------------------ tablette / Wi-Fi
    static void sync(MainWindow w) {
        JDialog d = dialog(w, "Synchronisation tablette / Wi-Fi", "wifi");
        JLabel info = new JLabel();
        info.setOpaque(true);
        info.setBackground(new Color(0xeef3f7));
        info.setBorder(new EmptyBorder(10, 12, 10, 12));
        DefaultListModel<String> devs = new DefaultListModel<>();
        Runnable refresh = () -> {
            SyncServer s = w.sync;
            if (s.running()) {
                StringBuilder a = new StringBuilder();
                for (String ip : SyncServer.localIps()) a.append("<b style='font-size:13px'>").append(ip).append(":").append(s.port).append("</b><br>");
                info.setText("<html>Adresse du PC :<br>" + a + "<br>Code : <b style='font-size:20px'>" + s.code + "</b></html>");
            } else info.setText(w.syncCfg.enabled ? "<html>Erreur : " + s.error + "</html>" : "Synchronisation désactivée.");
            devs.clear();
            long now = System.currentTimeMillis();
            s.devices.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).forEach(e -> {
                long ago = (now - e.getValue()) / 1000;
                devs.addElement(e.getKey() + " – il y a " + (ago < 120 ? ago + " s" : (ago / 60) + " min"));
            });
            d.pack();
        };
        JCheckBox en = new JCheckBox("Activer la synchronisation Wi-Fi", w.syncCfg.enabled);
        JSpinner port = new JSpinner(new SpinnerNumberModel(w.syncCfg.port, 1024, 65535, 1));
        port.setEditor(new JSpinner.NumberEditor(port, "#"));
        JList<String> dl = new JList<>(devs);
        dl.setVisibleRowCount(4);
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        JLabel intro = new JLabel("<html><div style='width:420px'>La tablette (application Android) et le PC doivent être sur le <b>même Wi-Fi</b>, avec ce logiciel ouvert.<br>"
                + "Sur la tablette : bouton Wi-Fi en haut à droite → saisissez l'adresse et le code ci-dessous (ou « Rechercher le PC »).</div></html>");
        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portRow.add(en); portRow.add(new JLabel("   Port :")); portRow.add(port);
        JLabel note = new JLabel("<html><div style='width:420px'><small>Au premier démarrage, Windows peut demander l'autorisation du pare-feu : cochez « Réseaux privés » puis « Autoriser l'accès ».<br>"
                + "Un navigateur sur le même Wi-Fi peut aussi ouvrir l'adresse ci-dessus.</small></div></html>");
        for (JComponent x : new JComponent[]{intro, info, portRow, new JLabel("Appareils vus récemment :"), new JScrollPane(dl), note}) {
            x.setAlignmentX(Component.LEFT_ALIGNMENT);
            c.add(x);
            c.add(Box.createVerticalStrut(8));
        }
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons(btn("Nouveau code", "settings", () -> {
            if (w.confirm("Créer un nouveau code ? Il faudra le saisir à nouveau sur la tablette.")) {
                w.syncCfg.code = SyncServer.newCode(); w.syncCfg.save(w.syncCfgPath); w.applySyncConfig(true); refresh.run();
            }
        }), btn("Ouvrir dans le navigateur", "wifi", () -> Sharing.browse("http://localhost:" + w.sync.port + "/"))), BorderLayout.WEST);
        south.add(buttons(btn("Annuler", null, d::dispose), btn("OK", null, () -> {
            w.syncCfg.enabled = en.isSelected(); w.syncCfg.port = (Integer) port.getValue(); w.syncCfg.save(w.syncCfgPath);
            w.applySyncConfig(true); d.dispose();
        })), BorderLayout.EAST);
        refresh.run();
        show(d, c, south, w);
    }

    // ------------------------------------------------------------------ envoi WhatsApp / e-mail (PDF uniquement)
    static JButton bigButton(String text, String icon, Color bg) {
        JButton b = new JButton(text, Icons.get(icon, 32));
        b.setVerticalTextPosition(SwingConstants.BOTTOM);
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 17f));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setPreferredSize(new Dimension(200, 104));
        b.putClientProperty("JButton.buttonType", "roundRect");
        b.setFocusPainted(false);
        return b;
    }

    static void send(MainWindow w) {
        Path cfgPath = w.dataFile.getParent().resolve("envoi.json");
        Map<String, Object> cfg;
        try { cfg = Json.obj(Json.parse(Storage.read(cfgPath))); } catch (Exception e) { cfg = new LinkedHashMap<>(); }
        JDialog d = dialog(w, "Envoyer le tableau en PDF", "send");
        JRadioButton cur = new JRadioButton(w.currentAc().title), all = new JRadioButton("Tous les avions (" + w.prog.aircraft.size() + " pages)");
        for (JRadioButton r : new JRadioButton[]{cur, all}) r.setFont(r.getFont().deriveFont(15f));
        ButtonGroup bg = new ButtonGroup(); bg.add(cur); bg.add(all);
        ("all".equals(cfg.get("scope")) ? all : cur).setSelected(true);
        JButton bwa = bigButton("WhatsApp", "whatsapp", new Color(0x25d366)), bmail = bigButton("E-mail", "mail", new Color(0x29a8e0));
        // semaine à envoyer : programme type ou une semaine datée (historique)
        List<String> wkeys = new ArrayList<>(Collections.singletonList((String) null));
        List<String> ws = new ArrayList<>(weeks(w.prog)); Collections.reverse(ws); wkeys.addAll(ws);
        JComboBox<String> wk = new JComboBox<>();
        for (String k : wkeys) wk.addItem(weekLabel(k));
        wk.setSelectedIndex(Math.max(0, wkeys.indexOf(w.week)));
        wk.setFont(wk.getFont().deriveFont(14f));
        JPanel wkRow = new JPanel(new BorderLayout(8, 0));
        wkRow.add(new JLabel("Programme :"), BorderLayout.WEST);
        wkRow.add(wk, BorderLayout.CENTER);
        JPanel scope = new JPanel(new GridLayout(3, 1, 0, 6));
        scope.add(wkRow); scope.add(cur); scope.add(all);
        JPanel big = new JPanel(new GridLayout(1, 2, 14, 0));
        big.add(bwa); big.add(bmail);
        JLabel note = new JLabel("Le PDF du tableau (même présentation que l'impression) est joint automatiquement.");
        note.setFont(note.getFont().deriveFont(12f));
        note.setForeground(Color.DARK_GRAY);
        JPanel c = new JPanel(new BorderLayout(0, 14));
        c.add(scope, BorderLayout.NORTH); c.add(big, BorderLayout.CENTER); c.add(note, BorderLayout.SOUTH);
        java.util.function.Consumer<String> run = target -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", all.isSelected() ? "all" : "cur");
            try { Files.write(cfgPath, Json.write(m).getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            d.dispose();
            doSend(w, target, all.isSelected(), wkeys.get(wk.getSelectedIndex()));
        };
        bwa.addActionListener(e -> run.accept("whatsapp"));
        bmail.addActionListener(e -> run.accept("email"));
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons(btn("Enregistrer le PDF", "pdf", () -> run.accept("pdf"))), BorderLayout.WEST);
        south.add(buttons(btn("Fermer", null, d::dispose)), BorderLayout.EAST);
        show(d, c, south, w);
    }

    static void doSend(MainWindow w, String target, boolean all) { doSend(w, target, all, w.week); }
    static void doSend(MainWindow w, String target, boolean all, String week) {
        List<Aircraft> lst = all ? views(w.prog, week) : List.of(view(w.originalAc(), week));
        String label = all ? "tous les avions" : lst.get(0).title;
        String when = week == null ? "programme type" : weekShort(week);
        String subject = "Programme des rotations – " + label + " – " + when;
        String body = "Bonjour,\n\nVeuillez trouver ci-joint le programme des rotations (" + label + ", " + when + ") en PDF.\n\nCordialement";
        Path pdf;
        try {
            pdf = Sharing.documentsDir().resolve("Programme_" + label.replaceAll("[^A-Za-z0-9]", "_") + "_"
                    + (week == null ? "type" : "semaine_" + week) + ".pdf");
            Pdf.write(pdf, lst, subject);
        } catch (Exception e) {
            w.error("Création du PDF impossible :\n" + e.getMessage());
            return;
        }
        switch (target) {
            case "pdf":
                Sharing.reveal(pdf);
                w.status("PDF enregistré : " + pdf);
                break;
            case "email":
                w.status("Ouverture de la messagerie avec le PDF joint…");
                w.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                new SwingWorker<Sharing.MailResult, Void>() {
                    @Override protected Sharing.MailResult doInBackground() { return Sharing.emailWithAttachment(pdf, subject, body); }
                    @Override protected void done() {
                        w.setCursor(Cursor.getDefaultCursor());
                        Sharing.MailResult r;
                        try { r = get(); } catch (Exception e) { r = Sharing.MailResult.UNAVAILABLE; }
                        if (r == Sharing.MailResult.OPENED) { w.status("Message prêt avec le PDF joint : ajoutez le destinataire puis Envoyer."); return; }
                        if (r == Sharing.MailResult.CANCELLED) { w.status("Envoi annulé."); return; }
                        // messagerie web ou sans pièce jointe automatique : le PDF est prêt à être joint
                        Sharing.copyFile(pdf);
                        Sharing.reveal(pdf);
                        Sharing.browse("mailto:?subject=" + Sharing.enc(subject) + "&body=" + Sharing.enc(body));
                        steps(w, "Envoyer par e-mail", "mail", pdf,
                                "Dans le message qui s'ouvre, faites Ctrl+V : le PDF se joint (il est déjà copié)",
                                "Ajoutez le destinataire",
                                "Cliquez sur Envoyer");
                    }
                }.execute();
                break;
            default:
                Sharing.copyFile(pdf);
                Sharing.openWhatsApp();
                steps(w, "Envoyer par WhatsApp", "whatsapp", pdf,
                        "Dans WhatsApp, ouvrez la conversation",
                        "Faites Ctrl+V (coller) : le PDF apparaît",
                        "Cliquez sur Envoyer");
        }
    }

    /** Petite fenêtre « 3 étapes » avec le nom du PDF. */
    static void steps(MainWindow w, String title, String icon, Path pdf, String... st) {
        JDialog d = dialog(w, title, icon);
        JPanel c = new JPanel(new GridLayout(0, 1, 0, 8));
        for (int i = 0; i < st.length; i++) {
            JLabel l = new JLabel("<html><b style='font-size:15px'>" + (i + 1) + ".</b>&nbsp; <span style='font-size:13px'>" + st[i] + "</span></html>");
            c.add(l);
        }
        JLabel f = new JLabel("PDF : " + pdf.getFileName(), Icons.get("pdf", 16), SwingConstants.LEFT);
        f.setForeground(Color.DARK_GRAY);
        c.add(f);
        show(d, c, buttons(btn("Ouvrir le dossier du PDF", "folder", () -> Sharing.reveal(pdf)), btn("OK", null, d::dispose)), w);
    }

    // ------------------------------------------------------------------ aide
    static void help(MainWindow w) {
        JTextArea t = new JTextArea(HELP);
        t.setEditable(false);
        t.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        t.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(640, 560));
        JDialog d = dialog(w, "Mode d'emploi", "help");
        show(d, sp, buttons(btn("Fermer", null, d::dispose)), w);
    }

    static final String HELP = String.join("\n",
            "UNE LIGNE = UNE ROTATION",
            "Départ → Escale (arrivée, temps d'escale, redépart) → Arrivée.",
            "",
            "SAISIE DES HEURES",
            "Format HH:MM. Vous pouvez taper 0700, 7h00 ou 7:00 : c'est converti en 07:00.",
            "",
            "CALCULÉ AUTOMATIQUEMENT",
            "• Escale (jaune) = redépart − arrivée à l'escale",
            "• Durée rotation (gris) = arrivée finale − départ",
            "• Escale base (bleu) = départ rotation suivante − arrivée",
            "• Total jour = somme des durées des rotations ; bloc de vol = somme des vols",
            "",
            "PROPOSITIONS AUTOMATIQUES",
            "Cliquez dans une case : une liste s'ouvre avec les propositions les plus",
            "probables en premier (destinations habituelles, arrivée habituelle, heures",
            "calculées). Tapez quelques lettres pour filtrer. Entrée = valider.",
            "",
            "REMPLISSAGE INSTANTANÉ",
            "Choisissez la destination (colonne « Escale ») : départ, arrivée, heures et",
            "escale se remplissent. Modifier une heure décale les heures suivantes.",
            "Nouvelle ville (ex. TENERIFE) : la durée de vol est demandée une seule fois.",
            "F5 : complète toutes les cases vides de la semaine.",
            "",
            "ESCALES MODIFIABLES",
            "Colonnes « Escale (mn) » et « Esc. base » : tapez les minutes (ex. 75).",
            "Escale mini / Esc. base mini (alertes) : en haut de l'onglet de chaque avion.",
            "",
            "SEMAINE TYPE",
            "Programme → Semaine type Dakhla Air Connect : compléter les jours vides,",
            "ou remplacer les 4 avions.",
            "",
            "ENVOYER (Ctrl+M) : le PDF du tableau, par WhatsApp ou par e-mail.",
            "  E-mail : votre messagerie s'ouvre avec le PDF déjà joint.",
            "  WhatsApp : WhatsApp s'ouvre, le PDF est copié → Ctrl+V dans la conversation.",
            "",
            "SYNCHRONISATION TABLETTE (même Wi-Fi)",
            "Synchronisation → Tablette / Wi-Fi : adresse et code à saisir sur la tablette.",
            "",
            "SEMAINES DATÉES ET HISTORIQUE",
            "Barre « Programme » : Programme type (sans date) ou une semaine datée.",
            "◀ ▶ : semaine précédente / suivante (création proposée si elle n'existe pas).",
            "Nouvelle semaine (Ctrl+N) : à partir du programme type, d'une autre semaine",
            "ou vide. Historique (Ctrl+H) : ouvrir, exporter en PDF, dupliquer, supprimer.",
            "Impression, PDF et envoi : la semaine affichée (ou choisie dans « Envoyer »),",
            "avec les dates dans le titre et la colonne des jours.",
            "",
            "FRÉQUENCES (Ctrl+F)",
            "Nombre de fréquences par jour et par destination, pour tous les avions,",
            "un type d'avion ou un avion ; survol d'un chiffre = avions et heures.",
            "",
            "AVIONS",
            "Ajouter un avion : nom, base, type (reprend les durées de vol d'un avion",
            "existant) et programme de départ (vide ou copie d'un autre avion).",
            "Cloner : copie le programme affiché (programme type ou semaine) sur",
            "d'autres avions, avec recalcul des heures selon leurs durées de vol.",
            "",
            "CLIC DROIT sur un jour : copier / coller / vider.",
            "Ctrl+Z / Ctrl+Y : annuler / rétablir.   Ctrl+P : imprimer.   Ctrl+E : PDF.",
            "",
            "Les données sont enregistrées automatiquement, avec des copies de sécurité",
            "(Fichier → Ouvrir le dossier des sauvegardes).");
}

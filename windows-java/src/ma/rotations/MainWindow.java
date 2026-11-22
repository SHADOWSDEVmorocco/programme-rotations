package ma.rotations;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.logging.Level;

import static ma.rotations.Core.*;

/** Fenêtre principale. */
public final class MainWindow extends JFrame {
    final Path dataFile;
    Program prog;
    final Deque<String> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    List<Rotation> clipboardDay;
    final JTabbedPane tabs = new JTabbedPane();
    /** Semaine affichée : null = programme type (sans date), sinon le lundi « AAAA-MM-JJ ». */
    String week;
    final JComboBox<String> weekCombo = new JComboBox<>();
    boolean weekUpdating;
    final DefaultListModel<String> checks = new DefaultListModel<>();
    final JLabel statusLabel = new JLabel(" "), syncLabel = new JLabel();
    final javax.swing.Timer saveTimer, statusTimer;
    boolean firstSave = true;
    int lastRev;
    final Path syncCfgPath;
    SyncServer.Config syncCfg;
    final SyncServer sync;

    MainWindow(Path dataFile) {
        super(Main.APP_NAME + " " + Main.VERSION);
        this.dataFile = dataFile;
        setIconImages(Icons.appIcons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { quit(); } });
        saveTimer = new javax.swing.Timer(400, e -> autosave());
        saveTimer.setRepeats(false);
        statusTimer = new javax.swing.Timer(8000, e -> statusLabel.setText(" "));
        statusTimer.setRepeats(false);

        prog = loadInitial();
        List<String> added = addMissingDefaults(prog);
        List<String> semaine = ensureSemaineType(prog);
        List<String> rot3 = ensureErj2Rot3(prog);

        // ---------------------------------------------------------------- mise en page
        JList<String> list = new JList<>(checks);
        list.setCellRenderer((l, v, i, sel, foc) -> {
            boolean ok = v.startsWith("\u0001");
            String txt = (ok ? v.substring(1) : v).replace("&", "&amp;").replace("<", "&lt;");
            JLabel lab = new JLabel("<html><div style='width:225px'>" + txt + "</div></html>", Icons.get(ok ? "ok" : "warning", 16), SwingConstants.LEFT);
            lab.setVerticalTextPosition(SwingConstants.TOP);
            lab.setForeground(ok ? new Color(0x1e8449) : new Color(0xb03a2e));
            lab.setBorder(new EmptyBorder(3, 4, 3, 4));
            lab.setIconTextGap(6);
            return lab;
        });
        JPanel side = new JPanel(new BorderLayout(0, 4));
        JLabel ct = new JLabel("Contrôles");
        ct.setFont(ct.getFont().deriveFont(Font.BOLD, 14f));
        side.add(ct, BorderLayout.NORTH);
        side.add(new JScrollPane(list), BorderLayout.CENTER);
        side.setBorder(new EmptyBorder(8, 4, 8, 8));
        side.setPreferredSize(new Dimension(340, 400));
        side.setMinimumSize(new Dimension(220, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, side);
        split.setResizeWeight(1.0);
        tabs.addChangeListener(e -> updateChecks());

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(3, 8, 3, 8));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(syncLabel, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(buildWeekBar(), BorderLayout.SOUTH);
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(buildMenus());
        setSize(1500, 900);
        setLocationByPlatform(true);
        setExtendedState(MAXIMIZED_BOTH);

        // ---------------------------------------------------------------- synchronisation Wi-Fi
        syncCfgPath = dataFile.getParent().resolve("synchronisation.json");
        syncCfg = SyncServer.Config.load(syncCfgPath);
        sync = new SyncServer(this::onTabletPut, this::updateSyncLabel);
        lastRev = prog.rev;
        publish("PC");
        applySyncConfig(false);
        new javax.swing.Timer(5000, e -> updateSyncLabel()).start();

        rebuildTabs();
        if (!added.isEmpty()) { status("Nouvel avion ajouté : " + String.join(", ", added)); touch("PC"); }
        if (!rot3.isEmpty()) {
            touch("PC");
            SwingUtilities.invokeLater(() -> info("Programme par défaut de « ERJ.145 - AVION 2 » : 3e rotation ajoutée\n("
                    + String.join(", ", rot3) + "), d'après la feuille complétée à la main.\n\nDimanche : l'heure d'arrivée à LAAYOUNE (16:00) est à vérifier\n(5 mn de vol) – voir le panneau Contrôles."));
        }
        if (!semaine.isEmpty()) {
            touch("PC");
            SwingUtilities.invokeLater(() -> info("Programme « Dakhla Air Connect – semaine type » ajouté dans les jours vides :\n\n• "
                    + String.join("\n• ", semaine) + "\n\nLes jours déjà remplis n'ont pas été modifiés.\nPour tout remplacer : Programme → Semaine type → Remplacer."));
        }
    }

    // ================================================================== chargement / sauvegarde
    Program loadInitial() {
        if (Files.exists(dataFile)) {
            try {
                return Storage.loadProgram(dataFile);
            } catch (Exception e) {
                Main.LOG.log(Level.WARNING, "Lecture impossible", e);
                try {
                    List<Path> b = Storage.backups(dataFile.getParent().resolve("sauvegardes"), "programme");
                    for (int i = b.size() - 1; i >= 0; i--) {
                        try {
                            Program p = Storage.loadProgram(b.get(i));
                            Path bf = b.get(i);
                            SwingUtilities.invokeLater(() -> warn("Le fichier principal était illisible (" + e.getMessage() + ").\nRécupération de la sauvegarde :\n" + bf.getFileName()));
                            return p;
                        } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) { }
            }
        }
        return factoryProgram();
    }

    void autosave() {
        try {
            Storage.saveProgram(prog, dataFile, firstSave ? 20 : 0);
            firstSave = false;
            if (System.currentTimeMillis() - lastStatus > 6000) status("Enregistré automatiquement.");
        } catch (Exception e) {
            Main.LOG.log(Level.SEVERE, "Sauvegarde impossible", e);
            error("Impossible d'enregistrer :\n" + e.getMessage());
        }
    }

    void quit() {
        if (tabs.getSelectedComponent() instanceof AircraftPanel) {
            JTable t = ((AircraftPanel) tabs.getSelectedComponent()).table;
            if (t.isEditing()) t.getCellEditor().stopCellEditing();
        }
        saveTimer.stop();
        autosave();
        sync.stop();
        dispose();
        System.exit(0);
    }

    // ================================================================== état
    /** Avion affiché, pour la semaine affichée. */
    Aircraft currentAc() { return view(originalAc(), week); }
    Aircraft originalAc() { return prog.aircraft.get(Math.max(0, Math.min(tabs.getSelectedIndex(), prog.aircraft.size() - 1))); }
    AircraftPanel currentPanel() { return (AircraftPanel) tabs.getSelectedComponent(); }

    void rebuildTabs() {
        int idx = Math.max(0, tabs.getSelectedIndex());
        tabs.removeAll();
        if (week != null && !weeks(prog).contains(week)) week = null;
        for (Aircraft a : prog.aircraft) tabs.addTab(a.title, Icons.get("plane", 16), new AircraftPanel(this, view(a, week)));
        if (tabs.getTabCount() > 0) tabs.setSelectedIndex(Math.min(idx, tabs.getTabCount() - 1));
        updateWeekBar();
        updateChecks();
    }

    void snapshot() {
        undo.push(Json.write(toDict(prog)));
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
    }
    void changed(boolean renameTabs) {
        AircraftPanel p = currentPanel();
        if (p != null) p.refresh();
        if (renameTabs) for (int i = 0; i < prog.aircraft.size(); i++) tabs.setTitleAt(i, prog.aircraft.get(i).title);
        updateChecks();
        touch("PC");
    }
    /** Après chaque modification : nouvelle révision (toujours croissante), envoi à la tablette, enregistrement. */
    void touch(String by) {
        lastRev = Math.max(lastRev, prog.rev) + 1;
        prog.rev = lastRev;
        publish(by);
        saveTimer.restart();
    }
    void publish(String by) { if (sync != null) sync.publish(prog.rev, Json.write(toDict(prog)), by); }

    void restore(String json) { prog = fromDict(Json.parse(json)); rebuildTabs(); touch("PC"); }
    void doUndo() { if (undo.isEmpty()) { status("Rien à annuler."); return; } redo.push(Json.write(toDict(prog))); restore(undo.pop()); }
    void doRedo() { if (redo.isEmpty()) return; undo.push(Json.write(toDict(prog))); restore(redo.pop()); }

    void updateChecks() {
        checks.clear();
        if (prog.aircraft.isEmpty() || tabs.getTabCount() == 0) return;
        List<String> w = check(prog, currentAc());
        if (w.isEmpty()) checks.addElement("\u0001Aucun problème détecté");
        for (String m : w) checks.addElement(m);
    }

    long lastStatus;
    void status(String m) { statusLabel.setText(m); statusTimer.restart(); if (!m.startsWith("Enregistré")) lastStatus = System.currentTimeMillis(); }
    void info(String m) { JOptionPane.showMessageDialog(this, m, Main.APP_NAME, JOptionPane.INFORMATION_MESSAGE); }
    void warn(String m) { JOptionPane.showMessageDialog(this, m, Main.APP_NAME, JOptionPane.WARNING_MESSAGE); }
    void error(String m) { JOptionPane.showMessageDialog(this, m, Main.APP_NAME, JOptionPane.ERROR_MESSAGE); }
    boolean confirm(String m) { return JOptionPane.showConfirmDialog(this, m, Main.APP_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }

    // ================================================================== saisie d'une case
    void applyCell(AircraftPanel panel, int di, int ri, String attr, String kind, String text) {
        Aircraft ac = panel.ac;
        if (kind.equals("esc") || kind.equals("gap")) {
            Integer m;
            try { m = parseMinutes(text); } catch (IllegalArgumentException e) { m = null; }
            if (m == null || m < 0 || m > 1440) { status("« " + text + " » : tapez un nombre de minutes (ex. 60)"); panel.refresh(); return; }
            RotationCalc c = computeDay(ac.days.get(di)).rotations.get(ri);
            if (m.equals(kind.equals("esc") ? c.escale : c.ecart)) return;
            snapshot();
            if (kind.equals("esc")) setEscale(prog, ac, di, ri, m); else setGap(prog, ac, di, ri, m);
            changed(false);
            return;
        }
        if (kind.equals("time")) {
            try { text = normalizeTime(text); }
            catch (IllegalArgumentException e) { status("« " + text + " » n'est pas une heure valide (format HH:MM, ex. 07:30)"); }
        } else text = up(text);
        if (ac.days.get(di).get(ri).get(attr).equals(text)) return;
        snapshot();
        List<String> missing = applyChange(prog, ac, di, ri, attr, text);
        if (askMissingDurations(ac, di, ri)) missing = applyChange(prog, ac, di, ri, "escale", ac.days.get(di).get(ri).escale);
        changed(false);
        if (!missing.isEmpty()) status("À compléter : " + String.join(" ; ", missing));
    }

    /** Durée de vol d'un trajet nouveau (ex. vers TENERIFE) : demandée une seule fois. */
    boolean askMissingDurations(Aircraft ac, int di, int ri) {
        boolean asked = false;
        for (String[] leg : missingDurations(prog, ac, di, ri)) {
            Integer rev = getDuration(prog, ac, leg[1] + ">" + leg[0]);
            String def = rev != null ? fmtDuration(rev) : "01:00";
            while (true) {
                Object v = JOptionPane.showInputDialog(this, "Durée de vol " + leg[0] + " → " + leg[1] + " pour « " + ac.title
                        + " » ?\n(format HH:MM – enregistrée pour la suite)", "Nouveau trajet", JOptionPane.QUESTION_MESSAGE, Icons.get("clock", 24), null, def);
                if (v == null) return asked;
                Integer m = safeTime(v.toString());
                if (m != null && m > 0) { ac.durations.put(leg[0] + ">" + leg[1], m); asked = true; break; }
                warn("Format attendu : HH:MM (ex. 01:10)");
            }
        }
        return asked;
    }

    // ================================================================== semaines (programme type / semaines datées)
    JPanel buildWeekBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBorder(new EmptyBorder(0, 8, 2, 8));
        JLabel l = new JLabel("Programme :");
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        JButton prev = new JButton("◀"), next = new JButton("▶");
        prev.setToolTipText("Semaine précédente");
        next.setToolTipText("Semaine suivante");
        prev.addActionListener(e -> stepWeek(-1));
        next.addActionListener(e -> stepWeek(1));
        weekCombo.setPrototypeDisplayValue("prototype");
        weekCombo.setMaximumRowCount(20);
        weekCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean f) {
                String w = v == null || "".equals(v) ? null : String.valueOf(v);
                if (w != null && !w.matches("\\d{4}-\\d{2}-\\d{2}")) return super.getListCellRendererComponent(list, "Semaine du 00/00/0000 au 00/00/0000   (historique)", i, sel, f);
                String t = weekLabel(w);
                if (w != null && w.equals(thisWeek())) t += "   (cette semaine)";
                else if (w != null && w.compareTo(thisWeek()) < 0) t += "   (historique)";
                JLabel lab = (JLabel) super.getListCellRendererComponent(list, t, i, sel, f);
                lab.setIcon(Icons.get(w == null ? "auto" : "clock", 16));
                if (w == null) lab.setFont(lab.getFont().deriveFont(Font.BOLD));
                return lab;
            }
        });
        weekCombo.addActionListener(e -> {
            if (weekUpdating) return;
            String w = (String) weekCombo.getSelectedItem();
            if (w == null) return;
            if (w.isEmpty()) w = null;
            if (!Objects.equals(w, week)) selectWeek(w);
        });
        bar.add(l); bar.add(prev); bar.add(weekCombo); bar.add(next);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(tool("Nouvelle semaine…", "clock", "Créer une semaine datée (copie du programme type, d'une autre semaine, ou vide)", () -> newWeekDialog(null, week)));
        bar.add(tool("Historique…", "folder", "Toutes les semaines enregistrées : ouvrir, exporter, supprimer", this::historyDialog));
        return bar;
    }
    void updateWeekBar() {
        weekUpdating = true;
        try {
            weekCombo.removeAllItems();
            weekCombo.addItem("");                        // « » = programme type
            List<String> ws = new ArrayList<>(weeks(prog));
            Collections.reverse(ws);                       // plus récentes en premier
            for (String w : ws) weekCombo.addItem(w);
            weekCombo.setSelectedItem(week == null ? "" : week);
        } finally {
            weekUpdating = false;
        }
        setTitle(Main.APP_NAME + " " + Main.VERSION + "  –  " + weekLabel(week));
    }
    void selectWeek(String w) {
        AircraftPanel p = currentPanel();
        if (p != null && p.table.isEditing()) p.table.getCellEditor().stopCellEditing();
        week = w;
        rebuildTabs();
        status(weekLabel(w));
    }
    void stepWeek(int dir) {
        String target = week == null ? thisWeek() : shiftWeek(week, dir);
        if (weeks(prog).contains(target)) { selectWeek(target); return; }
        newWeekDialog(target, week);
    }
    /** Création d'une semaine datée. */
    void newWeekDialog(String proposed, String preSource) {
        TreeSet<String> ws = weeks(prog);
        if (proposed == null) proposed = ws.isEmpty() || ws.last().compareTo(thisWeek()) < 0 ? thisWeek() : shiftWeek(ws.last(), 1);
        JSpinner date = new JSpinner(new SpinnerDateModel(java.sql.Date.valueOf(java.time.LocalDate.parse(proposed)), null, null, java.util.Calendar.DAY_OF_MONTH));
        date.setEditor(new JSpinner.DateEditor(date, "dd/MM/yyyy"));
        List<String> srcLabels = new ArrayList<>(List.of("Programme type (sans date)"));
        List<String> srcKeys = new ArrayList<>(Collections.singletonList((String) null));
        List<String> rev = new ArrayList<>(ws); Collections.reverse(rev);
        for (String w : rev) { srcLabels.add("Copie de la " + weekShort(w)); srcKeys.add(w); }
        srcLabels.add("Vide"); srcKeys.add("vide");
        JComboBox<String> src = new JComboBox<>(srcLabels.toArray(new String[0]));
        src.setSelectedIndex(Math.max(0, srcKeys.indexOf(preSource)));
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 6));
        p.add(new JLabel("Semaine commençant le (le lundi est retenu) :"));
        p.add(date);
        p.add(new JLabel("Remplir à partir de :"));
        p.add(src);
        if (JOptionPane.showConfirmDialog(this, p, "Nouvelle semaine datée", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, Icons.get("clock", 24)) != JOptionPane.OK_OPTION) return;
        java.time.LocalDate d = new java.sql.Date(((Date) date.getValue()).getTime()).toLocalDate();
        String w = monday(d);
        if (ws.contains(w) && !confirm(weekLabel(w) + " existe déjà.\nLa remplacer ? (Ctrl+Z pour annuler)")) { selectWeek(w); return; }
        snapshot();
        createWeek(prog, w, srcKeys.get(src.getSelectedIndex()));
        week = w;
        rebuildTabs();
        touch("PC");
        status(weekLabel(w) + " créée.");
    }
    /** Historique : toutes les semaines enregistrées. */
    void historyDialog() {
        List<String> ws = new ArrayList<>(weeks(prog));
        Collections.reverse(ws);
        List<String> rows = new ArrayList<>(Collections.singletonList((String) null));
        rows.addAll(ws);
        javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(new String[]{"Programme", "Total", "Bloc de vol", "Vols"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String w : rows) {
            int tot = 0, bloc = 0, vols = 0;
            for (Aircraft a : views(prog, w)) {
                tot += weekTotal(a); bloc += weekBloc(a);
                for (List<Rotation> d : a.days) for (Rotation r : d) if (r.hasFlight()) vols += 2;
            }
            String lab = weekLabel(w) + (w != null && w.equals(thisWeek()) ? "  (cette semaine)" : "");
            m.addRow(new Object[]{lab, fmtDuration(tot), fmtDuration(bloc), vols});
        }
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.getColumnModel().getColumn(0).setPreferredWidth(330);
        int cur = rows.indexOf(week);
        if (cur >= 0) t.setRowSelectionInterval(cur, cur);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(640, 360));
        JDialog d = new JDialog(this, "Historique des programmes", true);
        d.setIconImage(Icons.get("folder", 24).getImage());
        Runnable open = () -> { int r = t.getSelectedRow(); if (r >= 0) { d.dispose(); selectWeek(rows.get(r)); } };
        t.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) open.run(); } });
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        b.add(Dialogs.btn("Ouvrir", "open", open));
        b.add(Dialogs.btn("Exporter en PDF", "pdf", () -> { int r = t.getSelectedRow(); if (r >= 0) exportWeekPdf(rows.get(r)); }));
        b.add(Dialogs.btn("Dupliquer vers…", "copy", () -> { int r = t.getSelectedRow(); if (r >= 0) { d.dispose(); newWeekDialog(null, rows.get(r)); } }));
        b.add(Dialogs.btn("Supprimer", "clear", () -> {
            int r = t.getSelectedRow();
            if (r <= 0) { if (r == 0) warn("Le programme type ne peut pas être supprimé."); return; }
            String w = rows.get(r);
            if (!confirm("Supprimer " + weekLabel(w) + " ?\n(Ctrl+Z pour annuler)")) return;
            snapshot(); deleteWeek(prog, w); if (w.equals(week)) week = null; rebuildTabs(); touch("PC");
            d.dispose(); historyDialog();
        }));
        b.add(Dialogs.btn("Fermer", null, d::dispose));
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.add(new JLabel("<html>Le <b>programme type</b> (sans date) et toutes les <b>semaines datées</b> enregistrées. Double-clic pour ouvrir.</html>"), BorderLayout.NORTH);
        root.add(sp, BorderLayout.CENTER);
        root.add(b, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }
    void exportWeekPdf(String w) {
        JFileChooser fc = new JFileChooser();
        String base = w == null ? "programme_type" : "programme_semaine_" + w;
        fc.setSelectedFile(new File(System.getProperty("user.home"), base + ".pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path f = fc.getSelectedFile().toPath();
            if (!f.toString().toLowerCase().endsWith(".pdf")) f = Paths.get(f + ".pdf");
            Pdf.write(f, views(prog, w), "Programme des rotations – " + weekShort(w));
            openFile(f);
        } catch (Exception e) { error("Export impossible :\n" + e.getMessage()); }
    }

    // ================================================================== barre d'outils et menus
    JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(new EmptyBorder(4, 6, 4, 6));
        tb.add(tool("Compléter automatiquement", "auto", "Compléter les cases vides de la semaine (F5)", this::doAutocomplete));
        tb.add(tool("Imprimer / Aperçu", "print", "Imprimer le tableau affiché (Ctrl+P)", () -> doPrint(false)));
        tb.add(tool("Exporter PDF (tous)", "pdf", "PDF de tous les avions (Ctrl+E)", () -> doExportPdf(true)));
        tb.add(tool("Envoyer", "send", "Envoyer par WhatsApp ou e-mail (Ctrl+M)", () -> Dialogs.send(this)));
        tb.addSeparator();
        tb.add(tool("Annuler", "undo", "Annuler (Ctrl+Z)", this::doUndo));
        tb.add(tool("Rétablir", "redo", "Rétablir (Ctrl+Y)", this::doRedo));
        tb.addSeparator();
        tb.add(tool("Fréquences", "cities", "Compteur : nombre de fréquences par jour et par destination (Ctrl+F)", () -> Dialogs.frequencies(this)));
        tb.add(tool("Ajouter un avion", "plane", "Ajouter un avion (nom, base, type, programme de départ)", () -> Dialogs.addAircraft(this)));
        tb.add(tool("Cloner", "copy", "Copier le programme affiché sur d'autres avions", () -> Dialogs.cloneProgram(this)));
        tb.addSeparator();
        tb.add(tool("Durées de vol", "clock", "Durées de vol de l'avion affiché", () -> Dialogs.durations(this)));
        tb.add(tool("Villes", "cities", "Villes proposées dans les listes", () -> Dialogs.cities(this)));
        tb.add(tool("Paramètres", "settings", "Paramètres", () -> Dialogs.settings(this)));
        tb.add(tool("Tablette / Wi-Fi", "wifi", "Synchronisation avec la tablette", () -> Dialogs.sync(this)));
        return tb;
    }
    static JButton tool(String text, String icon, String tip, Runnable r) {
        JButton b = new JButton(text, Icons.get(icon, 20));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }
    JMenuItem mi(JMenu menu, String text, String icon, String key, Runnable r) {
        JMenuItem i = new JMenuItem(text, icon == null ? null : Icons.get(icon, 16));
        if (key != null) i.setAccelerator(KeyStroke.getKeyStroke(key));
        i.addActionListener(e -> r.run());
        menu.add(i);
        return i;
    }
    JMenuBar buildMenus() {
        JMenuBar mb = new JMenuBar();
        JMenu f = new JMenu("Fichier"), e = new JMenu("Édition"), p = new JMenu("Programme"), s = new JMenu("Synchronisation"), h = new JMenu("Aide");
        mi(f, "Imprimer / Aperçu", "print", "ctrl P", () -> doPrint(false));
        mi(f, "Imprimer tous les tableaux", "print", null, () -> doPrint(true));
        mi(f, "Exporter PDF (tous)", "pdf", "ctrl E", () -> doExportPdf(true));
        mi(f, "Exporter le tableau affiché en PDF…", "pdf", null, () -> doExportPdf(false));
        mi(f, "Envoyer (WhatsApp / e-mail)…", "send", "ctrl M", () -> Dialogs.send(this));
        f.addSeparator();
        mi(f, "Nouvelle semaine datée…", "clock", "ctrl N", () -> newWeekDialog(null, week));
        mi(f, "Historique des programmes…", "folder", "ctrl H", this::historyDialog);
        f.addSeparator();
        mi(f, "Ouvrir un programme…", "open", "ctrl O", this::doOpen);
        mi(f, "Enregistrer une copie sous…", "save", "ctrl S", this::doSaveAs);
        mi(f, "Ouvrir le dossier des sauvegardes", "folder", null, () -> {
            try { Desktop.getDesktop().open(dataFile.getParent().toFile()); } catch (Exception ex) { error(ex.getMessage()); }
        });
        mi(f, "Charger les feuilles papier dans les avions vides", "paste", null, this::loadPaper);
        mi(f, "Revenir au programme par défaut…", "undo", null, () -> {
            if (confirm("Remplacer le programme type des avions par le programme par défaut ?\n"
                    + "(les semaines datées sont conservées – Ctrl+Z pour annuler)")) {
                snapshot();
                Program def = factoryProgram();
                for (Aircraft d : def.aircraft) {
                    Aircraft a = prog.aircraft.stream().filter(x -> up(x.title).equals(up(d.title))).findFirst().orElse(null);
                    if (a == null) prog.aircraft.add(d);
                    else { for (int di = 0; di < DAYS.length; di++) a.days.set(di, d.days.get(di)); a.durations.putAll(d.durations); }
                }
                rebuildTabs(); touch("PC");
            }
        });
        f.addSeparator();
        mi(f, "Quitter", "exit", "ctrl Q", this::quit);
        mi(e, "Annuler", "undo", "ctrl Z", this::doUndo);
        mi(e, "Rétablir", "redo", "ctrl Y", this::doRedo);
        mi(p, "Compléter automatiquement", "auto", "F5", this::doAutocomplete);
        mi(p, "Fréquences par destination…", "cities", "ctrl F", () -> Dialogs.frequencies(this));
        mi(p, "Durées de vol", "clock", null, () -> Dialogs.durations(this));
        mi(p, "Villes", "cities", null, () -> Dialogs.cities(this));
        mi(p, "Paramètres", "settings", null, () -> Dialogs.settings(this));
        mi(p, "Apprendre les durées", "learn", null, () -> {
            snapshot(); int n = learnDurations(prog, currentAc()); changed(false);
            info(currentAc().title + " : " + n + " trajets appris / mis à jour à partir de son programme.");
        });
        JMenu st = new JMenu("Semaine type Dakhla Air Connect");
        st.setIcon(Icons.get("auto", 16));
        mi(st, "Compléter les jours vides", "auto", null, () -> semaineType(false));
        mi(st, "Remplacer les 4 avions…", "clear", null, () -> semaineType(true));
        p.add(st);
        p.addSeparator();
        mi(p, "Ajouter un avion…", "plane", null, () -> Dialogs.addAircraft(this));
        mi(p, "Cloner le programme sur d'autres avions…", "copy", null, () -> Dialogs.cloneProgram(this));
        mi(p, "Supprimer l'avion affiché…", "clear", null, () -> {
            if (prog.aircraft.size() > 1 && confirm("Supprimer « " + currentAc().title + " » ? (Ctrl+Z pour annuler)")) {
                snapshot(); prog.aircraft.remove(originalAc()); rebuildTabs(); touch("PC");
            }
        });
        mi(p, "Vider le programme de l'avion affiché…", "clear", null, () -> {
            if (confirm("Vider « " + currentAc().title + " » (" + weekShort(week) + ") ?")) {
                snapshot(); Aircraft a = currentAc(); for (int di = 0; di < DAYS.length; di++) a.days.set(di, emptyDay()); rebuildTabs(); touch("PC");
            }
        });
        mi(s, "Tablette / Wi-Fi", "wifi", null, () -> Dialogs.sync(this));
        mi(h, "Mode d'emploi", "help", "F1", () -> Dialogs.help(this));
        mi(h, "À propos", "plane", null, () -> info(Main.APP_NAME + " " + Main.VERSION + " (Java " + System.getProperty("java.version") + ")\n\nDonnées : " + dataFile));
        mb.add(f); mb.add(e); mb.add(p); mb.add(s); mb.add(h);
        return mb;
    }

    // ================================================================== commandes
    void doAutocomplete() {
        snapshot();
        List<String> m = autocomplete(prog, currentAc());
        changed(false);
        if (!m.isEmpty()) info("Complété. Reste à saisir à la main :\n\n• " + String.join("\n• ", m) + "\n\nAjoutez les durées manquantes dans « Durées de vol » (propres à chaque avion).");
        else status("Programme complété.");
    }
    void semaineType(boolean all) {
        if (all && !confirm("Remplacer les 7 jours des 4 avions par la semaine type ?\n(Ctrl+Z pour annuler)")) return;
        snapshot();
        Program pv = new Program();                       // la semaine type s'applique à la semaine affichée
        pv.aircraft = views(prog, week);
        pv.settings = prog.settings;
        List<String> r = applySemaineType(pv, all);
        rebuildTabs();
        touch("PC");
        info(r.isEmpty() ? "Aucun jour vide à compléter." : "Jours remplis :\n\n• " + String.join("\n• ", r));
    }
    void loadPaper() {
        snapshot();
        List<String> done = fillEmptyFromDefaults(prog);
        if (done.isEmpty()) { undo.pop(); info("Aucun avion vide à remplir."); return; }
        rebuildTabs(); touch("PC");
        info("Données chargées pour : " + String.join(", ", done));
    }
    List<Aircraft> scope(boolean all) { return all ? views(prog, week) : List.of(currentAc()); }

    void doPrint(boolean all) {
        try {
            Path f = Paths.get(System.getProperty("java.io.tmpdir"), "programme_rotations_" + System.currentTimeMillis() + ".pdf");
            Pdf.write(f, scope(all), "Programme des rotations – " + weekShort(week));
            openFile(f);
            status("Le PDF s'ouvre : imprimez-le depuis la visionneuse (Ctrl+P).");
        } catch (Exception e) { error("Impression impossible :\n" + e.getMessage()); }
    }
    void doExportPdf(boolean all) {
        JFileChooser fc = new JFileChooser();
        String suffix = week == null ? "" : "_semaine_" + week;
        fc.setSelectedFile(new File(System.getProperty("user.home"), (all ? "programme_rotations" : currentAc().title.replaceAll("[^A-Za-z0-9.]+", "_")) + suffix + ".pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path f = fc.getSelectedFile().toPath();
            if (!f.toString().toLowerCase().endsWith(".pdf")) f = Paths.get(f + ".pdf");
            Pdf.write(f, scope(all), "Programme des rotations");
            openFile(f);
        } catch (Exception e) { error("Export impossible :\n" + e.getMessage()); }
    }
    static void openFile(Path f) {
        try {
            if (Sharing.WINDOWS) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", f.toAbsolutePath().toString()).start();
            else Desktop.getDesktop().open(f.toFile());
        } catch (Exception e) { Main.LOG.log(Level.WARNING, "ouverture", e); }
    }
    void doOpen() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Programme (*.json)", "json"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Program p = Storage.loadProgram(fc.getSelectedFile().toPath());
            snapshot(); prog = p; addMissingDefaults(prog); rebuildTabs(); touch("PC");
        } catch (Exception e) { error("Fichier illisible :\n" + e.getMessage()); }
    }
    void doSaveAs() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(System.getProperty("user.home"), "programme_rotations.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try { Storage.saveProgram(prog, fc.getSelectedFile().toPath(), 0); status("Copie enregistrée : " + fc.getSelectedFile()); }
        catch (Exception e) { error(e.getMessage()); }
    }

    // ================================================================== synchronisation tablette
    void applySyncConfig(boolean showError) {
        sync.stop();
        if (syncCfg.enabled && !sync.start(syncCfg.port, syncCfg.code))
            SwingUtilities.invokeLater(() -> warn("Synchronisation Wi-Fi impossible : " + sync.error + "\nChangez le port dans Synchronisation → Tablette / Wi-Fi."));
        updateSyncLabel();
    }
    /** Envoi de la tablette (exécuté dans le fil de l'interface). */
    void onTabletPut(SyncServer.Job job) {
        try {
            if (!job.force && job.baseRev != prog.rev) {
                Object[] s = sync.snapshot();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rev", s[0]); m.put("json", s[1]); m.put("modifiePar", s[2]);
                job.status = 409; job.payload = m;
                return;
            }
            Program p = fromDict(Json.parse(job.json));
            AircraftPanel cp = currentPanel();
            if (cp != null && cp.table.isEditing()) cp.table.getCellEditor().cancelCellEditing();
            snapshot();
            prog = p;
            rebuildTabs();
            touch(job.appareil);
            job.status = 200;
            job.payload = Map.of("rev", prog.rev);
            status("Programme mis à jour depuis « " + job.appareil + " » (Ctrl+Z pour annuler)");
        } catch (Exception e) {
            Main.LOG.log(Level.WARNING, "envoi tablette invalide", e);
            job.status = 400;
            job.payload = Map.of("erreur", "programme invalide : " + e.getMessage());
        }
    }
    void updateSyncLabel() {
        if (!sync.running()) { syncLabel.setText(syncCfg.enabled ? "Wi-Fi : erreur" : "Wi-Fi : désactivé"); syncLabel.setIcon(Icons.get("wifi", 16)); return; }
        List<String> recent = new ArrayList<>();
        long now = System.currentTimeMillis();
        sync.devices.forEach((d, t) -> { if (now - t < 15000) recent.add(d); });
        syncLabel.setIcon(Icons.get("wifi", 16));
        syncLabel.setText("Wi-Fi : " + SyncServer.localIps().get(0) + ":" + sync.port + " · code " + sync.code + " · "
                + (recent.isEmpty() ? "aucun appareil connecté" : String.join(", ", recent)));
    }
}

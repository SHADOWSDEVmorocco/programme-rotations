package ma.rotations;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

import static ma.rotations.Core.*;

/** Onglet d'un avion : en-tête + tableau de la semaine (7 jours × 3 rotations). */
public final class AircraftPanel extends JPanel {
    static final Color C_BLUE = new Color(0x29a8e0), C_GREY = new Color(0xc4c7c5), C_YELLOW = new Color(0xf2c21b),
            C_BAD = new Color(0xf8c9c4), C_DAY = new Color(0xeef3f7), C_INK = new Color(0x1c2733);

    /** [titre, attribut, type] – même ordre que les versions précédentes. */
    static final String[][] COLS = {
            {"Jour", "", "day"}, {"Rot.", "", "num"},
            {"Départ", "depart", "station"}, {"Heure", "h_depart", "time"},
            {"Escale", "escale", "station"}, {"Arrivée", "h_arrivee_escale", "time"},
            {"Escale (mn)", "escale_mn", "esc"}, {"Redépart", "h_redepart", "time"},
            {"Arrivée", "arrivee", "station"}, {"Heure", "h_arrivee", "time"},
            {"Remarque", "remarque", "text"},
            {"Durée rot.", "duree", "calc"}, {"Esc. base", "ecart", "gap"}, {"Total jour", "total", "calc"}};

    final MainWindow win;
    Aircraft ac;
    final JTable table;
    final Model model = new Model();
    final JTextField titleField = new JTextField(22), baseField = new JTextField(10);
    final JSpinner escMin = spinner(), baseMin = spinner();
    final JLabel weekLabel = new JLabel();
    boolean updating;

    AircraftPanel(MainWindow win, Aircraft ac) {
        super(new BorderLayout(0, 6));
        this.win = win;
        this.ac = ac;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.add(new JLabel("Titre :")); head.add(titleField);
        head.add(new JLabel("  Base :")); head.add(baseField);
        head.add(new JLabel("    Escale mini :")); head.add(escMin);
        head.add(new JLabel("  Esc. base mini :")); head.add(baseMin);
        weekLabel.setFont(weekLabel.getFont().deriveFont(Font.BOLD, 14f));
        weekLabel.setBorder(new EmptyBorder(0, 24, 0, 0));
        head.add(weekLabel);
        add(head, BorderLayout.NORTH);
        titleField.addActionListener(e -> titleChanged());
        titleField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { titleChanged(); } });
        baseField.addActionListener(e -> baseChanged());
        baseField.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { baseChanged(); } });
        escMin.addChangeListener(e -> minChanged(true));
        baseMin.addChangeListener(e -> minChanged(false));
        escMin.setToolTipText("Alerte si l'escale est plus courte (modifiable pour cet avion)");
        baseMin.setToolTipText("Alerte si l'escale à la base est plus courte (modifiable pour cet avion)");

        table = new JTable(model) {
            @Override public String getToolTipText(MouseEvent e) {
                int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
                if (r < 0 || c < 0) return null;
                String k = COLS[c][2];
                if (k.equals("esc") || k.equals("gap")) return isCellEditable(r, c) ? "Modifiable : tapez les minutes (ex. 60) – les heures suivantes s'ajustent" : null;
                if (c == 4) {
                    List<String[]> cf = conflicts(win.prog, ac, r / 3, r % 3);
                    if (!cf.isEmpty()) return "Même destination au même moment que " + cf.get(0)[0] + " (" + cf.get(0)[2] + "–" + cf.get(0)[3] + ")";
                }
                Object v = getValueAt(r, c);
                return v != null && v.toString().length() > 12 ? v.toString() : null;
            }
        };
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCellSelectionEnabled(true);
        table.setSurrendersFocusOnKeystroke(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        TableCellRenderer hr = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((t, v, s, f, r, c) -> {
            Component comp = hr.getTableCellRendererComponent(t, v, s, f, r, c);
            Color bg = c == 6 ? C_YELLOW : c == 11 ? C_GREY : (c == 12 || c == 13) ? C_BLUE : null;
            if (comp instanceof JComponent) ((JComponent) comp).setOpaque(true);
            comp.setBackground(bg != null ? bg : UIManager.getColor("TableHeader.background"));
            comp.setForeground(c == 12 || c == 13 ? Color.WHITE : UIManager.getColor("TableHeader.foreground"));
            if (comp instanceof JLabel) ((JLabel) comp).setHorizontalAlignment(SwingConstants.CENTER);
            return comp;
        });
        int[] widths = {92, 40, 110, 64, 120, 64, 84, 72, 110, 64, 140, 80, 78, 80};
        for (int c = 0; c < COLS.length; c++) {
            TableColumn col = table.getColumnModel().getColumn(c);
            col.setPreferredWidth(widths[c]);
            String kind = COLS[c][2];
            if (!kind.equals("day") && !kind.equals("num") && !kind.equals("calc")) col.setCellEditor(new SmartEditor(COLS[c][1], kind));
        }
        table.getColumnModel().getColumn(0).setMinWidth(92);
        table.getColumnModel().getColumn(1).setMaxWidth(44);
        // Entrée : case suivante (à droite)
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectNextColumnCell");
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        refresh();
    }

    static JSpinner spinner() {
        JSpinner s = new JSpinner(new SpinnerNumberModel(45, 0, 600, 5));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(3);
        return s;
    }

    // ------------------------------------------------------------------ affichage
    void refresh() {
        updating = true;
        try {
            if (!titleField.hasFocus()) titleField.setText(ac.title);
            if (!baseField.hasFocus()) baseField.setText(ac.base);
            escMin.setValue(ac.escaleMin);
            baseMin.setValue(ac.baseMin);
            weekLabel.setText("Total semaine : " + fmtDuration(weekTotal(ac)) + "   ·   Bloc de vol : " + fmtDuration(weekBloc(ac)));
            if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            model.fireTableRowsUpdated(0, model.getRowCount() - 1);
        } finally {
            updating = false;
        }
    }

    final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return DAYS.length * ROTATIONS_PER_DAY; }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c][0]; }

        @Override public Object getValueAt(int row, int col) {
            int di = row / 3, ri = row % 3;
            Rotation r = ac.days.get(di).get(ri);
            String kind = COLS[col][2];
            switch (kind) {
                case "day":
                    if (ac.week == null) return ri == 1 ? DAYS[di] : "";
                    return ri == 1 ? DAYS[di] : ri == 2 ? dateOf(ac.week, di).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
                case "num": return String.valueOf(ri + 1);
                case "station": case "time": case "text": return r.get(COLS[col][1]);
                default:
                    DayCalc dc = computeDay(ac.days.get(di));
                    RotationCalc c = dc.rotations.get(ri);
                    if (kind.equals("esc")) return c.escale != null ? c.escale + " mn" : "";
                    if (kind.equals("gap")) return c.ecart != null ? c.ecart + " mn" : "";
                    if (col == 11) return c.duree != null ? fmtDuration(c.duree) : "";
                    return ri == 1 && dc.total != 0 ? fmtDuration(dc.total) : "";
            }
        }
        @Override public boolean isCellEditable(int row, int col) {
            String kind = COLS[col][2];
            if (kind.equals("station") || kind.equals("time") || kind.equals("text")) return true;
            if (kind.equals("esc") || kind.equals("gap")) return !String.valueOf(getValueAt(row, col)).isEmpty();
            return false;
        }
        @Override public void setValueAt(Object v, int row, int col) {
            if (updating) return;
            win.applyCell(AircraftPanel.this, row / 3, row % 3, COLS[col][1], COLS[col][2], v == null ? "" : v.toString());
        }
    }

    final class Renderer extends DefaultTableCellRenderer {
        final Border groupTop = new MatteBorder(2, 0, 0, 0, C_INK);
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, v, false, focus, row, col);
            l.setHorizontalAlignment(CENTER);
            String kind = COLS[col][2], text = v == null ? "" : v.toString();
            int di = row / 3, ri = row % 3;
            boolean bold = kind.equals("day") || kind.equals("calc") || kind.equals("esc") || kind.equals("gap");
            l.setFont(t.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN));
            Color bg = Color.WHITE, fg = C_INK;
            DayCalc dc = null;
            if (kind.equals("day")) bg = C_DAY;
            else if (kind.equals("num")) fg = Color.GRAY;
            else if (!text.isEmpty() && (kind.equals("esc") || kind.equals("calc") || kind.equals("gap"))) {
                bg = col == 6 ? C_YELLOW : col == 11 ? C_GREY : C_BLUE;
            }
            if (col == 13) bg = dayHasTotal(di) ? C_BLUE : Color.WHITE;
            if (kind.equals("time") && !text.isEmpty() && safeTime(text) == null) bg = C_BAD;
            if (col == 4 && !text.isEmpty() && !conflicts(win.prog, ac, di, ri).isEmpty()) bg = C_BAD;   // même destination, même heure
            if (kind.equals("esc") || kind.equals("gap") || col == 13) {
                dc = computeDay(ac.days.get(di));
                RotationCalc c = dc.rotations.get(ri);
                if (kind.equals("esc") && c.escale != null && c.escale < ac.escaleMin) bg = C_BAD;
                if (kind.equals("gap") && c.ecart != null && c.ecart < ac.baseMin) bg = C_BAD;
                Integer mx = safeTime(win.prog.settings.maxJour);
                if (col == 13 && mx != null && dc.total > mx) bg = C_BAD;
            }
            if (sel || (t.getSelectedRow() == row && t.getSelectedColumn() == col)) {
                bg = blend(bg, new Color(0x29a8e0), 0.25f);
            }
            l.setBackground(bg);
            l.setForeground(fg);
            Border pad = new EmptyBorder(0, 3, 0, 3);
            boolean top = ri == 0;
            if (col == 0 || col == 13) top = ri == 0;     // ces colonnes forment un bloc par jour
            l.setBorder(top ? new CompoundBorder(groupTop, pad) : pad);
            return l;
        }
        boolean dayHasTotal(int di) { return computeDay(ac.days.get(di)).total != 0; }
    }
    static Color blend(Color a, Color b, float f) {
        return new Color((int) (a.getRed() * (1 - f) + b.getRed() * f), (int) (a.getGreen() * (1 - f) + b.getGreen() * f), (int) (a.getBlue() * (1 - f) + b.getBlue() * f));
    }

    // ------------------------------------------------------------------ liste déroulante de propositions
    final class SmartEditor extends DefaultCellEditor {
        final String attr, kind;
        final JComboBox<String> combo;
        final JTextField field;
        List<String> items = new ArrayList<>();
        boolean filtering;

        @SuppressWarnings("unchecked")
        SmartEditor(String attr, String kind) {
            super(new JComboBox<String>());
            this.attr = attr;
            this.kind = kind;
            combo = (JComboBox<String>) getComponent();
            combo.setEditable(true);
            combo.setMaximumRowCount(14);
            combo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
            field = (JTextField) combo.getEditor().getEditorComponent();
            field.setHorizontalAlignment(JTextField.CENTER);
            if (kind.equals("station") || kind.equals("text")) {
                ((AbstractDocument) field.getDocument()).setDocumentFilter(new DocumentFilter() {
                    @Override public void insertString(FilterBypass fb, int o, String s, AttributeSet a) throws BadLocationException { super.insertString(fb, o, s == null ? null : s.toUpperCase(), a); }
                    @Override public void replace(FilterBypass fb, int o, int l, String s, AttributeSet a) throws BadLocationException { super.replace(fb, o, l, s == null ? null : s.toUpperCase(), a); }
                });
            }
            field.addKeyListener(new KeyAdapter() {
                @Override public void keyReleased(KeyEvent e) {
                    int k = e.getKeyCode();
                    if (k == KeyEvent.VK_UP || k == KeyEvent.VK_DOWN || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_ESCAPE
                            || k == KeyEvent.VK_TAB || k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT || e.isActionKey()) return;
                    filter();
                }
            });
            combo.addPopupMenuListener(new PopupMenuListener() {
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) { }
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
                public void popupMenuCanceled(PopupMenuEvent e) { }
            });
            setClickCountToStart(1);
        }

        void filter() {
            String raw = field.getText(), t = raw.trim().toUpperCase();
            int caret = field.getCaretPosition();
            List<String> l = new ArrayList<>();
            for (String x : items) if (x.toUpperCase().startsWith(t)) l.add(x);
            for (String x : items) if (!l.contains(x) && x.toUpperCase().contains(t)) l.add(x);
            filtering = true;
            try {
                combo.setModel(new DefaultComboBoxModel<>(l.toArray(new String[0])));
                combo.setSelectedIndex(-1);
                field.setText(raw);
                field.setCaretPosition(Math.min(caret, raw.length()));
                if (!l.isEmpty() && combo.isShowing()) { combo.hidePopup(); combo.showPopup(); }
                else combo.hidePopup();
            } finally {
                filtering = false;
            }
        }

        @Override public Component getTableCellEditorComponent(JTable t, Object value, boolean sel, int row, int col) {
            items = new ArrayList<>();
            try { items = suggest(win.prog, ac, row / 3, row % 3, attr); } catch (Exception e) { Main.LOG.warning("propositions : " + e); }
            filtering = true;
            try {
                combo.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
                String v = value == null ? "" : value.toString();
                if (v.isEmpty() && !items.isEmpty()) combo.setSelectedIndex(0);
                else { combo.setSelectedIndex(-1); combo.setSelectedItem(v); }
            } finally {
                filtering = false;
            }
            Component c = super.getTableCellEditorComponent(t, value, sel, row, col);
            String v = value == null ? "" : value.toString();
            if (v.isEmpty() && !items.isEmpty()) field.setText(items.get(0)); else field.setText(v);
            SwingUtilities.invokeLater(() -> {
                field.requestFocusInWindow();
                field.selectAll();
                if ((kind.equals("station") || kind.equals("text") || kind.equals("esc") || kind.equals("gap")) && !items.isEmpty() && combo.isShowing())
                    combo.showPopup();
            });
            return c;
        }
        @Override public Object getCellEditorValue() { return field.getText().trim(); }
        @Override public boolean stopCellEditing() { return !filtering && super.stopCellEditing(); }
    }

    // ------------------------------------------------------------------ saisies de l'en-tête
    void titleChanged() {
        if (updating) return;
        String t = titleField.getText().trim();
        if (!t.isEmpty() && !t.equals(ac.title)) { win.snapshot(); ac.title = t; original(ac).title = t; win.changed(true); }
    }
    void baseChanged() {
        if (updating) return;
        String b = up(baseField.getText());
        if (!b.equals(ac.base)) { win.snapshot(); ac.base = b; original(ac).base = b; win.changed(false); }
    }
    void minChanged(boolean escale) {
        if (updating) return;
        int v = (Integer) (escale ? escMin : baseMin).getValue();
        if ((escale ? ac.escaleMin : ac.baseMin) == v) return;
        win.snapshot();
        if (escale) { ac.escaleMin = v; original(ac).escaleMin = v; } else { ac.baseMin = v; original(ac).baseMin = v; }
        win.changed(false);
    }

    // ------------------------------------------------------------------ menu clic droit
    void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) return;
        int di = row / 3, ri = row % 3;
        table.changeSelection(row, Math.max(0, table.columnAtPoint(e.getPoint())), false, false);
        JPopupMenu m = new JPopupMenu();
        m.add(item("Copier " + DAYS[di], "copy", () -> { win.clipboardDay = copyDay(ac.days.get(di)); win.status(DAYS[di] + " copié. Clic droit sur un autre jour → Coller."); }));
        JMenuItem paste = item("Coller sur " + DAYS[di], "paste", () -> { win.snapshot(); ac.days.set(di, copyDay(win.clipboardDay)); win.changed(false); });
        paste.setEnabled(win.clipboardDay != null);
        m.add(paste);
        m.add(item("Copier " + DAYS[di] + " sur tous les jours vides", "copy", () -> {
            win.snapshot();
            int n = 0;
            for (int dj = 0; dj < DAYS.length; dj++)
                if (dj != di && ac.days.get(dj).stream().noneMatch(Rotation::isUsed)) { ac.days.set(dj, copyDay(ac.days.get(di))); n++; }
            win.changed(false);
            win.status(DAYS[di] + " copié sur " + n + " jour(s) vide(s).");
        }));
        m.addSeparator();
        m.add(item("Vider la rotation " + (ri + 1) + " du " + DAYS[di], "clear", () -> { win.snapshot(); ac.days.get(di).set(ri, new Rotation()); win.changed(false); }));
        m.add(item("Vider tout le " + DAYS[di], "clear", () -> { win.snapshot(); ac.days.set(di, emptyDay()); win.changed(false); }));
        m.show(e.getComponent(), e.getX(), e.getY());
    }
    static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon, 16));
        i.addActionListener(e -> r.run());
        return i;
    }
}

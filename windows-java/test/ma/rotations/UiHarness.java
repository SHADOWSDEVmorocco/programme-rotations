package ma.rotations;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.List;

/** Pilote l'interface pour les tests (écran virtuel). Commandes lues dans le fichier <dossier>/cmd. */
public class UiHarness {
    static MainWindow w;
    static void shot(Component c, String path) throws Exception {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics(); c.paint(g); g.dispose();
        javax.imageio.ImageIO.write(img, "png", new File(path));
    }
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static AircraftPanel panel() { return w.currentPanel(); }

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        edt(() -> {
            Main.setupLookAndFeel();
            Path data = Storage.dataDir().resolve("programme.json");
            w = new MainWindow(data);
            w.setExtendedState(JFrame.NORMAL);
            w.setSize(1600, 900);
            w.setVisible(true);
        });
        Thread.sleep(1500);
        System.out.println("PRET " + w.sync.running() + " " + w.sync.code);
        Path cmd = Paths.get(dir, "cmd");
        while (true) {
            Thread.sleep(150);
            if (!Files.exists(cmd)) continue;
            String c = Files.readString(cmd).trim();
            Files.delete(cmd);
            try {
                switch (c) {
                    case "quit": edt(w::quit); return;
                    case "shot": edt(() -> { try { shot(w.getRootPane(), dir + "/fenetre.png"); } catch (Exception e) { e.printStackTrace(); } }); break;
                    case "tab2": edt(() -> w.tabs.setSelectedIndex(1)); break;
                    case "tab3": edt(() -> w.tabs.setSelectedIndex(2)); break;
                    case "tab1": edt(() -> w.tabs.setSelectedIndex(0)); break;
                    case "suggest": edt(() -> {
                        JTable t = panel().table;
                        t.editCellAt(3, 4);
                        AircraftPanel.SmartEditor ed = (AircraftPanel.SmartEditor) t.getCellEditor();
                        System.out.println("propositions escale mardi : " + ed.items.subList(0, Math.min(6, ed.items.size())) + " | texte proposé : " + ed.field.getText());
                        ed.field.setText("NOU"); ed.filter();
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < ed.combo.getItemCount(); i++) b.append(ed.combo.getItemAt(i)).append(" ");
                        System.out.println("filtre NOU : " + b.toString().trim());
                        ed.field.setText("AGADIR");
                        ed.stopCellEditing();
                        Core.Rotation r = panel().ac.days.get(1).get(0);
                        System.out.println("après AGADIR : " + r.depart + " " + r.hDepart + " > " + r.escale + " " + r.hArriveeEscale + "/" + r.hRedepart + " > " + r.arrivee + " " + r.hArrivee);
                        panel().ac.days.get(1).set(1, new Core.Rotation()); panel().ac.days.get(1).set(2, new Core.Rotation());
                        t.editCellAt(4, 4);
                        AircraftPanel.SmartEditor ed2 = (AircraftPanel.SmartEditor) t.getCellEditor();
                        ed2.field.setText("nouakchott"); ed2.stopCellEditing();
                        Core.Rotation r2 = panel().ac.days.get(1).get(1);
                        System.out.println("rotation 2 (tapé en minuscules) : " + r2.escale + " " + r2.hDepart + " → " + r2.hArrivee);
                        t.setValueAt("90", 3, 12);
                        System.out.println("Esc. base 90 → rot2 départ " + panel().ac.days.get(1).get(1).hDepart);
                        t.setValueAt("1h", 3, 6);
                        System.out.println("Escale 1h → redépart " + panel().ac.days.get(1).get(0).hRedepart + " arrivée " + panel().ac.days.get(1).get(0).hArrivee);
                        t.setValueAt("715", 3, 3);
                        System.out.println("Départ 715 → " + panel().ac.days.get(1).get(0).hDepart + " ... " + panel().ac.days.get(1).get(0).hArrivee);
                        w.doUndo();
                        System.out.println("Annuler → départ " + panel().ac.days.get(1).get(0).hDepart);
                        t.changeSelection(3, 4, false, false);
                    }); break;
                    case "edit": edt(() -> { w.tabs.setSelectedIndex(0); panel().table.setValueAt("AGADIR", 3, 4); System.out.println("PC: mardi ERJ = " + panel().ac.days.get(1).get(0).hArrivee); }); break;
                    case "edit2": edt(() -> { w.tabs.setSelectedIndex(0); panel().table.setValueAt("GUELMIM", 6, 4); System.out.println("PC: mercredi ERJ escale = " + panel().ac.days.get(2).get(0).escale); }); break;
                    case "weeks": edt(() -> System.out.println("PC: semaines " + Core.weeks(w.prog) + " lundi 16/11 ERJ = " + (Core.weeks(w.prog).contains("2026-11-16") ? w.prog.aircraft.get(0).weeks.get("2026-11-16").get(0).get(0).hDepart : "-"))); break;
                    case "dump": edt(() -> System.out.println("PC: rev " + w.prog.rev + " ERJ2 mardi: " + w.prog.aircraft.get(1).days.get(1).get(0).escale
                            + " ERJ jeudi: " + w.prog.aircraft.get(0).days.get(3).get(0).escale + " | " + w.statusLabel.getText())); break;
                    case "send": edt(() -> {
                        Dialogs.doSend(w, "pdf", true);
                        System.out.println("PC: envoi " + w.statusLabel.getText());
                    }); break;
                    case "week": edt(() -> {
                        w.snapshot(); Core.createWeek(w.prog, "2026-11-16", null); w.touch("PC"); w.selectWeek("2026-11-16");
                        panel().table.setValueAt("06:00", 0, 3);
                        System.out.println("PC: semaine " + w.week + " lundi " + panel().ac.days.get(0).get(0).hDepart + " | type " + w.prog.aircraft.get(0).days.get(0).get(0).hDepart
                                + " | titre " + w.getTitle() + " | jour " + panel().table.getValueAt(2, 0));
                    }); break;
                    case "histdlg": SwingUtilities.invokeLater(w::historyDialog); break;
                    case "exportweek": edt(() -> { try {
                        java.nio.file.Path f = java.nio.file.Paths.get(dir, "semaine.pdf");
                        Pdf.write(f, Core.views(w.prog, w.week), "Programme des rotations – " + Core.weekShort(w.week));
                        System.out.println("PC: PDF semaine écrit");
                    } catch (Exception e) { e.printStackTrace(); } }); break;
                    case "fqdlg": SwingUtilities.invokeLater(() -> Dialogs.frequencies(w)); break;
                    case "adddlg": SwingUtilities.invokeLater(() -> Dialogs.addAircraft(w)); break;
                    case "clonedlg": SwingUtilities.invokeLater(() -> Dialogs.cloneProgram(w)); break;
                    case "doadd": edt(() -> {
                        w.snapshot();
                        Core.Aircraft na = Core.newAircraft(w.prog, "ERJ.145 - AVION 3", "DAKHLA", w.prog.aircraft.get(1));
                        Core.cloneDays(w.prog, w.prog.aircraft.get(1), na, false);
                        w.rebuildTabs(); w.tabs.setSelectedIndex(w.tabs.getTabCount() - 1); w.touch("PC");
                        System.out.println("PC: onglets " + w.tabs.getTabCount() + " | nouvel avion lundi rot3 " + w.currentAc().days.get(0).get(2).escale + " | contrôles " + w.checks.size());
                    }); break;
                    case "senddlg": SwingUtilities.invokeLater(() -> Dialogs.send(w)); break;
                    case "wa": SwingUtilities.invokeLater(() -> Dialogs.doSend(w, "whatsapp", false)); break;
                    case "mail": SwingUtilities.invokeLater(() -> Dialogs.doSend(w, "email", true)); break;
                    case "clip": edt(() -> { try {
                        Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                        System.out.println("PC: presse-papiers = " + o);
                    } catch (Exception e) { System.out.println("PC: presse-papiers vide " + e); } }); break;
                    case "shotdlg": edt(() -> { int k = 0; for (Window x : Window.getWindows()) if (x instanceof JDialog && x.isShowing()) {
                        try { shot(((JDialog) x).getRootPane(), dir + "/dlg" + (k++) + ".png"); System.out.println("PC: fenêtre " + ((JDialog) x).getTitle()); } catch (Exception e) { e.printStackTrace(); } } }); break;
                    case "closedlg": edt(() -> { for (Window x : Window.getWindows()) if (x instanceof JDialog && x.isShowing()) x.dispose(); }); break;
                    default: System.out.println("commande inconnue " + c);
                }
            } catch (Exception e) { e.printStackTrace(); }
            System.out.flush();
        }
    }
}

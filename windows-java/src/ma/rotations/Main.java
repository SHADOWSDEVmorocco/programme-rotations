package ma.rotations;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.*;

/** Programme des Rotations – version Java. */
public final class Main {
    public static final String APP_NAME = "Programme des Rotations";
    public static final String VERSION = "2.7.0";
    static final Logger LOG = Logger.getLogger("rotations");
    private static FileLock lock;

    public static void main(String[] args) {
        Path dir = Storage.dataDir();
        setupLogging(dir);
        javax.imageio.ImageIO.setUseCache(false);     // images lues en mémoire (pas de fichiers temporaires)
        // une seule fenêtre à la fois (sinon deux programmes écriraient le même fichier)
        try {
            FileChannel ch = FileChannel.open(dir.resolve(".verrou"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = ch.tryLock();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "verrou", e);
        }
        SwingUtilities.invokeLater(() -> {
            setupLookAndFeel();
            if (lock == null && !Boolean.getBoolean("rotations.multi")) {
                JOptionPane.showMessageDialog(null, "Le Programme des Rotations est déjà ouvert sur cet ordinateur.",
                        APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
            }
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                LOG.log(Level.SEVERE, "Erreur non gérée", e);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                        "Une erreur est survenue :\n" + e + "\n\nVos données sont conservées. Détails dans journal.log.",
                        APP_NAME, JOptionPane.ERROR_MESSAGE));
            });
            MainWindow w = new MainWindow(dir.resolve("programme.json"));
            w.setVisible(true);
        });
    }

    static void setupLookAndFeel() {
        try {
            FlatLightLaf.setup();
            UIManager.put("Component.arc", 6);
            UIManager.put("Button.arc", 8);
            UIManager.put("Table.showVerticalLines", true);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.gridColor", new Color(0xd5dbe0));
            UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
            UIManager.put("TabbedPane.underlineColor", new Color(0x29a8e0));
        } catch (Exception e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        }
        {
            java.util.Locale.setDefault(java.util.Locale.FRANCE);
            JComponent.setDefaultLocale(java.util.Locale.FRANCE);
            UIManager.put("OptionPane.yesButtonText", "Oui");
            UIManager.put("OptionPane.noButtonText", "Non");
            UIManager.put("OptionPane.cancelButtonText", "Annuler");
            UIManager.put("OptionPane.okButtonText", "OK");
            UIManager.put("FileChooser.cancelButtonText", "Annuler");
            UIManager.put("FileChooser.saveButtonText", "Enregistrer");
            UIManager.put("FileChooser.openButtonText", "Ouvrir");
        }
    }

    static void setupLogging(Path dir) {
        try {
            FileHandler fh = new FileHandler(dir.resolve("journal.log").toString(), 1_000_000, 2, true);
            fh.setEncoding("UTF-8");
            fh.setFormatter(new SimpleFormatter() {
                @Override public String format(LogRecord r) {
                    StringWriter sw = new StringWriter();
                    if (r.getThrown() != null) r.getThrown().printStackTrace(new PrintWriter(sw));
                    return String.format("%1$tF %1$tT %2$s %3$s: %4$s%n%5$s", r.getMillis(), r.getLevel(), r.getLoggerName(), r.getMessage(), sw);
                }
            });
            Logger root = Logger.getLogger("rotations");
            root.addHandler(fh);
            root.setLevel(Level.INFO);
        } catch (Exception ignored) { }
    }

    static String stack(Throwable e) { StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw)); return sw.toString(); }
}

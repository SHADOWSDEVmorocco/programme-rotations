package ma.rotations;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.*;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Envoi par e-mail ou WhatsApp.
 * E-mail : Outlook (classique) s'ouvre avec le PDF joint ; sinon, un e-mail est préparé avec le texte
 * et le dossier du PDF s'ouvre. WhatsApp : WhatsApp s'ouvre avec le message et le PDF est copié (Ctrl+V).
 */
public final class Sharing {
    private Sharing() { }
    static final Logger LOG = Logger.getLogger("rotations.envoi");
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    public static Path documentsDir() throws java.io.IOException {
        Path base = Paths.get(System.getProperty("user.home"), "Documents");
        Path d = (Files.isDirectory(base) ? base : Paths.get(System.getProperty("user.home"))).resolve("Programme des rotations");
        Files.createDirectories(d);
        return d;
    }

    public static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    public static boolean browse(String uri) {
        try {
            if (WINDOWS) {               // fonctionne aussi pour whatsapp:// et mailto:
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start();
                return true;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(uri));
                return true;
            }
        } catch (Exception e) {
            LOG.log(Level.INFO, "ouverture " + uri, e);
        }
        return false;
    }

    /** Ouvre l'explorateur avec le fichier sélectionné. */
    public static void reveal(Path p) {
        try {
            if (WINDOWS) new ProcessBuilder("explorer", "/select," + p.toAbsolutePath()).start();
            else if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(p.toAbsolutePath().getParent().toFile());
        } catch (Exception e) {
            LOG.log(Level.INFO, "explorateur", e);
        }
    }

    /** Dossier de l'application (celui de ProgrammeRotations.jar). */
    static Path appDir() {
        try { return Paths.get(Sharing.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent(); }
        catch (Exception e) { return Paths.get("."); }
    }

    /** Résultat de l'ouverture du nouveau message. */
    public enum MailResult { OPENED, CANCELLED, UNAVAILABLE }

    /**
     * Nouveau message de la messagerie Windows par défaut avec le PDF DÉJÀ JOINT (envoyer_mail.exe / Simple MAPI),
     * sinon Outlook. La fenêtre du message reste ouverte : on n'attend que quelques secondes la réponse.
     */
    public static MailResult emailWithAttachment(Path pdf, String subject, String body) {
        if (!WINDOWS) return MailResult.UNAVAILABLE;
        Path helper = appDir().resolve("envoyer_mail.exe");
        if (Files.exists(helper)) {
            try {
                Process pr = new ProcessBuilder(helper.toString(), pdf.toAbsolutePath().toString(), subject, body).start();
                if (!pr.waitFor(6, TimeUnit.SECONDS)) return MailResult.OPENED;     // fenêtre du message ouverte
                if (pr.exitValue() == 0) return MailResult.OPENED;
                if (pr.exitValue() == 1) return MailResult.CANCELLED;
            } catch (Exception e) {
                LOG.log(Level.INFO, "envoyer_mail.exe", e);
            }
        }
        return outlook(pdf, subject, body, List.of()) ? MailResult.OPENED : MailResult.UNAVAILABLE;
    }

    /** Ouvre WhatsApp (application, sinon WhatsApp Web) sans texte. */
    public static boolean openWhatsApp() {
        return browse("whatsapp://") || browse("https://web.whatsapp.com/");
    }

    /** Outlook (classique) : nouveau message avec pièce jointe, via PowerShell. Renvoie false si Outlook n'est pas disponible. */
    public static boolean outlook(Path pdf, String subject, String body, List<String> to) {
        if (!WINDOWS) return false;
        String script = "$ErrorActionPreference='Stop';$o=New-Object -ComObject Outlook.Application;$m=$o.CreateItem(0);"
                + "$m.To=$env:RT_TO;$m.Subject=$env:RT_SUBJECT;$m.Body=$env:RT_BODY;[void]$m.Attachments.Add($env:RT_FILE);$m.Display()";
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.environment().put("RT_TO", to == null ? "" : String.join("; ", to));
            pb.environment().put("RT_SUBJECT", subject);
            pb.environment().put("RT_BODY", body);
            pb.environment().put("RT_FILE", pdf.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            pr.getInputStream().readAllBytes();
            if (!pr.waitFor(45, TimeUnit.SECONDS)) { pr.destroy(); return false; }
            return pr.exitValue() == 0;
        } catch (Exception e) {
            LOG.log(Level.INFO, "Outlook indisponible", e);
            return false;
        }
    }

    public static String mailto(String subject, String body, List<String> to) {
        if (body.length() > 1800) body = body.substring(0, 1800) + "\n…\n(programme complet dans le PDF joint)";
        return "mailto:" + String.join(",", to) + "?subject=" + enc(subject) + "&body=" + enc(body);
    }

    /** Ouvre WhatsApp (application, sinon WhatsApp Web) avec le message. */
    public static boolean whatsapp(String text, String phone) {
        String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
        if (browse("whatsapp://send?text=" + enc(text) + (digits.isEmpty() ? "" : "&phone=" + digits))) return true;
        return browse("https://wa.me/" + digits + "?text=" + enc(text));
    }

    /** Copie un fichier dans le presse-papiers (à coller avec Ctrl+V dans WhatsApp ou un e-mail). */
    public static void copyFile(Path p) {
        final List<File> files = List.of(p.toFile());
        Transferable t = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.javaFileListFlavor.equals(f); }
            public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(f)) throw new UnsupportedFlavorException(f);
                return files;
            }
        };
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
    }
}

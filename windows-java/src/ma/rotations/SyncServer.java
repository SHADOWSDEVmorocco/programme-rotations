package ma.rotations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serveur de synchronisation Wi-Fi pour la tablette Android (même protocole que les versions précédentes) :
 * GET /api/ping, GET /api/programme?rev=N, PUT /api/programme, et l'interface web à « / ».
 * Les envois de la tablette sont appliqués dans le fil de l'interface (Swing), un à la fois.
 */
public final class SyncServer {
    static final Logger LOG = Logger.getLogger("rotations.sync");
    static final int MAX_BODY = 5 * 1024 * 1024;
    static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8", "svg", "image/svg+xml", "json", "application/json", "png", "image/png", "ico", "image/x-icon");

    /** Configuration enregistrée dans synchronisation.json (même fichier que la version Python). */
    public static final class Config {
        public boolean enabled = true;
        public int port = 8765;
        public String code = "";

        public static Config load(Path p) {
            Config c = new Config();
            try {
                Map<String, Object> d = Json.obj(Json.parse(Storage.read(p)));
                c.enabled = !Boolean.FALSE.equals(d.get("enabled"));
                c.port = Json.num(d.get("port"), 8765);
                c.code = Json.str(d.get("code"), "");
            } catch (Exception ignored) { }
            if (!c.code.matches("\\d{6}")) { c.code = newCode(); c.save(p); }
            return c;
        }
        public void save(Path p) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("enabled", enabled); d.put("port", port); d.put("code", code);
            try { Files.write(p, Json.pretty(d).getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { LOG.log(Level.WARNING, "config", e); }
        }
    }
    public static String newCode() { return String.format("%06d", new SecureRandom().nextInt(1_000_000)); }

    /** Envoi de la tablette à traiter dans le fil de l'interface. */
    public static final class Job {
        public int baseRev; public String json, appareil; public boolean force;
        public int status = 500; public Map<String, Object> payload = Map.of("erreur", "non traité");
    }

    private HttpServer http;
    private volatile int rev;
    private volatile String json = "{}", by = "PC";
    public volatile String code = "", error = "";
    public volatile int port;
    public final Map<String, Long> devices = new ConcurrentHashMap<>();
    private final Consumer<Job> onPut;
    private final Runnable onActivity;

    public SyncServer(Consumer<Job> onPut, Runnable onActivity) { this.onPut = onPut; this.onActivity = onActivity; }

    public synchronized void publish(int rev, String json, String by) { this.rev = rev; this.json = json; this.by = by; }
    public synchronized Object[] snapshot() { return new Object[]{rev, json, by}; }
    public boolean running() { return http != null; }

    public boolean start(int port, String code) {
        stop();
        this.port = port; this.code = code; this.error = "";
        try {
            http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (IOException e) {
            error = "Port " + port + " indisponible (" + e.getMessage() + ")";
            LOG.warning(error);
            http = null;
            return false;
        }
        http.setExecutor(Executors.newFixedThreadPool(8, r -> { Thread t = new Thread(r, "sync"); t.setDaemon(true); return t; }));
        http.createContext("/", this::handle);
        http.start();
        LOG.info("Serveur de synchronisation démarré sur le port " + port);
        return true;
    }
    public void stop() { if (http != null) { http.stop(0); http = null; } }

    // ------------------------------------------------------------------ requêtes
    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            if (method.equals("OPTIONS")) { send(ex, 204, null); return; }
            if (path.equals("/api/ping") && method.equals("GET")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("app", "rotations"); m.put("version", Main.VERSION); m.put("pc", hostName());
                send(ex, 200, m);
            } else if (path.equals("/api/programme")) {
                if (!authorized(ex)) return;
                if (method.equals("GET")) getProgramme(ex);
                else if (method.equals("PUT")) putProgramme(ex);
                else send(ex, 405, Map.of("erreur", "méthode"));
            } else if (method.equals("GET") || method.equals("HEAD")) staticFile(ex, path);
            else send(ex, 404, Map.of("erreur", "introuvable"));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "requête", e);
            try { send(ex, 500, Map.of("erreur", String.valueOf(e.getMessage()))); } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) throws IOException {
        String given = Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Code")).orElse("");
        boolean ok = !code.isEmpty() && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }     // ralentit les essais de code
            send(ex, 401, Map.of("erreur", "code incorrect"));
            return false;
        }
        String dev = Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Appareil")).orElse("");
        try { dev = URLDecoder.decode(dev, StandardCharsets.UTF_8); } catch (Exception ignored) { }
        devices.put(dev.isBlank() ? ex.getRemoteAddress().getAddress().getHostAddress() : dev, System.currentTimeMillis());
        if (onActivity != null) SwingUtilities.invokeLater(onActivity);
        return true;
    }

    private void getProgramme(HttpExchange ex) throws IOException {
        int since = -1;
        String q = ex.getRequestURI().getQuery();
        if (q != null) for (String kv : q.split("&")) if (kv.startsWith("rev=")) try { since = Integer.parseInt(kv.substring(4)); } catch (NumberFormatException ignored) { }
        Object[] s = snapshot();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rev", s[0]);
        if (since == (int) s[0]) m.put("changed", false);
        else { m.put("changed", true); m.put("json", s[1]); m.put("modifiePar", s[2]); }
        send(ex, 200, m);
    }

    private void putProgramme(HttpExchange ex) throws IOException {
        Job job = new Job();
        try {
            byte[] body = readBody(ex.getRequestBody());
            Map<String, Object> req = Json.obj(Json.parse(new String(body, StandardCharsets.UTF_8)));
            job.baseRev = Json.num(req.get("baseRev"), -1);
            job.json = Json.str(req.get("json"), null);
            if (job.json == null) throw new IllegalArgumentException("champ json manquant");
            String a = Json.str(req.get("appareil"), "Tablette");
            job.appareil = a.isBlank() ? "Tablette" : a.substring(0, Math.min(60, a.length()));
            job.force = Boolean.TRUE.equals(req.get("force"));
        } catch (Exception e) {
            send(ex, 400, Map.of("erreur", "requête invalide : " + e.getMessage()));
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> onPut.accept(job));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "application de l'envoi", e);
        }
        send(ex, job.status, job.payload);
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        if (b.length == 0 || b.length > MAX_BODY) throw new IOException("taille invalide");
        return b;
    }

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") || path.isEmpty() ? "index.html" : path.substring(1);
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (rel.contains("..") || rel.contains("\\") || !TYPES.containsKey(ext)) { send(ex, 404, Map.of("erreur", "introuvable")); return; }
        try (InputStream in = SyncServer.class.getResourceAsStream("/web/" + rel)) {
            if (in == null) { send(ex, 404, Map.of("erreur", "introuvable")); return; }
            sendBytes(ex, 200, TYPES.get(ext), in.readAllBytes());
        }
    }

    private void send(HttpExchange ex, int status, Map<String, Object> payload) throws IOException {
        sendBytes(ex, status, "application/json; charset=utf-8", payload == null ? new byte[0] : Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }
    private void sendBytes(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "X-Code, X-Appareil, Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS");
        h.set("Access-Control-Allow-Private-Network", "true");
        boolean head = ex.getRequestMethod().equals("HEAD") || status == 204;
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    public static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n != null && !n.isBlank()) return n;
        try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "PC"; }
    }

    /** Adresses IPv4 du PC sur le réseau local (la principale en premier). */
    public static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("10.255.255.255"), 1);       // aucun paquet n'est envoyé
            String ip = s.getLocalAddress().getHostAddress();
            if (!ip.startsWith("0.") && !ip.startsWith("127.")) ips.add(ip);
        } catch (Exception ignored) { }
        if (!Boolean.getBoolean("rotations.noNetIf")) try {      // option de test (émulateur Wine)
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress() && !ips.contains(a.getHostAddress())) ips.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        if (ips.isEmpty()) ips.add("127.0.0.1");
        return ips;
    }
}

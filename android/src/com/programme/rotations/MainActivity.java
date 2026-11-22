package com.programme.rotations;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.util.Base64;
import android.widget.Toast;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Programme des Rotations – application tablette.
 * L'interface (HTML/JS, identique au site et au logiciel PC) est embarquée dans l'APK
 * et servie depuis http://app.rotations.local/ (adresse interne, jamais envoyée sur le réseau).
 * Le pont « AndroidBridge » fait les échanges Wi-Fi avec le PC, la recherche du PC et l'impression.
 */
public class MainActivity extends Activity {
    static final String HOST = "app.rotations.local";
    static final String START = "http://" + HOST + "/index.html";
    static final int FILE_CHOOSER = 42;

    WebView web;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    ValueCallback<Uri[]> fileCallback;

    static final Map<String, String> MIME = new HashMap<String, String>();
    static {
        MIME.put("html", "text/html"); MIME.put("js", "text/javascript"); MIME.put("css", "text/css");
        MIME.put("svg", "image/svg+xml"); MIME.put("json", "application/json"); MIME.put("png", "image/png");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebViewClient(new LocalClient());
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        if (state != null) web.restoreState(state);
        if (web.getUrl() == null) web.loadUrl(START);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        // enregistre immédiatement les modifications en attente
        web.evaluateJavascript("window.__flush && window.__flush()", null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__androidBack && window.__androidBack()) ? 'oui' : 'non'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v == null || !v.contains("oui")) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_CHOOSER && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /** Sert les fichiers de l'application depuis assets/www. */
    class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
            if (path.contains("..")) return notFound();
            String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
            String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
            try {
                InputStream in = getAssets().open("www" + path);
                Map<String, String> h = new HashMap<String, String>();
                h.put("Cache-Control", "no-cache");
                return new WebResourceResponse(mime, "UTF-8", 200, "OK", h, in);
            } catch (Exception e) {
                return notFound();
            }
        }

        WebResourceResponse notFound() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    new HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u = Uri.parse(url);
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
            return true;
        }
    }

    /** Boîtes alert/confirm/prompt natives + choix de fichier (import). */
    class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "Choisir un programme (.json)"), FILE_CHOOSER);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    void callback(final String id, final int status, final String body) {
        ui.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.__bridgeCb(" + JSONObject.quote(id) + "," + status + ","
                        + JSONObject.quote(body == null ? "" : body) + ")", null);
            }
        });
    }

    static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    static String[] http(String method, String url, JSONObject headers, String body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout * 2);
        c.setRequestMethod(method);
        c.setUseCaches(false);
        if (headers != null) {
            Iterator<String> it = headers.keys();
            while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, headers.getString(k)); }
        }
        if (body != null && body.length() > 0) {
            byte[] b = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
        }
        int code = c.getResponseCode();
        String text = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        return new String[]{String.valueOf(code), text};
    }

    static List<String> localIPv4() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(e)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Fonctions appelées depuis le JavaScript. */
    class Bridge {
        @JavascriptInterface
        public void request(final String id, final String method, final String url, final String headers, final String body) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        String[] r = http(method, url, headers == null || headers.isEmpty() ? null : new JSONObject(headers), body, 4000);
                        callback(id, Integer.parseInt(r[0]), r[1]);
                    } catch (Exception e) {
                        callback(id, 0, String.valueOf(e.getMessage()));
                    }
                }
            });
        }

        /** Cherche le PC (port donné) sur tous les appareils du Wi-Fi (réseau /24). */
        @JavascriptInterface
        public void scan(final String id, final int port) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final JSONArray found = new JSONArray();
                    ExecutorService ex = Executors.newFixedThreadPool(48);
                    for (String ip : localIPv4()) {
                        String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                        for (int i = 1; i < 255; i++) {
                            final String target = prefix + i;
                            ex.execute(new Runnable() {
                                @Override public void run() {
                                    try {
                                        String[] r = http("GET", "http://" + target + ":" + port + "/api/ping", null, null, 700);
                                        JSONObject j = new JSONObject(r[1]);
                                        if ("200".equals(r[0]) && "rotations".equals(j.optString("app"))) {
                                            JSONObject o = new JSONObject();
                                            o.put("ip", target);
                                            o.put("pc", j.optString("pc"));
                                            synchronized (found) { found.put(o); }
                                        }
                                    } catch (Exception ignored) { }
                                }
                            });
                        }
                    }
                    ex.shutdown();
                    try { ex.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    synchronized (found) { callback(id, 200, found.toString()); }
                }
            }).start();
        }

        /** Imprime (ou enregistre en PDF) la page telle que préparée pour l'impression. */
        @JavascriptInterface
        public void print(final String title) {
            ui.post(new Runnable() {
                @Override public void run() {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    PrintDocumentAdapter a = web.createPrintDocumentAdapter(title);
                    PrintAttributes attrs = new PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build();
                    pm.print(title, a, attrs);
                }
            });
        }

        /** Partage un texte (export du programme vers e-mail, Drive, WhatsApp…). */
        @JavascriptInterface
        public void shareText(final String filename, final String text) {
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, filename);
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Exporter le programme"));
                }
            });
        }

        /**
         * Partage un fichier (PDF) avec un texte.
         * target = "whatsapp" (WhatsApp ou WhatsApp Business), "email" (applications de messagerie) ou "autre".
         */
        @JavascriptInterface
        public void shareFile(final String name, final String base64, final String mime, final String text,
                              final String target, final String emailsJson, final String subject) {
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        File dir = FilesProvider.dir(MainActivity.this);
                        File[] old = dir.listFiles();
                        if (old != null) for (File f : old) f.delete();
                        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                        File f = new File(dir, safe);
                        FileOutputStream os = new FileOutputStream(f);
                        os.write(Base64.decode(base64, Base64.DEFAULT));
                        os.close();
                        Uri uri = Uri.parse("content://" + FilesProvider.AUTHORITY + "/" + FilesProvider.DIR + "/" + safe);

                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        if (text != null && text.length() > 0) i.putExtra(Intent.EXTRA_TEXT, text);
                        if (subject != null && subject.length() > 0) i.putExtra(Intent.EXTRA_SUBJECT, subject);
                        i.setClipData(ClipData.newRawUri(safe, uri));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        if ("whatsapp".equals(target)) {
                            for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
                                try { i.setPackage(pkg); startActivity(i); return; } catch (ActivityNotFoundException ignored) { }
                            }
                            i.setPackage(null);
                            Toast.makeText(MainActivity.this, "WhatsApp n'est pas installé : choisissez une application", Toast.LENGTH_LONG).show();
                        } else if ("email".equals(target)) {
                            if (emailsJson != null && emailsJson.length() > 2) {
                                JSONArray a = new JSONArray(emailsJson);
                                String[] to = new String[a.length()];
                                for (int k = 0; k < a.length(); k++) to[k] = a.getString(k);
                                i.putExtra(Intent.EXTRA_EMAIL, to);
                            }
                            Intent onlyMail = new Intent(i);
                            onlyMail.setSelector(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")));
                            try { startActivity(onlyMail); return; }
                            catch (ActivityNotFoundException ignored) { }
                        }
                        startActivity(Intent.createChooser(i, "Envoyer le programme"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Envoi impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String info() {
            return "{\"platform\":\"android\",\"sdk\":" + Build.VERSION.SDK_INT + ",\"ips\":" + new JSONArray(localIPv4()) + "}";
        }
    }
}

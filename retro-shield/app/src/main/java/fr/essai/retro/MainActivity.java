package fr.essai.retro;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * L'appli de la Shield : une page plein écran (WebView).
 * 1. Elle cherche le Mac sur le réseau (Bonjour « _retromac._tcp ») et affiche assets/accueil.html.
 * 2. Une fois le Mac trouvé, elle ouvre l'interface servie par le Mac (http://mac:8642/).
 * Les manettes passent par l'API Gamepad de la page ; la touche Retour devient la touche « GoBack ».
 */
public class MainActivity extends Activity {
    private static final String SERVICE = "_retromac._tcp.";
    private static final String ACCUEIL = "file:///android_asset/accueil.html";

    private WebView web;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener ecoute;
    private WifiManager.MulticastLock verrou;
    private final ArrayDeque<NsdServiceInfo> aResoudre = new ArrayDeque<>();
    private boolean resolutionEnCours = false;

    @Override
    protected void onCreate(Bundle etat) {
        super.onCreate(etat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        pleinEcran();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // le son du jeu démarre sans toucher l'écran
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);   // la page d'accueil (fichier local) peut interroger le Mac
        web.setBackgroundColor(0xFF050816);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Pont(), "Shield");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest r) { r.grant(r.getResources()); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                // le Mac ne répond plus : retour à la recherche
                if (r.isForMainFrame() && !r.getUrl().toString().startsWith("file:")) v.loadUrl(ACCUEIL + "?perdu=1");
            }
        });
        web.loadUrl(ACCUEIL);
        web.requestFocus();
        chercherMac();
        // Bonjour ne passe pas sur tous les réseaux : on essaie aussi toutes les adresses du réseau de la maison
        balayer();
        web.postDelayed(this::balayer, 12000);
    }

    private void pleinEcran() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) pleinEcran(); }

    // --- Touches : Retour → « GoBack » pour la page ; les boutons de manette restent à la page (pas de Retour automatique sur B) ---
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (ACCUEIL.equals(baseUrl())) finish();
                else web.evaluateJavascript("dispatchEvent(new KeyboardEvent('keydown',{key:'GoBack'}))", null);
            }
            return true;
        }
        boolean manette = (e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (manette && KeyEvent.isGamepadButton(code)) {
            web.dispatchKeyEvent(e);   // nourrit l'API Gamepad de la page
            return true;               // et empêche Android de transformer B en « Retour »
        }
        return super.dispatchKeyEvent(e);
    }

    private String baseUrl() {
        String u = web.getUrl();
        if (u == null) return "";
        int i = u.indexOf('?');
        return i < 0 ? u : u.substring(0, i);
    }

    // --- Recherche du Mac (Bonjour / mDNS) ---
    private void chercherMac() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) { verrou = wifi.createMulticastLock("retro"); verrou.setReferenceCounted(false); verrou.acquire(); }
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        ecoute = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int c) { signaler("erreur", "Recherche impossible (" + c + ")"); }
            @Override public void onStopDiscoveryFailed(String t, int c) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo info) { synchronized (aResoudre) { aResoudre.add(info); } resoudreSuivant(); }
            @Override public void onServiceLost(NsdServiceInfo info) {
                try { JSONObject o = new JSONObject(); o.put("nom", info.getServiceName()); envoyer("macPerdu", o); } catch (Exception ignore) {}
            }
        };
        try { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, ecoute); }
        catch (Exception e) { signaler("erreur", e.getMessage()); }
    }

    // --- Recherche de secours : http://<réseau>.1 à .254:8642/api/catalogue ---
    private void balayer() {
        new Thread(() -> {
            String base = null;
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses()))
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) { String ip = a.getHostAddress(); base = ip.substring(0, ip.lastIndexOf('.') + 1); }
                }
            } catch (Exception ignore) {}
            if (base == null) return;
            ExecutorService ex = Executors.newFixedThreadPool(48);
            for (int i = 1; i < 255; i++) { final String h = base + i; ex.submit(() -> essayer(h, 8642)); }
            ex.shutdown();
        }).start();
    }
    private void essayer(String hote, int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + hote + ":" + port + "/api/catalogue").openConnection();
            c.setConnectTimeout(700); c.setReadTimeout(3000);
            if (c.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) { for (String l; (l = r.readLine()) != null; ) sb.append(l); }
                JSONObject o = new JSONObject();
                o.put("nom", new JSONObject(sb.toString()).optString("mac", hote));
                o.put("hote", hote);
                o.put("port", port);
                envoyer("macTrouve", o);
            }
            c.disconnect();
        } catch (Exception ignore) {}
    }

    // Android ne résout qu'un service à la fois : file d'attente
    private void resoudreSuivant() {
        NsdServiceInfo info;
        synchronized (aResoudre) {
            if (resolutionEnCours || aResoudre.isEmpty()) return;
            resolutionEnCours = true;
            info = aResoudre.poll();
        }
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int c) { fini(); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("nom", i.getServiceName());
                    o.put("hote", i.getHost().getHostAddress());
                    o.put("port", i.getPort());
                    envoyer("macTrouve", o);
                } catch (Exception ignore) {}
                fini();
            }
            private void fini() { synchronized (aResoudre) { resolutionEnCours = false; } resoudreSuivant(); }
        });
    }

    private void envoyer(String fonction, JSONObject o) {
        runOnUiThread(() -> web.evaluateJavascript("window." + fonction + " && window." + fonction + "(" + o + ")", null));
    }
    private void signaler(String type, String message) {
        try { JSONObject o = new JSONObject(); o.put("type", type); o.put("message", message); envoyer("signal", o); } catch (Exception ignore) {}
    }

    /** Fonctions appelables depuis la page d'accueil. */
    private class Pont {
        @JavascriptInterface public void ouvrir(String url) { runOnUiThread(() -> web.loadUrl(url)); }
        @JavascriptInterface public void quitter() { runOnUiThread(MainActivity.this::finish); }
        // vérifie une adresse (dernière utilisée, ou tapée à la main) : si le Mac répond, macTrouve() est appelé
        @JavascriptInterface public void essayer(String hote, int port) { new Thread(() -> MainActivity.this.essayer(hote, port)).start(); }
    }

    @Override
    protected void onDestroy() {
        try { if (nsd != null && ecoute != null) nsd.stopServiceDiscovery(ecoute); } catch (Exception ignore) {}
        if (verrou != null && verrou.isHeld()) verrou.release();
        web.destroy();
        super.onDestroy();
    }
}

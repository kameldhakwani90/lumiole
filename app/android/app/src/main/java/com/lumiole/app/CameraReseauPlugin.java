package com.lumiole.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caméra de la maison (Tapo, Imou, Ezviz, Reolink… sur le même Wi-Fi que la télé).
 * - chercher() : trouve les caméras (ONVIF WS-Discovery + port RTSP 554 ouvert), avec la marque et le modèle quand ils sont annoncés ;
 * - demarrer({ url }) : lit le flux RTSP avec un tampon minimal (Media3) dans une petite vue cachée sous la page ;
 * - les images (petites, JPEG) partent vers la page par l'évènement « image » : la détection des corps se fait dans la page,
 *   sur la télé. Rien ne sort de la maison, rien n'est enregistré.
 */
@CapacitorPlugin(name = "CameraReseau")
public class CameraReseauPlugin extends Plugin {
    private ExoPlayer lecteur;
    private TextureView vue;
    private HandlerThread filImages;
    private Handler mainHandler;
    private volatile boolean actif = false;
    private int largeur = 320, hauteur = 180, ips = 15;

    // ---------- chercher les caméras sur le Wi-Fi ----------
    @PluginMethod
    public void chercher(PluginCall call) {
        new Thread(() -> {
            Map<String, JSObject> trouvees = Collections.synchronizedMap(new LinkedHashMap<>());
            Thread onvif = new Thread(() -> sonderOnvif(trouvees));
            onvif.start();
            balayerRtsp(trouvees);
            try { onvif.join(4000); } catch (InterruptedException ignored) { }
            JSArray l = new JSArray();
            synchronized (trouvees) { for (JSObject o : trouvees.values()) l.put(o); }
            JSObject r = new JSObject();
            r.put("cameras", l);
            call.resolve(r);
        }).start();
    }

    // ONVIF : une question envoyée à tout le réseau, les caméras répondent avec leur adresse, leur marque et leur modèle
    private void sonderOnvif(Map<String, JSObject> trouvees) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock verrou = null;
        MulticastSocket s = null;
        try {
            if (wifi != null) { verrou = wifi.createMulticastLock("lumiole-onvif"); verrou.setReferenceCounted(false); verrou.acquire(); }
            String sonde = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID><w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>"
                + "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
            byte[] b = sonde.getBytes("UTF-8");
            s = new MulticastSocket();
            s.setSoTimeout(700);
            InetAddress groupe = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) s.send(new DatagramPacket(b, b.length, groupe, 3702));
            long fin = System.currentTimeMillis() + 3000;
            byte[] tampon = new byte[16384];
            while (System.currentTimeMillis() < fin) {
                DatagramPacket p = new DatagramPacket(tampon, tampon.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                String rep = new String(p.getData(), 0, p.getLength(), "UTF-8");
                String ip = p.getAddress().getHostAddress();
                JSObject o = trouvees.containsKey(ip) ? trouvees.get(ip) : new JSObject();
                o.put("ip", ip); o.put("onvif", true);
                String portees = premier(rep, "Scopes>([^<]*)<");
                String nom = scope(portees, "name"), modele = scope(portees, "hardware");
                if (nom != null) o.put("marque", nom);
                if (modele != null) o.put("modele", modele);
                trouvees.put(ip, o);
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
            if (verrou != null && verrou.isHeld()) verrou.release();
        }
    }

    // RTSP : on regarde quelles adresses du réseau ont le port 554 ouvert (les caméras qui ne parlent pas ONVIF)
    private void balayerRtsp(Map<String, JSObject> trouvees) {
        String base = reseauLocal();
        if (base == null) return;
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = base + i;
            pool.execute(() -> {
                try (Socket so = new Socket()) {
                    so.connect(new InetSocketAddress(ip, 554), 350);
                    JSObject o = trouvees.containsKey(ip) ? trouvees.get(ip) : new JSObject();
                    o.put("ip", ip); o.put("rtsp", true);
                    trouvees.put(ip, o);
                } catch (Exception ignored) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    private static String reseauLocal() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String premier(String texte, String motif) {
        Matcher m = Pattern.compile(motif).matcher(texte);
        return m.find() ? m.group(1) : null;
    }

    // « onvif://www.onvif.org/name/TP-IPC onvif://www.onvif.org/hardware/C200 » → TP-IPC, C200
    private static String scope(String portees, String cle) {
        if (portees == null) return null;
        Matcher m = Pattern.compile("onvif://www\\.onvif\\.org/" + cle + "/([^\\s]+)").matcher(portees);
        if (!m.find()) return null;
        try { return java.net.URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception e) { return m.group(1); }
    }

    // ---------- lire le flux d'une caméra ----------
    @PluginMethod
    @OptIn(markerClass = UnstableApi.class)
    public void demarrer(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("url"); return; }
        largeur = call.getInt("largeur", 320); hauteur = call.getInt("hauteur", 180); ips = call.getInt("ips", 15);
        mainHandler = new Handler(Looper.getMainLooper());
        getActivity().runOnUiThread(() -> {
            arreterInterne();
            try {
                // une petite vue sous la page (la page la recouvre) : elle reçoit l'image décodée
                vue = new TextureView(getContext());
                View page = getBridge().getWebView();
                ViewGroup parent = (ViewGroup) page.getParent();
                parent.addView(vue, 0, new FrameLayout.LayoutParams(640, 360));
                // tampon minimal : on veut l'image la plus récente, pas une image fluide en retard
                DefaultLoadControl tampon = new DefaultLoadControl.Builder().setBufferDurationsMs(100, 1000, 50, 50).build();
                lecteur = new ExoPlayer.Builder(getContext()).setLoadControl(tampon).build();
                lecteur.setVideoTextureView(vue);
                lecteur.setVolume(0f);
                final boolean[] repondu = { false };
                lecteur.addListener(new Player.Listener() {
                    @Override public void onRenderedFirstFrame() {
                        if (!repondu[0]) { repondu[0] = true; JSObject r = new JSObject(); r.put("ok", true); call.resolve(r); }
                    }
                    @Override public void onPlayerError(PlaybackException e) {
                        JSObject o = new JSObject(); o.put("message", e.getErrorCodeName()); notifyListeners("erreur", o);
                        if (!repondu[0]) { repondu[0] = true; call.reject(e.getErrorCodeName()); }
                    }
                });
                RtspMediaSource source = new RtspMediaSource.Factory().setTimeoutMs(6000).createMediaSource(MediaItem.fromUri(url));
                lecteur.setMediaSource(source);
                lecteur.setPlayWhenReady(true);
                lecteur.prepare();
                actif = true;
                mainHandler.postDelayed(() -> { if (!repondu[0]) { repondu[0] = true; call.reject("TIMEOUT"); } }, 9000);
                filImages = new HandlerThread("lumiole-images"); filImages.start();
                capturer();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        });
    }

    // environ 15 fois par seconde : la dernière image, petite, en JPEG, envoyée à la page
    private void capturer() {
        if (!actif || vue == null) return;
        long t0 = System.currentTimeMillis();
        try {
            if (vue.isAvailable()) {
                final Bitmap bmp = vue.getBitmap(largeur, hauteur);
                if (bmp != null) {
                    new Handler(filImages.getLooper()).post(() -> {
                        try {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out);
                            bmp.recycle();
                            JSObject o = new JSObject();
                            o.put("d", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                            o.put("t", System.currentTimeMillis());
                            notifyListeners("image", o);
                        } catch (Exception ignored) { }
                    });
                }
            }
        } catch (Exception ignored) { }
        long attente = Math.max(5, 1000 / Math.max(1, ips) - (System.currentTimeMillis() - t0));
        mainHandler.postDelayed(this::capturer, attente);
    }

    @PluginMethod
    public void arreter(PluginCall call) {
        getActivity().runOnUiThread(() -> { arreterInterne(); call.resolve(); });
    }

    private void arreterInterne() {
        actif = false;
        if (lecteur != null) { try { lecteur.release(); } catch (Exception ignored) { } lecteur = null; }
        if (vue != null) { ViewGroup p = (ViewGroup) vue.getParent(); if (p != null) p.removeView(vue); vue = null; }
        if (filImages != null) { filImages.quitSafely(); filImages = null; }
    }

    @Override
    protected void handleOnDestroy() { arreterInterne(); }
}

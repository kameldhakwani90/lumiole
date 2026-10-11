package com.lumiole.app;

import android.app.Activity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Verrou enfant : épingle l'app à l'écran (Android « épinglage d'application »).
 * L'enfant ne peut plus sortir de l'app ; un parent la libère depuis l'Espace parents (code).
 */
@CapacitorPlugin(name = "Verrou")
public class VerrouPlugin extends Plugin {
    @PluginMethod
    public void activer(PluginCall call) {
        Activity a = getActivity();
        a.runOnUiThread(() -> {
            try { a.startLockTask(); call.resolve(); } catch (Exception e) { call.reject(e.getMessage()); }
        });
    }

    @PluginMethod
    public void desactiver(PluginCall call) {
        Activity a = getActivity();
        a.runOnUiThread(() -> {
            try { a.stopLockTask(); call.resolve(); } catch (Exception e) { call.reject(e.getMessage()); }
        });
    }

    @PluginMethod
    public void etat(PluginCall call) {
        android.app.ActivityManager am = (android.app.ActivityManager) getContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
        JSObject r = new JSObject();
        r.put("actif", am != null && am.getLockTaskModeState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE);
        call.resolve(r);
    }
}

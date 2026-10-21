package com.lumiole.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VerrouPlugin.class); // verrou enfant (épinglage de l'écran)
        registerPlugin(CameraReseauPlugin.class); // caméra de la maison (même Wi-Fi)
        super.onCreate(savedInstanceState);
    }
}

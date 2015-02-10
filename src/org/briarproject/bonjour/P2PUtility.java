package org.briarproject.bonjour;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;

public class P2PUtility {
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    public P2PUtility(Context context) {
        p2p = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (p2p == null) {
            throw new RuntimeException("This device does not support Wi-Fi Direct");
        }
        channel = p2p.initialize(context, context.getMainLooper(), null);
    }

    // Discovery
    // Bluetooth discovery followed by normal bluetooth scanning
    // Wi-Fi Direct discovery followed by either bluetooth connectivity or

}
package org.parano1d.courier;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Receives requests from the Parano1d Wear OS app. Started by Google Play services on demand. */
public final class WatchListenerService extends WearableListenerService {
    @Override public void onMessageReceived(MessageEvent event) {
        if ("/p1/rpc".equals(event.getPath())) {
            MainActivity.ensureInitialized(getApplicationContext());
            WatchBridge.onRpc(this, event.getSourceNodeId(), event.getData());
        }
    }
}

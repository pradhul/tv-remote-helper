package com.pradhul.remotehelper;
import android.content.*; import android.util.Log;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i(RemoteService.TAG, "BootReceiver: " + i.getAction());
        RemoteService.start(c, i.getAction(), null);
    }
}

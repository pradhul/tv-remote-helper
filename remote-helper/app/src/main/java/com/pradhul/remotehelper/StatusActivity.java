package com.pradhul.remotehelper;
import android.app.Activity; import android.os.Bundle; import android.widget.TextView;
public class StatusActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String addr = getIntent().getStringExtra(RemoteService.EXTRA_ADDRESS);
        RemoteService.start(this, "activity", addr);
        TextView t = new TextView(this); t.setTextSize(22); t.setPadding(48,48,48,48);
        t.setText("Remote Helper running\nTarget: " + RemoteService.getAddress(this) + "\nLogs: adb logcat -s RemoteHelper");
        setContentView(t);
    }
}

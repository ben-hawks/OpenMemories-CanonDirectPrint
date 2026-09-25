package com.github.benhawks.ivy2print.app;

import android.content.Intent;
import android.os.Bundle;

/** Opens the camera's own Wi-Fi settings to join the bridge's network (from PMCADemo). */
public class WifiSettingActivity extends BaseActivity {
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new WifiHelper(this).setEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!started) {
            started = true;
            try {
                startActivityForResult(new Intent("com.sony.scalar.app.wifisettings.WifiSettings"), 0);
            } catch (android.content.ActivityNotFoundException e) {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), 0);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        finish();
    }
}

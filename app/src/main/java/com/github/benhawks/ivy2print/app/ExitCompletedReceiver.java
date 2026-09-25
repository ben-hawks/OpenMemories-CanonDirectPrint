package com.github.benhawks.ivy2print.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Sent by the camera when the app is closed (e.g. via the Menu/Home button). */
public class ExitCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.info("Exit completed");
        new WifiHelper(context).setEnabled(false);
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}

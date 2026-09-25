package com.github.benhawks.canonprint.app;

import android.app.Activity;
import android.content.Intent;
import android.view.KeyEvent;

import com.github.ma1co.openmemories.framework.DisplayManager;
import com.sony.scalar.sysutil.ScalarInput;

/**
 * Maps the camera's hardware keys to callbacks.
 * Adapted from PMCADemo's BaseActivity (MIT, https://github.com/ma1co/PMCADemo).
 */
public class BaseActivity extends Activity implements DisplayManager.Listener {
    public static final String NOTIFICATION_DISPLAY_CHANGED = "NOTIFICATION_DISPLAY_CHANGED";

    private DisplayManager displayManager;

    @Override
    protected void onResume() {
        super.onResume();
        displayManager = DisplayManager.create(this);
        displayManager.addListener(this);
        displayManager.setColorDepth(DisplayManager.ColorDepth.HIGH);
        notifyAppInfo();
    }

    @Override
    protected void onPause() {
        super.onPause();
        displayManager.setColorDepth(DisplayManager.ColorDepth.LOW);
        displayManager.release();
        displayManager = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_UP:
                return onUpKeyDown();
            case ScalarInput.ISV_KEY_DOWN:
                return onDownKeyDown();
            case ScalarInput.ISV_KEY_LEFT:
                return onLeftKeyDown();
            case ScalarInput.ISV_KEY_RIGHT:
                return onRightKeyDown();
            case ScalarInput.ISV_KEY_ENTER:
                return onEnterKeyDown();
            case ScalarInput.ISV_KEY_S2:
                return onShutterKeyDown();
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
                return onMenuKeyDown();
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                return true;
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
                return onDial(1);
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                return onDial(-1);
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (event.getScanCode()) {
            case ScalarInput.ISV_KEY_DELETE:
            case ScalarInput.ISV_KEY_SK2:
                return onDeleteKeyUp();
            case ScalarInput.ISV_KEY_UP:
            case ScalarInput.ISV_KEY_DOWN:
            case ScalarInput.ISV_KEY_LEFT:
            case ScalarInput.ISV_KEY_RIGHT:
            case ScalarInput.ISV_KEY_ENTER:
            case ScalarInput.ISV_KEY_S2:
            case ScalarInput.ISV_KEY_MENU:
            case ScalarInput.ISV_KEY_SK1:
            case ScalarInput.ISV_DIAL_1_CLOCKWISE:
            case ScalarInput.ISV_DIAL_2_CLOCKWISE:
            case ScalarInput.ISV_DIAL_1_COUNTERCW:
            case ScalarInput.ISV_DIAL_2_COUNTERCW:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    protected boolean onUpKeyDown() { return false; }
    protected boolean onDownKeyDown() { return false; }
    protected boolean onLeftKeyDown() { return false; }
    protected boolean onRightKeyDown() { return false; }
    protected boolean onEnterKeyDown() { return false; }
    protected boolean onShutterKeyDown() { return false; }
    protected boolean onMenuKeyDown() { return false; }
    protected boolean onDial(int direction) { return false; }

    /** The trash button acts as "back". */
    protected boolean onDeleteKeyUp() {
        onBackPressed();
        return true;
    }

    @Override
    public void displayChanged(DisplayManager.Display display) {
        AppNotificationManager.getInstance().notify(NOTIFICATION_DISPLAY_CHANGED);
    }

    /** Keeps the camera awake while we talk to the printer. */
    protected void setAutoPowerOffMode(boolean enable) {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.apo");
        intent.putExtra("apo_info", enable ? "APO/NORMAL" : "APO/NO");
        sendBroadcast(intent);
    }

    protected void notifyAppInfo() {
        Intent intent = new Intent("com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        sendBroadcast(intent);
    }
}

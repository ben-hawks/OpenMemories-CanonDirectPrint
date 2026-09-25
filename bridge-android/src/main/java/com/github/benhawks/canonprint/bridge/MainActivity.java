package com.github.benhawks.canonprint.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick the paired printer and start/stop the bridge service. */
public class MainActivity extends Activity implements BridgeState.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS = "bridge";
    private static final String PREF_ADDRESS = "address";

    private Spinner printerSpinner;
    private TextView noPrinters;
    private Button toggle;
    private TextView status;
    private TextView logView;
    private ScrollView logScroll;
    private final List<String[]> printers = new ArrayList<>(); // {address, name}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        printerSpinner = findViewById(R.id.printer);
        noPrinters = findViewById(R.id.noPrinters);
        toggle = findViewById(R.id.toggle);
        status = findViewById(R.id.status);
        logView = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);

        toggle.setOnClickListener(v -> onToggle());
        findViewById(R.id.bluetoothSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        findViewById(R.id.hotspotSettings).setOnClickListener(v -> openHotspotSettings());

        if (!hasPermissions())
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BridgeState.get().addListener(this);
        loadPrinters();
        onStateChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BridgeState.get().removeListener(this);
    }

    private static String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31)
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33)
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            // Notifications are nice to have; only Bluetooth is essential.
            if (p.equals(Manifest.permission.POST_NOTIFICATIONS))
                continue;
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        loadPrinters();
    }

    @SuppressLint("MissingPermission") // checked by hasPermissions()
    private void loadPrinters() {
        printers.clear();
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter != null && hasPermissions()) {
            List<String[]> others = new ArrayList<>();
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName() != null ? d.getName() : d.getAddress();
                String[] entry = { d.getAddress(), name };
                // Ivy 2 printers call themselves "Canon (xx:xx) Mini Printer"; list them first.
                if (name.toLowerCase(Locale.US).contains("printer"))
                    printers.add(entry);
                else
                    others.add(entry);
            }
            printers.addAll(others);
        }
        List<String> labels = new ArrayList<>();
        for (String[] p : printers)
            labels.add(p[1] + "  (" + p[0] + ")");
        ArrayAdapter<String> adapterView = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapterView.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        printerSpinner.setAdapter(adapterView);
        noPrinters.setVisibility(printers.isEmpty() ? View.VISIBLE : View.GONE);

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ADDRESS, null);
        for (int i = 0; i < printers.size(); i++)
            if (printers.get(i)[0].equals(saved))
                printerSpinner.setSelection(i);
    }

    private void onToggle() {
        if (BridgeState.get().isRunning()) {
            stopService(new Intent(this, BridgeService.class));
            return;
        }
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
            return;
        }
        int index = printerSpinner.getSelectedItemPosition();
        if (index < 0 || index >= printers.size())
            return;
        String[] printer = printers.get(index);
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        editor.putString(PREF_ADDRESS, printer[0]).apply();

        Intent intent = new Intent(this, BridgeService.class)
                .putExtra(BridgeService.EXTRA_ADDRESS, printer[0])
                .putExtra(BridgeService.EXTRA_NAME, printer[1]);
        if (Build.VERSION.SDK_INT >= 26)
            startForegroundService(intent);
        else
            startService(intent);
    }

    private void openHotspotSettings() {
        Intent tether = new Intent(Intent.ACTION_MAIN);
        tether.setClassName("com.android.settings", "com.android.settings.TetherSettings");
        try {
            startActivity(tether);
        } catch (ActivityNotFoundException | SecurityException e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    @Override
    public void onStateChanged() {
        BridgeState s = BridgeState.get();
        boolean running = s.isRunning();
        toggle.setText(running ? R.string.stop : R.string.start);
        printerSpinner.setEnabled(!running);
        status.setText(s.getStatus());
        logView.setText(s.getLog());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}

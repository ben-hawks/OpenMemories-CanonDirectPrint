package com.github.benhawks.ivy2print.app;

import android.os.Bundle;
import android.widget.TextView;

import com.github.benhawks.ivy2print.R;
import com.github.benhawks.ivy2print.ivy2.Ivy2Printer;
import com.github.benhawks.ivy2print.ivy2.Ivy2Protocol;

/**
 * Printer status and settings.
 * Enter refreshes, Left/Right change the printer's auto power off time.
 */
public class PrinterActivity extends BaseActivity {
    private TextView infoView;
    private TextView statusView;
    private AppSettings settings;
    private volatile boolean busy;
    private int autoPowerOff = -1;

    private interface PrinterAction {
        String run(Ivy2Printer printer, PrinterConnector connector) throws Exception;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.printer);
        infoView = (TextView) findViewById(R.id.info);
        statusView = (TextView) findViewById(R.id.status);
        settings = AppSettings.load(this);
        refresh();
    }

    private void refresh() {
        run(new PrinterAction() {
            @Override
            public String run(Ivy2Printer printer, PrinterConnector connector) throws Exception {
                return describe(printer);
            }
        });
    }

    private String describe(Ivy2Printer printer) throws Exception {
        Ivy2Protocol.Status status = printer.getStatus();
        Ivy2Protocol.Settings s = printer.getSettings();
        autoPowerOff = s.autoPowerOff;
        StringBuilder sb = new StringBuilder();
        sb.append("Bridge: ").append(settings.lastBridgeHost).append(':').append(settings.lastBridgePort).append('\n');
        sb.append("Battery level: ").append(status.batteryLevel).append(" / 63")
                .append(status.usbConnected ? " (charging)" : "").append('\n');
        sb.append("Paper: ").append(status.noPaper ? "EMPTY" : "OK")
                .append(status.coverOpen ? ", cover OPEN" : "")
                .append(status.wrongSmartSheet ? ", wrong Smart Sheet" : "").append('\n');
        if (status.errorCode != 0)
            sb.append("Error code: ").append(status.errorCode).append('\n');
        sb.append("Firmware: ").append(s.firmwareVersion).append('\n');
        sb.append("Photos printed: ").append(s.photosPrinted).append('\n');
        sb.append("Auto power off: ◀ ").append(s.autoPowerOff).append(" min ▶\n");
        return sb.toString();
    }

    private void changeAutoPowerOff(int direction) {
        if (autoPowerOff < 0)
            return;
        int[] values = Ivy2Protocol.AUTO_POWER_OFF_VALUES;
        int index = 0;
        for (int i = 0; i < values.length; i++)
            if (values[i] == autoPowerOff)
                index = i;
        final int value = values[Math.max(0, Math.min(values.length - 1, index + direction))];
        if (value == autoPowerOff)
            return;
        run(new PrinterAction() {
            @Override
            public String run(Ivy2Printer printer, PrinterConnector connector) throws Exception {
                printer.setAutoPowerOff(value);
                return describe(printer);
            }
        });
    }

    private void run(final PrinterAction action) {
        if (busy)
            return;
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String info = null;
                String status;
                try {
                    PrinterConnector connector = new PrinterConnector(PrinterActivity.this, settings, new PrinterConnector.StatusListener() {
                        @Override
                        public void onStatus(String message) {
                            post(null, message);
                        }
                    });
                    Ivy2Printer printer = connector.connect();
                    try {
                        info = action.run(printer, connector);
                    } finally {
                        printer.close();
                    }
                    status = getString(R.string.hint_printer);
                } catch (Exception e) {
                    Logger.error("Printer action failed", e);
                    status = "Error: " + e.getMessage();
                }
                busy = false;
                post(info, status);
            }
        }).start();
    }

    private void post(final String info, final String status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (info != null)
                    infoView.setText(info);
                if (status != null)
                    statusView.setText(status);
            }
        });
    }

    @Override
    protected boolean onEnterKeyDown() {
        refresh();
        return true;
    }

    @Override
    protected boolean onLeftKeyDown() {
        changeAutoPowerOff(-1);
        return true;
    }

    @Override
    protected boolean onRightKeyDown() {
        changeAutoPowerOff(1);
        return true;
    }
}

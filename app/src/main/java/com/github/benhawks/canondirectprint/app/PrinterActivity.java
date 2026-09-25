package com.github.benhawks.canondirectprint.app;

import android.os.Bundle;
import android.widget.TextView;

import com.github.benhawks.canondirectprint.R;
import com.github.benhawks.canondirectprint.ivy2.Ivy2Protocol;

/**
 * Printer status and settings.
 * Up/Down switch the printer type, Enter refreshes,
 * Left/Right change the Ivy 2's auto power off time.
 */
public class PrinterActivity extends BaseActivity {
    private TextView titleView;
    private TextView infoView;
    private TextView statusView;
    private AppSettings settings;
    private volatile boolean busy;
    private PrintBackend backend;

    private interface Action {
        String run(PrintBackend backend, PrintBackend.Listener listener) throws Exception;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.printer);
        titleView = (TextView) findViewById(R.id.title);
        infoView = (TextView) findViewById(R.id.info);
        statusView = (TextView) findViewById(R.id.status);
        settings = AppSettings.load(this);
        refresh();
    }

    private void refresh() {
        backend = PrintBackend.create(this, settings);
        titleView.setText("▲▼ " + settings.printerType.label);
        infoView.setText("");
        run(new Action() {
            @Override
            public String run(PrintBackend backend, PrintBackend.Listener listener) throws Exception {
                return backend.describe(listener);
            }
        });
    }

    private void switchPrinter() {
        if (busy)
            return;
        settings.printerType = settings.printerType.next();
        settings.save(this);
        refresh();
    }

    private void changeAutoPowerOff(int direction) {
        if (!(backend instanceof Ivy2Backend))
            return;
        final Ivy2Backend ivy2 = (Ivy2Backend) backend;
        if (ivy2.autoPowerOff < 0)
            return;
        int[] values = Ivy2Protocol.AUTO_POWER_OFF_VALUES;
        int index = 0;
        for (int i = 0; i < values.length; i++)
            if (values[i] == ivy2.autoPowerOff)
                index = i;
        final int value = values[Math.max(0, Math.min(values.length - 1, index + direction))];
        if (value == ivy2.autoPowerOff)
            return;
        run(new Action() {
            @Override
            public String run(PrintBackend backend, PrintBackend.Listener listener) throws Exception {
                return ivy2.setAutoPowerOff(value, listener);
            }
        });
    }

    private void run(final Action action) {
        if (busy)
            return;
        busy = true;
        final PrintBackend b = backend;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String info = null;
                String status;
                try {
                    info = action.run(b, new PrintBackend.Listener() {
                        @Override
                        public void onStatus(String message) {
                            post(null, message);
                        }

                        @Override
                        public void onProgress(int value, int max) {}
                    });
                    status = getString(b instanceof Ivy2Backend ? R.string.hint_printer : R.string.hint_printer_selphy);
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
    protected boolean onUpKeyDown() {
        switchPrinter();
        return true;
    }

    @Override
    protected boolean onDownKeyDown() {
        switchPrinter();
        return true;
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backend != null)
            backend.cancel();
    }
}

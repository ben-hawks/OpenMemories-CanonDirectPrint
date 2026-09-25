package com.github.benhawks.ivy2print.app;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.benhawks.ivy2print.R;
import com.github.benhawks.ivy2print.image.PrintLayout;
import com.github.benhawks.ivy2print.image.PrintRenderer;
import com.github.benhawks.ivy2print.ivy2.Ivy2Printer;
import com.github.ma1co.openmemories.framework.ImageInfo;
import com.github.ma1co.openmemories.framework.MediaManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shows a preview of the print and sends it to the printer.
 *
 * Up/Down select an option, Left/Right (or a dial) change it,
 * Enter or the shutter button prints, the trash button goes back / cancels.
 */
public class PrintActivity extends BaseActivity {
    public static final String EXTRA_IMAGE_ID = "imageId";

    private static final int PREVIEW_WIDTH = 240;
    private static final int PREVIEW_HEIGHT = 360;
    private static final int MAX_COPIES = 10;
    /** A print takes roughly this long; wait before sending the next copy. */
    private static final int SECONDS_BETWEEN_COPIES = 60;

    private static final int OPTION_LAYOUT = 0;
    private static final int OPTION_ROTATE = 1;
    private static final int OPTION_COPIES = 2;
    private static final int OPTION_COUNT = 3;

    private ScalingBitmapView previewView;
    private TextView titleView;
    private TextView optionsView;
    private TextView statusView;
    private TextView hintView;
    private ProgressBar progressBar;

    private AppSettings settings;
    private int selectedOption = 0;
    private int copies = 1;

    private ImageInfo imageInfo;
    private int exifDegrees;
    private Bitmap source;
    private Bitmap preview;

    private volatile Thread job;
    private volatile Ivy2Printer activePrinter;
    private volatile boolean cancelled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.print);
        previewView = (ScalingBitmapView) findViewById(R.id.preview);
        titleView = (TextView) findViewById(R.id.title);
        optionsView = (TextView) findViewById(R.id.options);
        statusView = (TextView) findViewById(R.id.status);
        hintView = (TextView) findViewById(R.id.hint);
        progressBar = (ProgressBar) findViewById(R.id.progress);

        settings = AppSettings.load(this);

        long id = getIntent().getLongExtra(EXTRA_IMAGE_ID, -1);
        imageInfo = MediaManager.create(this).getImageInfo(id);
        exifDegrees = PrintRenderer.exifDegrees(imageInfo.getOrientation());
        titleView.setText(imageInfo.getFilename());
        updateOptions();
        setStatus("Loading photo...");
        hintView.setText(R.string.hint_idle);
        loadSource();
    }

    /** Decodes the camera's built-in preview JPEG (large enough for the print) in the background. */
    private void loadSource() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // On the camera the "preview" is the embedded 1616px screennail;
                // on other devices it is the full image and gets subsampled.
                PrintRenderer.StreamSource stream = new PrintRenderer.StreamSource() {
                    @Override
                    public InputStream open() throws IOException {
                        InputStream in = imageInfo.getPreviewImage();
                        if (in == null)
                            throw new IOException("Cannot open image");
                        return in;
                    }
                };
                try {
                    int[] size = PrintRenderer.decodeBounds(stream);
                    int sample = Integer.MAX_VALUE;
                    for (PrintLayout.Mode mode : PrintLayout.Mode.values()) {
                        for (boolean rotate : new boolean[] { false, true }) {
                            int rotation = PrintLayout.rotation(size[0], size[1], exifDegrees, rotate);
                            sample = Math.min(sample, PrintLayout.sampleSize(size[0], size[1], rotation, mode));
                        }
                    }
                    final Bitmap bitmap = PrintRenderer.decode(stream, sample);
                    Logger.info("Loaded " + imageInfo.getFilename() + " " + size[0] + "x" + size[1]
                            + " sample=" + sample + " -> " + bitmap.getWidth() + "x" + bitmap.getHeight());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) {
                                bitmap.recycle();
                                return;
                            }
                            source = bitmap;
                            updatePreview();
                            setStatus(getString(R.string.status_ready));
                        }
                    });
                } catch (final Throwable e) {
                    Logger.error("Loading image failed", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Cannot load photo: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void updatePreview() {
        if (source == null)
            return;
        Bitmap old = preview;
        preview = PrintRenderer.renderPreview(source, exifDegrees, settings.autoRotate, settings.mode, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        previewView.setImageBitmap(preview);
        if (old != null)
            old.recycle();
    }

    private void updateOptions() {
        String[] lines = {
                "Layout: " + (settings.mode == PrintLayout.Mode.FILL ? "Fill (borderless)" : "Fit (white border)"),
                "Rotate to fit: " + (settings.autoRotate ? "On" : "Off"),
                "Copies: " + copies,
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++)
            sb.append(i == selectedOption ? "▶ " : "    ").append(lines[i]).append('\n');
        optionsView.setText(sb.toString());
    }

    private void changeOption(int direction) {
        switch (selectedOption) {
            case OPTION_LAYOUT:
                settings.mode = settings.mode == PrintLayout.Mode.FILL ? PrintLayout.Mode.FIT : PrintLayout.Mode.FILL;
                break;
            case OPTION_ROTATE:
                settings.autoRotate = !settings.autoRotate;
                break;
            case OPTION_COPIES:
                copies = Math.max(1, Math.min(MAX_COPIES, copies + direction));
                break;
        }
        settings.save(this);
        updateOptions();
        updatePreview();
    }

    private boolean isBusy() {
        return job != null;
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void postStatus(final String text) {
        Logger.info(text);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                setStatus(text);
            }
        });
    }

    private void postProgress(final int value, final int max) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                progressBar.setVisibility(max > 0 ? View.VISIBLE : View.INVISIBLE);
                progressBar.setMax(Math.max(1, max));
                progressBar.setProgress(value);
            }
        });
    }

    private void startPrint() {
        if (isBusy() || source == null)
            return;
        cancelled = false;
        hintView.setText(R.string.hint_busy);
        setAutoPowerOffMode(false);

        final Bitmap src = source;
        final PrintLayout.Mode mode = settings.mode;
        final boolean autoRotate = settings.autoRotate;
        final int copyCount = copies;
        job = new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    postStatus("Preparing image...");
                    byte[] jpeg = PrintRenderer.renderForPrinter(src, exifDegrees, autoRotate, mode, settings.jpegQuality);
                    Logger.info("Rendered " + jpeg.length + " byte JPEG");
                    for (int copy = 1; copy <= copyCount; copy++) {
                        if (copy > 1)
                            waitBetweenCopies();
                        printOnce(jpeg, copy, copyCount);
                    }
                    result = copyCount == 1 ? getString(R.string.status_sent) : "All " + copyCount + " copies sent.";
                } catch (Throwable e) {
                    Logger.error("Print failed", e);
                    result = cancelled ? "Cancelled." : "Error: " + e.getMessage();
                }
                final String message = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        job = null;
                        activePrinter = null;
                        setAutoPowerOffMode(true);
                        progressBar.setVisibility(View.INVISIBLE);
                        setStatus(message);
                        hintView.setText(R.string.hint_idle);
                    }
                });
            }
        });
        job.start();
    }

    private void printOnce(byte[] jpeg, final int copy, final int copyCount) throws IOException {
        final String prefix = copyCount > 1 ? "[" + copy + "/" + copyCount + "] " : "";
        PrinterConnector connector = new PrinterConnector(this, settings, new PrinterConnector.StatusListener() {
            @Override
            public void onStatus(String message) {
                postStatus(prefix + message);
            }
        });
        Ivy2Printer printer = connector.connect();
        activePrinter = printer;
        try {
            if (cancelled)
                throw new IOException("Cancelled");
            postStatus(prefix + "Sending photo (printer battery level " + connector.sessionInfo.batteryLevel + ")...");
            printer.print(jpeg, new Ivy2Printer.ProgressListener() {
                @Override
                public void onTransferProgress(int sent, int total) {
                    postProgress(sent, total);
                }
            });
            postProgress(0, 0);
        } finally {
            activePrinter = null;
            printer.close();
        }
    }

    private void waitBetweenCopies() throws IOException {
        for (int s = SECONDS_BETWEEN_COPIES; s > 0; s--) {
            if (cancelled)
                throw new IOException("Cancelled");
            postStatus("Printing... next copy in " + s + " s");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new IOException("Cancelled");
            }
        }
    }

    private void cancel() {
        cancelled = true;
        Ivy2Printer printer = activePrinter;
        if (printer != null) {
            printer.cancel();
            printer.close();
        }
        Thread t = job;
        if (t != null)
            t.interrupt();
        setStatus("Cancelling...");
    }

    @Override
    protected boolean onUpKeyDown() {
        if (!isBusy()) {
            selectedOption = (selectedOption + OPTION_COUNT - 1) % OPTION_COUNT;
            updateOptions();
        }
        return true;
    }

    @Override
    protected boolean onDownKeyDown() {
        if (!isBusy()) {
            selectedOption = (selectedOption + 1) % OPTION_COUNT;
            updateOptions();
        }
        return true;
    }

    @Override
    protected boolean onLeftKeyDown() {
        if (!isBusy())
            changeOption(-1);
        return true;
    }

    @Override
    protected boolean onRightKeyDown() {
        if (!isBusy())
            changeOption(1);
        return true;
    }

    @Override
    protected boolean onDial(int direction) {
        if (!isBusy())
            changeOption(direction);
        return true;
    }

    @Override
    protected boolean onEnterKeyDown() {
        startPrint();
        return true;
    }

    @Override
    protected boolean onShutterKeyDown() {
        startPrint();
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        if (isBusy())
            cancel();
        else
            onBackPressed();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (isBusy())
            cancel();
        else
            super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        boolean busy = isBusy();
        if (busy)
            cancel();
        previewView.setImageBitmap(null);
        if (preview != null)
            preview.recycle();
        // A running job may still be reading the source; leave it to the GC then.
        if (source != null && !busy)
            source.recycle();
    }
}

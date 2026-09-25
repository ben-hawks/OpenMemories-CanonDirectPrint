package com.github.benhawks.ivy2print.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

import com.github.benhawks.ivy2print.R;
import com.github.benhawks.ivy2print.image.PrintRenderer;
import com.github.benhawks.ivy2print.ivy2.Ivy2Log;
import com.github.ma1co.openmemories.framework.ImageInfo;
import com.github.ma1co.openmemories.framework.MediaManager;

import java.text.SimpleDateFormat;
import java.util.Locale;

/** Lists the photos on the memory card, newest first. Select one to print it. */
public class MainActivity extends BaseActivity implements AdapterView.OnItemClickListener {
    private MediaManager mediaManager;
    private Cursor cursor;
    private ListView listView;
    private int headerCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Logger.error("Uncaught exception", throwable);
                System.exit(0);
            }
        });
        Ivy2Log.setSink(new Ivy2Log.Sink() {
            @Override
            public void log(String message) {
                Logger.info(message);
            }
        });
        Logger.info("Started");

        // Start joining the bridge's network while the user picks a photo.
        new WifiHelper(this).setEnabled(true);

        listView = (ListView) findViewById(R.id.listView);
        LayoutInflater inflater = getLayoutInflater();
        addHeader(inflater, R.string.menu_printer, R.string.menu_printer_sub);
        addHeader(inflater, R.string.menu_wifi, R.string.menu_wifi_sub);

        mediaManager = MediaManager.create(this);
        cursor = mediaManager.queryNewestImages();
        listView.setAdapter(new ResourceCursorAdapter(this, R.layout.image_list_item, cursor) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                ImageInfo info = mediaManager.getImageInfo(cursor);
                String date = info.getDate() != null ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(info.getDate()) : "";
                ((TextView) view.findViewById(android.R.id.text1)).setText(info.getFolder() + "/" + info.getFilename());
                ((TextView) view.findViewById(android.R.id.text2)).setText(date + "  " + info.getWidth() + "x" + info.getHeight());
                Bitmap thumbnail = null;
                try {
                    thumbnail = rotate(BitmapFactory.decodeStream(info.getThumbnail()),
                            PrintRenderer.exifDegrees(info.getOrientation()));
                } catch (Exception e) {
                    Logger.error("Thumbnail failed", e);
                }
                ((ScalingBitmapView) view.findViewById(R.id.imageView)).setImageBitmap(thumbnail);
            }
        });
        listView.setOnItemClickListener(this);
    }

    private void addHeader(LayoutInflater inflater, int title, int subtitle) {
        View header = inflater.inflate(android.R.layout.simple_list_item_2, listView, false);
        ((TextView) header.findViewById(android.R.id.text1)).setText(title);
        ((TextView) header.findViewById(android.R.id.text2)).setText(subtitle);
        listView.addHeaderView(header);
        headerCount++;
    }

    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        if (bitmap == null || degrees == 0)
            return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        if (position == 0) {
            startActivity(new Intent(this, PrinterActivity.class));
        } else if (position == 1) {
            startActivity(new Intent(this, WifiSettingActivity.class));
        } else if (position >= headerCount) {
            Cursor c = (Cursor) adapterView.getItemAtPosition(position);
            Intent intent = new Intent(this, PrintActivity.class);
            intent.putExtra(PrintActivity.EXTRA_IMAGE_ID, mediaManager.getImageId(c));
            startActivity(intent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cursor != null)
            cursor.close();
        new WifiHelper(this).setEnabled(false);
        Logger.info("Stopped");
    }
}

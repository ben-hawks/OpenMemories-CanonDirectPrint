package com.github.benhawks.canondirectprint.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.github.ma1co.openmemories.framework.DisplayManager;

/**
 * ImageView that compensates for the camera's non-square framebuffer pixels,
 * so previews are shown with the correct aspect ratio on the LCD and EVF.
 * From PMCADemo (MIT, https://github.com/ma1co/PMCADemo).
 */
public class ScalingBitmapView extends ImageView implements AppNotificationManager.NotificationListener {
    private static class ScaledBitmapDrawable extends BitmapDrawable {
        private final float xScale;

        ScaledBitmapDrawable(Bitmap bitmap, float xScale) {
            super(bitmap);
            this.xScale = xScale;
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(super.getIntrinsicWidth() * xScale);
        }
    }

    private Bitmap bitmap;

    public ScalingBitmapView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppNotificationManager.getInstance().addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        AppNotificationManager.getInstance().removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void setImageBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        update();
    }

    @Override
    public void onNotify(String message) {
        if (message.equals(BaseActivity.NOTIFICATION_DISPLAY_CHANGED))
            update();
    }

    private void update() {
        if (bitmap == null) {
            setImageDrawable(null);
            return;
        }
        DisplayManager displayManager = DisplayManager.create(getContext());
        float displayAspect = displayManager.getActiveDisplayInfo().aspectRatio;
        float frameBufferAspect = displayManager.getFrameBufferInfo().aspectRatio;
        displayManager.release();

        setImageDrawable(new ScaledBitmapDrawable(bitmap, frameBufferAspect / displayAspect));
    }
}

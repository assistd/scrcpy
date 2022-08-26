package com.genymobile.scrcpy.udt;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.ScreenInfo;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScreenCapture implements Device.RotationListener {
    public interface OnImageAvailableListener {
        void onImageAvailable(byte[] bitmap, int size);
    }

    private static final String TAG = "capture,";
    private static final boolean ENCODE_FROM_JPEG_TURBO = true;

    private int height, quality;
    private Options options;

    private Handler backgroundHandler;
    private IBinder display;
    private JpgEncoder encoder;

    private final List<OnImageAvailableListener> listeners = new ArrayList<>();

    private static final ScreenCapture sScreenCapture = new ScreenCapture();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean rotationChanged = new AtomicBoolean();
    private boolean imageReaderReady = false;
    private JpgEncoder.JpgData data;

    public static ScreenCapture getInstance() {
        return sScreenCapture;
    }

    ScreenCapture() {
        UdtLn.i("init ScreenCapture once");
    }

    private void startLoopThread() {
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                HandlerThread backgroundThread =
                        new HandlerThread("udt-cap", android.os.Process
                                .THREAD_PRIORITY_BACKGROUND);
                backgroundThread.start();
                backgroundHandler = new Handler(backgroundThread.getLooper());
                prepare();
                Looper.loop();
            }
        }.start();
    }

    @Override
    public void onRotationChanged(int rotation) {
        rotationChanged.set(true);
    }

    public boolean consumeRotationChange() {
        return rotationChanged.getAndSet(false);
    }

    public synchronized void setConfig(int height, int quality, Options options) {
        if (this.height == 0) {
            UdtLn.i(TAG + " init capture config, height = " + height + " quality =" +quality);
            this.height = height;
            this.quality = quality;
            this.options = options;
            running.set(true);
            startLoopThread();
            notify();
        } if (this.height != height || this.quality != quality) {
            UdtLn.i(TAG + " update capture config,"
                    + " new height = " + height + " old height = " + this.height
                    + ", new quality =" +quality + " old quality =" + this.quality
            );
            this.height = height;
            this.quality = quality;
            this.options = options;
            rotationChanged.set(true);
        } else {
            UdtLn.d(TAG + " no need update capture config, height = " + height + " quality =" +quality);
        }
    }

    public synchronized void addListener(OnImageAvailableListener listener) {
        UdtLn.i(TAG + " add capture listener = " + listener + ", imageReaderReady = " + imageReaderReady);
        listeners.add(listener);
        if (imageReaderReady) {
            notify();
        }
    }

    public synchronized void rmListener(OnImageAvailableListener listener) {
        UdtLn.i(TAG + " rm capture listener = " + listener);
        listeners.remove(listener);
        notify();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void prepare() {
        Workarounds.prepareMainLooper();
        UdtLn.i(TAG + " init capture device");
        do {
            synchronized (this) {
                while (!running.get()) {
                    try {
                         wait(2000);
                         UdtLn.d(TAG + " wait thread start cmd");
                     } catch (Exception e) {}
                }
            }
            options.setMaxSize(height);
            Device device = new Device(options);
            device.setRotationListener(this);

            display = createDisplay();
            ScreenInfo screenInfo = device.getScreenInfo();
            final int width = screenInfo.getVideoSize().getWidth();
            final int height = screenInfo.getVideoSize().getHeight();
            Rect contentRect = screenInfo.getContentRect();
            // does not include the locked video orientation
            Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
            int videoRotation = screenInfo.getVideoRotation();
            int layerStack = device.getLayerStack();
            ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

            UdtLn.i(TAG + " init image reader by config:"
                    + " contentRect" + contentRect.toString()
                    + " videoW: " + width + " videoH: " + height
                    + " ori: " + videoRotation);

            setDisplaySurface(display, imageReader.getSurface(), videoRotation, contentRect, unlockedVideoRect, layerStack);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    synchronized (ScreenCapture.this) {
                        UdtLn.i(TAG + " image reader is ready");
                        imageReaderReady = true;
                        ScreenCapture.this.notify();
                    }
                }
            }, backgroundHandler);
            UdtLn.i(TAG + " start loop to capture");

            try {
                handleImage(imageReader);
            } catch (Exception e) {
            } finally {
                imageReaderReady = false;
                data = null;
                imageReader.close();
                UdtLn.i(TAG + " destroy capture display");
                destroyDisplay();
            }
        } while (running.get());
        UdtLn.i(TAG + " finish capture device");
    }

    private void handleImage(ImageReader reader) {
        UdtLn.i(TAG + " handle image start");
        do {
            synchronized (this) {
                if (listeners.size() == 0 || !imageReaderReady) {
                    try {
                        wait(10000);
                        UdtLn.i(TAG + " no listerner or image reader not ready, recheck");
                        continue;
                    } catch (Exception ignored) { }
                 }
             }
            UdtLn.i(TAG + " acquire Latest Image for listener: " + listeners.size());
            Image image = reader.acquireLatestImage();
            if (image != null) {
                if (ENCODE_FROM_JPEG_TURBO) {
                    data = getJpegFromEncoder(image, quality);
                } else {
                    data = getJpegFromBitmap(image, quality);
                }
                image.close();
            } else {
                // use last jpeg data.
            }

            synchronized (this) {
                for (OnImageAvailableListener l : listeners) {
                    if (data != null) {
                        l.onImageAvailable(data.data, data.size);
                    } else {
                        l.onImageAvailable(null, 0);
                    }
                    listeners.remove(l);
                }
            }
        } while (!consumeRotationChange() && running.get()); // check 2s
        UdtLn.i(TAG + " exit of cap thread");
    }

    protected static IBinder createDisplay() {
        boolean secure = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
                && !Build.VERSION.CODENAME.equals("S");
        return SurfaceControl.createDisplay("udt-screencap", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, int orientation,
                                          Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, orientation, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    public void destroyDisplay() {
        try {
            if (display != null) {
                SurfaceControl.destroyDisplay(display);
                display = null;
            }
        } catch (Exception e) {
            UdtLn.e(TAG + " destroy display error:" + e);
        }
    }

    private JpgEncoder.JpgData getJpegFromEncoder(Image image, int quality) {
        try {
            if (encoder == null) {
                encoder = new JpgEncoder();
            }
            encoder.allocate(image.getWidth(), image.getHeight());
            return encoder.encode(image, quality);
        } catch (Exception e) {
            UdtLn.e(" encode jpeg by turbo error: " + e);
        }
        return null;
    }

    // ScreenCapture run singleton, no need call free.
    public void free() {
        if (encoder != null) {
            try {
                encoder.free();
                encoder = null;
            } catch (Exception e) {
                UdtLn.e("free jpg encoder error: " + e);
            }
        }
    }

    private Bitmap createBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride,
                height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, width, height);
    }

    private JpgEncoder.JpgData getJpegFromBitmap(Image image, int quality) {
        try {
            Bitmap bitmap = createBitmap(image, image.getWidth(), image.getHeight());
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
            byteArrayOutputStream.flush();
            byteArrayOutputStream.close();
            byte[] data = byteArrayOutputStream.toByteArray();
            return new JpgEncoder.JpgData(data, data.length);
        } catch (Exception e) {
            UdtLn.e(" get image from bitmap error: " + e);
        }
        return null;
    }
}

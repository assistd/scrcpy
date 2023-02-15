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
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.ScreenInfo;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public final class ScreenCapture {
    public interface OnImageAvailableListener {
        void onImageAvailable(byte[] bitmap, int size);
    }

    private static class LoopThread extends Thread {
        WeakReference<ScreenCapture> ScreenCaptureRef;
        int fd;

        LoopThread(ScreenCapture c, int fd) {
            ScreenCaptureRef = new WeakReference<>(c);
            this.fd = fd;
        }

        @Override
        public void run() {
            ScreenCapture capture = ScreenCaptureRef.get();
            if (capture == null) {
                return;
            }

            Looper.prepare();
            String loopName = "udt-cap-" + System.currentTimeMillis();
            capture.backgroundThread =
                    new HandlerThread(loopName, android.os.Process.THREAD_PRIORITY_BACKGROUND);
            capture.backgroundThread.start();
            if (capture.backgroundHandler == null) {
                capture.backgroundHandler = new Handler(capture.backgroundThread.getLooper());
            }
            capture.handlerLooper = Looper.myLooper();
            Looper.loop();
            UdtLn.i("exit of capture looper: " + loopName);
        }
    }

    private static final String TAG = "screencap:";
    private static final boolean ENCODE_FROM_JPEG_TURBO = true;

    private int fd;

    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private LoopThread loopThread;
    private Looper handlerLooper;
    private JpgEncoder.JpgData lastData;

    private static ScreenCapture sScreenCapture;

    public ScreenCapture(int fd) {
        UdtLn.i("init ScreenCapture once");
        startLoopThread();
        this.fd = fd;
    }

    private void startLoopThread() {
        if (loopThread == null) {
            loopThread = new LoopThread(this, fd);
            loopThread.start();
        }
    }

    public void stop() {
        if (handlerLooper != null) {
            UdtLn.i("Looper.myLooper().quit");
            handlerLooper.quit();
        }
        if (backgroundHandler.getLooper() != null) {
            UdtLn.i("backgroundHandler.myLooper().quit");
            backgroundHandler.getLooper().quit();
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void capture(int height, int quality, Options options, OnImageAvailableListener listener) {
        options.setMaxSize(height);
        options.setScaleImage(UdtOption.sRescaleImage);

        Device device = new Device(options);
        IBinder display = createDisplay(fd);
        ScreenInfo screenInfo = device.getScreenInfo();
        int w = screenInfo.getVideoSize().getWidth();
        int h = screenInfo.getVideoSize().getHeight();
        Rect contentRect = screenInfo.getContentRect();
        // does not include the locked video orientation
        Rect unlockedVideoRect = screenInfo.getUnlockedVideoSize().toRect();
        int videoRotation = screenInfo.getVideoRotation();
        int layerStack = device.getLayerStack();
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);

        UdtLn.i(TAG + " config:" + " cr" + contentRect.toString()
                + " vw: " + w + " vh: " + h + " o: " + videoRotation);

        setDisplaySurface(display, imageReader.getSurface(), videoRotation, contentRect, unlockedVideoRect, layerStack);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                if (backgroundHandler == null) {
                    UdtLn.e(TAG + "looper has stopped, ignore image");
                    return;
                }

                try (Image image = reader.acquireLatestImage()) {
                    if (image != null) {
                        JpgEncoder.JpgData data;
                        if (ENCODE_FROM_JPEG_TURBO) {
                            data = getJpegFromEncoder(image, quality);
                        } else {
                            data = getJpegFromBitmap(image, quality);
                        }
                        image.close();

                        if (listener != null) {
                            if (data != null) {
                                listener.onImageAvailable(data.data, data.size);
                                if (lastData != null) {
                                    lastData.free();
                                }
                                lastData = data.copy();
                                data.free();
                            } else {
                                if (lastData != null) {
                                    UdtLn.e(TAG + "acquire Latest Image failed, use last image " + lastData.size);
                                    listener.onImageAvailable(lastData.data, lastData.size);
                                } else {
                                    listener.onImageAvailable(new byte[]{1}, 1);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    UdtLn.e(TAG + "acquire Latest Image failed by image by " + e);
                } finally {
                    destroyDisplay(display);
                    reader.close();
                }
            }
        }, backgroundHandler);
    }

    protected static IBinder createDisplay(int fd) {
        boolean secure = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R
                && !Build.VERSION.CODENAME.equals("S");
        return SurfaceControl.createDisplay("udt-screencap-" + (fd++), secure);
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

    private void destroyDisplay(IBinder display) {
        try {
            if (display != null) {
                SurfaceControl.destroyDisplay(display);
            }
        } catch (Exception e) {
            UdtLn.e(TAG + " destroy display error:" + e);
        }
    }

    private JpgEncoder.JpgData getJpegFromEncoder(Image image, int quality) {
        try {
            return JpgEncoder.getInstance().encode(image, quality);
        } catch (Exception e) {
            UdtLn.e(" encode jpeg by turbo error: " + e);
        }
        return null;
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

package com.genymobile.scrcpy.udt;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.LocaleList;
import android.os.RemoteException;
import android.view.IRotationWatcher;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.wrappers.PackageManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import com.genymobile.scrcpy.udt.UdtControllerMessageReader.UdtControlMessage;
import com.genymobile.scrcpy.wrappers.WindowManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

public class UdtController {
    private UdtDevice device;
    private Options options;
    private UdtSender udtSender;
    private DesktopConnection connection;
    private boolean running;

    private long lastTick = 0;
    private long lastTickCheck = 0;
    private long lastTickCount = 0;
    private Thread tickCheckThread;

    private final ServiceManager serviceManager = new ServiceManager();
    private WindowManager windowManager;

    private ScreenCapture screenCapture;

    public UdtController(UdtDevice device, Options options, DesktopConnection connection) {
        UdtLn.i("init udt controller");
        this.device = device;
        this.options = options;
        this.connection = connection;
        this.udtSender = new UdtSender(connection);
        running = true;
        if (UdtOption.sRotationAutoSync) {
            windowManager = serviceManager.newWindowManager();
            windowManager.registerRotationWatcher(new RotationWatcher(this),
                    options.getDisplayId());
        }
    }

    public void stop() {
        UdtLn.i("stop udt controller");
        running = false;
        if (udtSender != null) {
            udtSender.stop();
        }
        windowManager = null;

        if (screenCapture != null) {
            screenCapture.stop();
        }
    }

    public UdtSender getUdtSender() {
        return udtSender;
    }

    public boolean handleEvent(ByteBuffer buffer, byte _type, UdtControllerMessageReader.ParseCallBack parseCallBack) {
        int type = _type;
        UdtLn.d("receiving msg, type:" + String.format("0x%02x", _type) + " pos:" + buffer.position());
        UdtControlMessage udtMsg = UdtControllerMessageReader.parseUdtEvent(buffer, type, parseCallBack);
        if (udtMsg == null) {
            return false;
        }
        switch (udtMsg.getType()) {
            case UdtControlMessage.TYPE_REQ_IDR:
                device.reqIDRFrame();
                return true;
            case UdtControlMessage.TYPE_SET_BITRATE:
                int bitrate = udtMsg.getBitRate();
                device.setBitRate(bitrate);
                return true;
            case UdtControlMessage.TYPE_HEARTBEAT:
                onTick(connection, System.currentTimeMillis());
                return true;
            case UdtControlMessage.TYPE_CAPTURE_DEVICE:
                captureScreen(udtMsg.getCapHeight(), udtMsg.getCapQuality());
                return true;
            case UdtControlMessage.TYPE_PAUSE_VIDEO:
            case UdtControlMessage.TYPE_RESUME_VIDEO:
                device.reqPauseVideo(udtMsg.getType() == UdtControlMessage.TYPE_PAUSE_VIDEO);
                return true;
            case UdtControlMessage.TYPE_SET_LOCALE:
                String newLocal = udtMsg.getNewLocale();
                setLocale(newLocal);
                return true;
            case UdtControlMessage.TYPE_GET_LOCALE:
                getLocale();
                return true;
            case UdtControlMessage.TYPE_GET_APPS:
                getInstalledPackages();
                return true;
            case UdtControlMessage.TYPE_GET_ROTATION:
                getRotation();
                return true;
            default:
                UdtLn.e("unknown udt control msg, type = " + udtMsg.getType());
                return false;
        }
    }

    public void onTick(DesktopConnection connection, long ts) {
        lastTickCount++;
        if (Math.abs(System.currentTimeMillis() - lastTickCheck) > 60 * 1000) {
            UdtLn.d("dump heartbeat from client, total:" + lastTickCount);
            lastTickCheck = System.currentTimeMillis();
        }
        lastTick = ts;
        if (tickCheckThread == null) {
            tickCheckThread = startTickCheckThread(connection);
        }
    }

    private Thread startTickCheckThread(DesktopConnection connection) {
        UdtLn.i("init heartbeat check thread for connect:" + connection);
        Thread thread = new TickCheckThread(this, connection);
        thread.start();
        return thread;
    }

    private void captureScreen(int height, int quality) {
        UdtLn.i("capture screen by height: " + height + ", quality" + quality);
        if (screenCapture == null) {
            screenCapture = new ScreenCapture(connection.getVideoFd().hashCode());
        }
        screenCapture.capture(height, quality, options,
                new ScreenCapture.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(byte[] bitmap, int size) {
                udtSender.pushCaptureImage(bitmap, size);
            }
        });
    }

    private void setLocale(String newLocale) {
        String[] localeInfos = newLocale.split("_");
        if (localeInfos.length == 2) {
            String language = localeInfos[0];
            String country = localeInfos[1];
            Locale l = new Locale(language, country);
            boolean ok = LocaleUtils.changeLanguage(l);
            UdtLn.i("set new locale: " + l + ", res: " + ok);
        } else {
            UdtLn.i("set new locale failed, illegal param: " + newLocale);
        }
    }

    private void getLocale() {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
            locale =  LocaleList.getDefault().get(0);
        } else{
            locale = Locale.getDefault();
        }
        String currentLocale = locale.getLanguage() + "_" + locale.getCountry();
        UdtLn.i("get device locale: " + currentLocale);
        udtSender.pushLocale(currentLocale);
    }

    private void getInstalledPackages() {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = serviceManager.getPackageManager();
        List<PackageInfo> pkgList = pm.getInstalledPackages();
        int N = pkgList.size();
        for (int i = 0; i< N ; i++) {
            PackageInfo info = pkgList.get(i);
            sb.append(info.packageName).append(",")
                    .append(info.versionCode).append(",")
                    .append(info.versionName)
                    .append(";");
        }
        UdtLn.i("get device installed apps: " + sb);
        udtSender.pushInstallApps(sb.toString());
    }

    private void getRotation() {
        int rotation = device.getRotation();
        UdtLn.i("get device rotation: " + rotation);
        udtSender.pushRotation(rotation);
    }

    private static class RotationWatcher extends IRotationWatcher.Stub {
        private final WeakReference<UdtController> udtControllerRef;

        RotationWatcher(UdtController udtController) {
            UdtLn.i("init udt RotationWatcher");
            udtControllerRef = new WeakReference<>(udtController);
        }

        @Override
        public void onRotationChanged(int rotation) throws RemoteException {
            UdtController controller = udtControllerRef.get();
            if (controller == null) {
                return;
            }

            synchronized (RotationWatcher.this) {
                controller.udtSender.pushRotation(rotation);
            }
        }
    }

    private static class TickCheckThread extends Thread {
        WeakReference<DesktopConnection> connectionRef;
        WeakReference<UdtController> ctrlRef;

        TickCheckThread(UdtController ctrl, DesktopConnection connection) {
            UdtLn.i("init udt TickCheckThread");

            this.ctrlRef = new WeakReference<>(ctrl);
            this.connectionRef = new WeakReference<>(connection);
        }

        @Override
        public void run() {
            UdtController ctrl = ctrlRef.get();
            if (ctrl == null) {
                return;
            }

            DesktopConnection connection = connectionRef.get();
            if (connection == null) {
                return;
            }

            while (ctrl.running) {
                //if 1min not heart from client, check connection is ok
                if (Math.abs(System.currentTimeMillis() - ctrl.lastTick) > 60 * 1000) {
                    UdtLn.w("no heartbeat from client, close connection," +
                            " lastTickCount:" +  ctrl.lastTickCount);
                    try {
                        connection.close();
                    } catch (IOException e) {
                        UdtLn.e("close connection error " + e);
                    }
                    return;
                }  else {
                    try {
                        Thread.sleep(5 * 1000);
                    } catch (Exception e) { }
                }
            }
        }
    }
}

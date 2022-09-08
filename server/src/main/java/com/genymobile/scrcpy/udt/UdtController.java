package com.genymobile.scrcpy.udt;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.LocaleList;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.wrappers.PackageManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import com.genymobile.scrcpy.udt.UdtControllerMessageReader.UdtControlMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;

public class UdtController implements ScreenCapture.OnImageAvailableListener {
    private UdtDevice device;
    private Options options;
    private UdtSender udtSender;
    private DesktopConnection connection;
    private boolean snapshotOnce;
    private boolean running;

    private long lastTick = 0;
    private long lastTickCheck = 0;
    private long lastTickCount = 0;
    private Thread tickCheckThread;

    private final ServiceManager serviceManager = new ServiceManager();

    public UdtController(UdtDevice device, Options options, DesktopConnection connection) {
        UdtLn.i("init udt controller");
        this.device = device;
        this.options = options;
        this.connection = connection;
        this.udtSender = new UdtSender(connection);
        running = true;
    }

    public void stop() {
        UdtLn.i("stop udt controller");
        running = false;
        ScreenCapture.getInstance().rmListener(this);
        if (udtSender != null) {
            udtSender.stop();
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
        UdtLn.d("handle udt control msg, type = " + type);
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
                int height = udtMsg.getCapHeight();
                int quality = udtMsg.getCapQuality();
                captureScreen(height, quality);
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
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    //if 1min not heart from client, check connection is ok
                    if (Math.abs(System.currentTimeMillis() - lastTick) > 60 * 1000) {
                        UdtLn.w("no heartbeat from client, close connection," +
                                " lastTickCount:" +  lastTickCount);
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
        });
        thread.start();
        return thread;
    }


    @Override
    public void onImageAvailable(byte[] bitmap, int size) {
        synchronized (this) {
            if (snapshotOnce) {
                UdtLn.i("screencap image available, send size: " + size);
                udtSender.pushCaptureImage(bitmap, size);
                snapshotOnce = false;
                notify();
            }
        }
    }

    private void captureScreen(int height, int quality) {
        UdtLn.i("screencap by height: " + height + ", quality" + quality);
        try {
            ScreenCapture.getInstance().addListener(this);
            ScreenCapture.getInstance().setConfig(height, quality, options);
            snapshotOnce = true;
            int count = 0;
            int maxCount = 50; //5s
            synchronized (this) {
                // wait
                while (snapshotOnce && running) {
                    if (count++ < maxCount) {
                        try {
                            wait(100);
                        } catch (Exception e) {
                        }
                    } else {
                        UdtLn.i("screencap failed by timeout ( >5s )");
                        break;
                    }
                }
                if (count < maxCount) {
                    UdtLn.i("screencap success, send image onImageAvailable");
                }
            }
        } finally {
            ScreenCapture.getInstance().rmListener(this);
        }
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
}

package com.genymobile.scrcpy.udt;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.ScreenInfo;
import com.genymobile.scrcpy.Size;
import com.genymobile.scrcpy.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class UdtSender {
    private final DesktopConnection connection;
    private final UdtDeviceMessageWriter writer;

    private byte[] captureImage;
    private String curLocale;
    private String appLists;
    private int rotation = -1;
    private ScreenInfo screenInfo = null;

    public UdtSender(DesktopConnection connection) {
        this.connection = connection;
        writer = new UdtDeviceMessageWriter();
    }

    public void stop() {
        writer.free();
    }

    public synchronized void pushCaptureImage(byte[] image, int size) {
        if (image != null) {
            captureImage = new byte[size];
            System.arraycopy(image, 0, captureImage, 0, captureImage.length);
            notify();
        }
    }

    public synchronized void pushLocale(String locale) {
        if (locale != null) {
            curLocale = locale;
            notify();
        }
    }

    public synchronized void pushInstallApps(String appList) {
        if (appList != null) {
            appLists = appList;
            notify();
        }
    }

    public synchronized void pushRotation(int rotation) {
        if (rotation >= 0) {
            this.rotation = rotation;
            notify();
        }
    }

    public synchronized void pushScreenInfo(ScreenInfo info) {
        if (info != null) {
            this.screenInfo = info;
            notify();
        }
    }

    public void loop() throws IOException, InterruptedException {
        while (true) {
            byte[] image = null;
            String newLocale = null;
            String apps = null;
            int rotation = -1;
            ScreenInfo info = null;
            synchronized (this) {
                while (captureImage == null
                        && curLocale == null
                        && appLists == null
                        && this.rotation < 0
                        && screenInfo == null
                ) {
                    wait();
                }
                if (captureImage != null && captureImage.length > 0) {
                    image = new byte[captureImage.length];
                    System.arraycopy(captureImage, 0, image, 0, captureImage.length);
                    captureImage = null;
                }
                if (curLocale != null) {
                    newLocale = curLocale;
                    curLocale = null;
                }
                if (appLists != null) {
                    apps = appLists;
                    appLists = null;
                }
                if (this.rotation >= 0) {
                    rotation = this.rotation;
                    this.rotation = -1;
                }
                if (screenInfo != null) {
                    info = screenInfo;
                    screenInfo = null;
                }
            }
            if (image != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createCapture(image);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
            if (newLocale != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createLocale(newLocale);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
            if (apps != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createInstalledApps(apps);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
            if (rotation >= 0) {
                UdtDeviceMessage event = UdtDeviceMessage.createGetRotation(rotation);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
            if (info != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createScreenInfo(info);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
        }
    }

    public static class UdtDeviceMessage {
        public static final int TYPE_HEARTBEAT  = 102;
        public static final int TYPE_CAPTURE    = 103;
        public static final int TYPE_GET_LOCALE = 104;
        public static final int TYPE_GET_APPS   = 105;
        public static final int TYPE_GET_ROTATION   = 106;

        private int type;
        private byte[] image;
        private String curLocale;
        private String apps;
        private int rotation = -1;
        private ScreenInfo screenInfo;

        public static UdtDeviceMessage createCapture(byte[] image) {
            UdtDeviceMessage event = new UdtDeviceMessage();
            event.type = TYPE_CAPTURE;
            event.image = image;
            return event;
        }

        public static UdtDeviceMessage createLocale(String locale) {
            UdtDeviceMessage event = new UdtDeviceMessage();
            event.type = TYPE_GET_LOCALE;
            event.curLocale = locale;
            return event;
        }

        public static UdtDeviceMessage createInstalledApps(String apps) {
            UdtDeviceMessage event = new UdtDeviceMessage();
            event.type = TYPE_GET_APPS;
            event.apps = apps;
            return event;
        }

        public static UdtDeviceMessage createGetRotation(int rotation) {
            UdtDeviceMessage event = new UdtDeviceMessage();
            event.type = TYPE_GET_ROTATION;
            event.rotation = rotation;
            return event;
        }

        public static UdtDeviceMessage createScreenInfo(ScreenInfo screenInfo) {
            UdtDeviceMessage event = new UdtDeviceMessage();
            event.type = UdtControllerMessageReader.UdtControlMessage.TYPE_GET_SCREEN_INFO;
            event.screenInfo = screenInfo;
            return event;
        }

        public int getType() {
            return type;
        }

        public byte[] getImage() {
            return image;
        }

        public String getCurLocale() {
            return curLocale;
        }

        public String getApps() {
            return apps;
        }

        public int getRotation() {
            return rotation;
        }
        public ScreenInfo getScreenInfo() {
            return screenInfo;
        }
    }

    public static class UdtDeviceMessageWriter {

        private static final int MESSAGE_MAX_SIZE = 1 << 20; // 1M
        public static final int LOCALE_MAX_LENGTH = 5 + 32; // type: 1 byte; length: 4 bytes;
        public static final int TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 5; // type: 1 byte; length: 4 bytes

        private byte[] rawBuffer;
        private ByteBuffer buffer;

        public void sendUdtDeviceMessage(UdtDeviceMessage msg, OutputStream output) throws IOException {
            UdtLn.i("send udt device msg, type = " + msg.getType());
            if (buffer == null) {
                if (rawBuffer == null) {
                    rawBuffer = new byte[MESSAGE_MAX_SIZE];
                }
                buffer = ByteBuffer.wrap(rawBuffer);
            }

            buffer.clear();
            buffer.put((byte) msg.getType());
            switch (msg.getType()) {
                case UdtDeviceMessage.TYPE_CAPTURE:
                    byte[] image = msg.getImage();
                    buffer.putInt(image.length);
                    buffer.put(image, 0, image.length);
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                case UdtDeviceMessage.TYPE_GET_LOCALE:
                    String locale = msg.getCurLocale();
                    byte[] raw = locale.getBytes(StandardCharsets.UTF_8);
                    int len = StringUtils.getUtf8TruncationIndex(raw, LOCALE_MAX_LENGTH);
                    buffer.putInt(len);
                    buffer.put(raw, 0, len);
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                case UdtDeviceMessage.TYPE_GET_APPS:
                    String apps = msg.getApps();
                    byte[] bytes = apps.getBytes(StandardCharsets.UTF_8);
                    int i = StringUtils.getUtf8TruncationIndex(bytes, TEXT_MAX_LENGTH);
                    buffer.putInt(i);
                    buffer.put(bytes, 0, i);
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                case UdtDeviceMessage.TYPE_GET_ROTATION:
                    int rotation = msg.getRotation();
                    buffer.putInt(rotation);
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                case UdtControllerMessageReader.UdtControlMessage.TYPE_GET_SCREEN_INFO:
                    ScreenInfo info = msg.getScreenInfo();
                    Size size = info.getUnlockedVideoSize();
                    buffer.putInt(size.getWidth());
                    buffer.putInt(size.getHeight());
                    buffer.putInt(info.getDeviceRotation());
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                default:
            }
        }

        public void free() {
            if (buffer != null) {
                buffer = null;
            }
            if (rawBuffer != null) {
                rawBuffer = null;
            }
        }
    }
}

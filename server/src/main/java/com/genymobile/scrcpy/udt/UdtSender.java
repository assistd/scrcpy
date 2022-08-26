package com.genymobile.scrcpy.udt;

import com.genymobile.scrcpy.DesktopConnection;
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

    public UdtSender(DesktopConnection connection) {
        this.connection = connection;
        writer = new UdtDeviceMessageWriter();
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

    public void loop() throws IOException, InterruptedException {
        while (true) {
            byte[] image = null;
            String newLocale = null;
            String apps = null;
            int rotation = -1;
            synchronized (this) {
                while (captureImage == null
                        && curLocale == null
                        && appLists == null
                        && this.rotation < 0
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
    }

    public static class UdtDeviceMessageWriter {

        private static final int MESSAGE_MAX_SIZE = 1 << 20; // 1M
        public static final int LOCALE_MAX_LENGTH = 5 + 32; // type: 1 byte; length: 4 bytes;
        public static final int TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 5; // type: 1 byte; length: 4 bytes

        private final byte[] rawBuffer = new byte[MESSAGE_MAX_SIZE];
        private final ByteBuffer buffer = ByteBuffer.wrap(rawBuffer);

        public void sendUdtDeviceMessage(UdtDeviceMessage msg, OutputStream output) throws IOException {
            UdtLn.i("send udt device msg, type = " + msg.getType());
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
                default:
            }
        }
    }
}

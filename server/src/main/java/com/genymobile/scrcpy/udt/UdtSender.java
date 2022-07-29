package com.genymobile.scrcpy.udt;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Ln;
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

    public void loop() throws IOException, InterruptedException {
        while (true) {
            byte[] image = null;
            String newLocale = null;
            synchronized (this) {
                while (captureImage == null && curLocale == null) {
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
            }

            if (image != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createCapture(image);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
            if (newLocale != null) {
                UdtDeviceMessage event = UdtDeviceMessage.createLocale(newLocale);
                writer.sendUdtDeviceMessage(event, connection.getOutputStream());
            }
        }
    }

    public static class UdtDeviceMessage {
        public static final int TYPE_HEARTBEAT  = 102;
        public static final int TYPE_CAPTURE    = 103;
        public static final int TYPE_GET_LOCALE = 104;

        private int type;
        private byte[] image;
        private String curLocale;

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

        public int getType() {
            return type;
        }

        public byte[] getImage() {
            return image;
        }

        public String getCurLocale() {
            return curLocale;
        }
    }

    public static class UdtDeviceMessageWriter {

        private static final int MESSAGE_MAX_SIZE = 1 << 20; // 1M

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
                    int len = StringUtils.getUtf8TruncationIndex(raw, 128);
                    buffer.putInt(len);
                    buffer.put(raw, 0, len);
                    output.write(rawBuffer, 0, buffer.position());
                    return;
                default:
            }
        }
    }
}

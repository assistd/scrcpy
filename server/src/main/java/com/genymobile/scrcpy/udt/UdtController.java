package com.genymobile.scrcpy.udt;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Options;

import java.io.IOException;
import java.nio.ByteBuffer;

public class UdtController implements ScreenCapture.OnImageAvailableListener {
    private UdtDevice device;
    private Options options;
    private UdtSender udtSender;
    private DesktopConnection connection;
    private boolean snapshotOnce;
    private boolean running;

    private long lastTick = 0;
    private Thread tickCheckThread;

    public UdtController(UdtDevice device, Options options, DesktopConnection connection) {
        UdtLn.i("init controller ");
        this.device = device;
        this.options = options;
        this.connection = connection;
        this.udtSender = new UdtSender(connection);
        running = true;
    }

    public void stop() {
        UdtLn.i("stop controller ");
        running = false;
        ScreenCapture.getInstance().rmListener(this);
    }

    public UdtSender getUdtSender() {
        return udtSender;
    }

    public boolean handleEvent(ByteBuffer buffer, byte _type) {
        int type = _type;
        UdtLn.i("receiving msg, type:" + String.format("0x%02x", _type) + " pos:" + buffer.position());
        UdtControlMessage udtMsg = UdtControllerMessageReader.parseUdtEvent(buffer, type);
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
                ScreenCapture.getInstance().addListener(this);
                ScreenCapture.getInstance().setConfig(height, quality, options);
                snapshotOnce = true;
                int count = 0;
                synchronized (this) {
                    // wait
                    while (snapshotOnce && running) {
                        if (count++ < 20) {
                            try {
                                wait(100);
                            } catch (Exception e) {
                            }
                        } else {
                            UdtLn.d("capture device timeout ( >2s )");
                            break;
                        }
                    }
                }
                ScreenCapture.getInstance().rmListener(this);
                return true;
            case UdtControlMessage.TYPE_PAUSE_VIDEO:
            case UdtControlMessage.TYPE_RESUME_VIDEO:
                device.reqPauseVideo(udtMsg.getType() == UdtControlMessage.TYPE_PAUSE_VIDEO);
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onImageAvailable(byte[] bitmap, int size) {
        synchronized (this) {
            if (snapshotOnce) {
                UdtLn.d("image available, size = " + size);
                udtSender.pushCaptureImage(bitmap, size);
                snapshotOnce = false;
                notify();
            }
        }
    }

    public void onTick(DesktopConnection connection, long ts) {
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
                        UdtLn.w("no heartbeat from client, close connection");
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

    private static class UdtControlMessage {
        public static final int TYPE_REQ_IDR        = 100;
        public static final int TYPE_SET_BITRATE    = 101;
        public static final int TYPE_HEARTBEAT      = 102;
        public static final int TYPE_CAPTURE_DEVICE = 103;
        public static final int TYPE_PAUSE_VIDEO    = 104;
        public static final int TYPE_RESUME_VIDEO   = 105;

        private int type;
        private int bitRate;
        private int capHeight;
        private int capQuality = 80;

        public UdtControlMessage() {
        }

        public int getType() {
            return type;
        }

        public int getBitRate() {
            return bitRate;
        }

        public static UdtControlMessage createEmpty(int type) {
            UdtControlMessage msg = new UdtControlMessage();
            msg.type = type;
            return msg;
        }

        public static UdtControlMessage createSetBitrate(int bitRate) {
            UdtControlMessage msg = new UdtControlMessage();
            msg.type = TYPE_SET_BITRATE;
            msg.bitRate = bitRate;
            return msg;
        }

        public int getCapHeight() {
            return capHeight;
        }

        public int getCapQuality() {
            return capQuality;
        }

        public static UdtControlMessage createCaptureDevice(int height, int quality) {
            UdtControlMessage msg = new UdtControlMessage();
            msg.type = TYPE_CAPTURE_DEVICE;
            msg.capHeight = height;
            msg.capQuality = quality;
            return msg;
        }
    }

    public static class UdtControllerMessageReader {
        static final int SET_BITRATE_LENGTH = 4;
        static final int CAPTURE_DEVICE_SCREEN_LENGTH = 5; // type: 1 byte; height: 2 bytes; quality: 2 bytes

        public static UdtControlMessage parseUdtEvent(ByteBuffer buffer, int type) {
            switch(type) {
                case UdtControlMessage.TYPE_SET_BITRATE:
                    return parseSetBitrate(buffer);
                case UdtControlMessage.TYPE_CAPTURE_DEVICE:
                    return parseCaptureDevice(buffer);
                case UdtControlMessage.TYPE_REQ_IDR:
                case UdtControlMessage.TYPE_HEARTBEAT:
                case UdtControlMessage.TYPE_PAUSE_VIDEO:
                case UdtControlMessage.TYPE_RESUME_VIDEO:
                    return UdtControlMessage.createEmpty(type);
                default:
                    return null;
            }
        }

        private static UdtControlMessage parseSetBitrate(ByteBuffer buffer) {
            if (buffer.remaining() < SET_BITRATE_LENGTH) {
                return null;
            }
            int bitRate = buffer.getInt();
            return UdtControlMessage.createSetBitrate(bitRate);
        }

        private static UdtControlMessage parseCaptureDevice(ByteBuffer buffer) {
            if (buffer.remaining() < CAPTURE_DEVICE_SCREEN_LENGTH) {
                return null;
            }
            int height = buffer.getInt();
            int quality = buffer.getInt();
            return UdtControlMessage.createCaptureDevice(height, quality);
        }
    }
}

package com.genymobile.scrcpy.udt;

import java.nio.ByteBuffer;
public class UdtControllerMessageReader {
    public static class UdtControlMessage {
        public static final int TYPE_REQ_IDR        = 100;
        public static final int TYPE_SET_BITRATE    = 101;
        public static final int TYPE_HEARTBEAT      = 102;
        public static final int TYPE_CAPTURE_DEVICE = 103;
        public static final int TYPE_PAUSE_VIDEO    = 104;
        public static final int TYPE_RESUME_VIDEO   = 105;
        public static final int TYPE_GET_LOCALE     = 106;
        public static final int TYPE_SET_LOCALE     = 107;
        public static final int TYPE_GET_APPS       = 108;

        private int type;
        private int bitRate;
        private int capHeight;
        private int capQuality = 80;
        private String locale;

        public UdtControlMessage() {
        }

        public int getType() {
            return type;
        }

        public int getBitRate() {
            return bitRate;
        }

        public String getNewLocale() {
            return locale;
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

        public static UdtControlMessage createSetLocale(String newLocale) {
            UdtControlMessage msg = new UdtControlMessage();
            msg.type = TYPE_SET_LOCALE;
            msg.locale = newLocale;
            return msg;
        }
    }

    public interface ParseCallBack {
        String onParseString();
    }

    static final int SET_BITRATE_LENGTH = 4;
    static final int CAPTURE_DEVICE_SCREEN_LENGTH = 5; // type: 1 byte; height: 2 bytes; quality: 2 bytes

    public static UdtControlMessage parseUdtEvent(ByteBuffer buffer, int type, ParseCallBack parseCallBack) {
        switch(type) {
            case UdtControlMessage.TYPE_SET_BITRATE:
                return parseSetBitrate(buffer);
            case UdtControlMessage.TYPE_CAPTURE_DEVICE:
                return parseCaptureDevice(buffer);
            case UdtControlMessage.TYPE_REQ_IDR:
            case UdtControlMessage.TYPE_HEARTBEAT:
            case UdtControlMessage.TYPE_PAUSE_VIDEO:
            case UdtControlMessage.TYPE_RESUME_VIDEO:
            case UdtControlMessage.TYPE_GET_LOCALE:
            case UdtControlMessage.TYPE_GET_APPS:
                return UdtControlMessage.createEmpty(type);
            case UdtControlMessage.TYPE_SET_LOCALE:
                return parseSetLocale(parseCallBack);
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

    private static UdtControlMessage parseSetLocale(ParseCallBack parseCallBack) {
        String newLocal = parseCallBack.onParseString();
        if (newLocal == null) {
            return null;
        }
        UdtLn.i("parseSetLocale: newLocal " + newLocal);
        return UdtControlMessage.createSetLocale(newLocal);
    }
}

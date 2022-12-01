package com.genymobile.scrcpy.udt;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.ScreenEncoder;

import java.io.FileDescriptor;

public class UdtEncoder {
    private final ScreenEncoder encoder;

    public UdtEncoder(ScreenEncoder encoder) {
        this.encoder = encoder;
    }

    enum Mode {
        Resume,
        Pause,
        Exit,
    }

    private MediaCodec codec;

    private static final String KEY_DURATION = "duration";

    private Mode videoMode = Mode.Resume;
    private int bitRate;

    public static boolean setUdtCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        switch (key) {
            case KEY_DURATION:
                Object value = codecOption.getValue();
                if (value instanceof Integer) {
                    ScreenEncoder.durationUs = (Integer) value * 1000 * 1000;
                    UdtLn.d("udt: codec option " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    public void onInit(MediaCodec codec) {
        UdtLn.i("udt: init codec" + codec.getName());
        this.codec = codec;
    }

    public void onBitrateChanged(int bitrate) {
        UdtLn.i("udt: change bitrate, new: " + bitrate + ", old: " + bitRate);
        if (waitCodecReady()) {
            bitRate = bitrate;
            try {
                android.os.Bundle b = new android.os.Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
                codec.setParameters(b);
            } catch (IllegalStateException e) {
                UdtLn.e("onBitrateChanged failed", e);
            }
        }
    }

    public void onReqIDRFrame() {
        UdtLn.i("udt: req IDRFrame now");
        if (waitCodecReady()) {
            videoMode = Mode.Resume;
            resetCodec();
        }
    }

    private void resetCodec() {
        if (ScreenEncoder.durationUs > -1) {
            UdtLn.i("udt: generate key frame by update rotation and wait timeout");
            encoder.onRotationChanged(0);
        }
    }

    public void onPauseVideo(boolean pause) {
        UdtLn.i("udt: pauseing video thread: " + pause);
            videoMode = pause ? Mode.Pause : Mode.Resume;
        if (waitCodecReady()) {
            resetCodec();
        }
    }

    public void onExitVideo() {
        UdtLn.d("udt: exit video thread");
        videoMode = Mode.Exit;
    }

    private boolean waitCodecReady() {
        UdtLn.d("udt: wait codec ready");
        int i = 0;
        while (codec == null && i++ < 20) {
            try {
                Thread.sleep(50);
            } catch (Exception e) {}
        }
        return codec != null;
    }

    public boolean onFinish(FileDescriptor fd) {
        if (videoMode == Mode.Pause) {
            while (videoMode == Mode.Pause) {
                try {
                    Thread.sleep(50);
                } catch (Exception e) {}
            }
        }
        return videoMode == Mode.Exit;
    }
}

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
    private volatile Mode videoMode = Mode.Resume;
    private int bitRate;

    public static boolean setUdtCodecOption(MediaFormat format, CodecOption codecOption) {
        String key = codecOption.getKey();
        if (KEY_DURATION.equals(key)) {
            Object value = codecOption.getValue();
            if (value instanceof Integer) {
                ScreenEncoder.durationUs = (Integer) value * 1000 * 1000;
                UdtLn.d("udt: codec option " + key + " (" + value.getClass().getSimpleName() + ") = " + value);
                return true;
            }
        }
        return false;
    }

    public void onInit(MediaCodec codec) {
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
            UdtLn.i("udt: update rotation to generate key frame");
            encoder.onRotationChanged(1);
        }
    }

    public void onPauseVideo(boolean pause) {
        UdtLn.i("udt: pause video thread: " + pause);
            videoMode = pause ? Mode.Pause : Mode.Resume;
        if (waitCodecReady()) {
            encoder.onRotationChanged(1);
        }
    }

    public void onExitVideo() {
        UdtLn.i("udt: exit video thread");
        videoMode = Mode.Exit;
    }

    private boolean waitCodecReady() {
        UdtLn.i("udt: wait codec ready");
        int i = 0;
        while (codec == null && i++ < 2 * 3) {
            UdtLn.i("udt: do wait codec ready");
            try {
                Thread.sleep(500);
            } catch (Exception e) {}
        }
        return codec != null;
    }

    public boolean onFinish(FileDescriptor fd) {
        if (videoMode == Mode.Pause) {
            while (videoMode == Mode.Pause) {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {}
            }
        }
        return videoMode == Mode.Exit;
    }
}

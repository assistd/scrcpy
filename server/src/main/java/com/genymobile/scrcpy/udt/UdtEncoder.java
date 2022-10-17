package com.genymobile.scrcpy.udt;

import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.ScreenEncoder;
import com.genymobile.scrcpy.Size;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

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
    private List<CodecOption> codecOptions;

    private static final String KEY_DURATION = "duration";

    private Mode videoMode = Mode.Resume;
    private int bitRate;
    private boolean reqKeyFrame;

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
            reqKeyFrame = true;
            videoMode = Mode.Resume;
            resetCodec();
        }
    }

    private void resetCodec() {
        if (ScreenEncoder.durationUs > -1) {
            UdtLn.i("udt: generate key frame by udpate rotation and wait timeout");
            encoder.onRotationChanged(0);
        } else {
            UdtLn.i("udt: generate key frame by udpate rotation and flush codec if need");
            encoder.onRotationChanged(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startResetThread();
            }
        }
    }

    public boolean isReqKeyFrame() {
        return reqKeyFrame;
    }

    public void onPauseVideo(boolean pause) {
        UdtLn.i("udt: pauseing video thread: " + pause);
            videoMode = pause ? Mode.Pause : Mode.Resume;
        if (waitCodecReady()) {
            reqKeyFrame = true;
            UdtLn.i("udt: pausing video by reset");
            resetCodec();
            UdtLn.i("udt: pausing video by reset");
        }
        UdtLn.i("udt: paused video thread");
    }

    public void onExitVideo() {
        UdtLn.i("udt: exit video thread");
        videoMode = Mode.Exit;
    }

    private boolean waitCodecReady() {
        UdtLn.i("udt: wait codec ready");
        int i = 0;
        while (codec == null && i++ < 20) {
            UdtLn.i("udt: do wait codec ready");
            try {
                Thread.sleep(50);
            } catch (Exception e) {}
        }
        return codec != null;
    }

    public boolean onFinish(FileDescriptor fd) {
        reqKeyFrame = false;
        if (videoMode == Mode.Pause) {
            while (videoMode == Mode.Pause) {
                try {
                    UdtLn.d("udt: wait codec resume");
                    Thread.sleep(50);
                } catch (Exception e) {}
            }
        }
        return videoMode == Mode.Exit;
    }

    private void startResetThread() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
              if (codec != null) {
                  try {
                      codec.flush();
                      UdtLn.i("udt: stop code");
                  } catch (Exception e) {
                      UdtLn.i("udt: ignore exception from stop frame");
                  }
              }
            }
        });
        thread.start();
    }
}

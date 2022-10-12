package com.genymobile.scrcpy.udt;

import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
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
    private volatile boolean reqKeyFrame;

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
            reqKeyFrame = true;
            if (ScreenEncoder.durationUs > -1) {
                UdtLn.i("udt: generate key frame by udpate rotaion and wait timeout");
                encoder.onRotationChanged(1);
            } else {
                UdtLn.i("udt: generate key frame by stop codec");
                try {
                    codec.stop();
                } finally {
                    UdtLn.i("udt: ignore exception from req frame");
                }
            }
        }
    }

    public boolean isReqKeyFrame() {
        return reqKeyFrame;
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
                    Thread.sleep(50);
                } catch (Exception e) {}
            }
        }
        return videoMode == Mode.Exit;
    }

    private static final String KEY_LEVEL = "level"; //@see MediaFormat.KEY_LEVEL
    private static final String KEY_PROFILE = "profile"; //@see MediaFormat.KEY_PROFILE

    private static final int CodecAVCProfileBaseline
            = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
    private static final String mimeType = MIMETYPE_VIDEO_AVC;

    public static MediaFormat chooseCodecLevel(MediaFormat format)  {
        int sugguestLevel = -1;
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (!types[j].equalsIgnoreCase(mimeType)) {
                    continue;
                }
                for (MediaCodecInfo.CodecProfileLevel level :
                        codecInfo.getCapabilitiesForType(mimeType).profileLevels) {
                    if (level.profile != CodecAVCProfileBaseline) {
                       continue;
                    }
                    UdtLn.i("support codecs info:, level: " + level.level
                            + ", profile: " + level.profile);
                    if (level.level < sugguestLevel || sugguestLevel < 0) {
                        sugguestLevel = level.level;
                    }
                }
                break;
            }
        }

        if (sugguestLevel >= 0) {
            format.setInteger(KEY_PROFILE, CodecAVCProfileBaseline);
            format.setInteger(KEY_LEVEL, sugguestLevel );
        }
        return format;
    }
}

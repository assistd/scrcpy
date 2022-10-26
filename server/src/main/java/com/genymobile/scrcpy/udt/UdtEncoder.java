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

    private static final String KEY_LEVEL = "level"; //@see MediaFormat.KEY_LEVEL
    private static final String KEY_PROFILE = "profile"; //@see MediaFormat.KEY_PROFILE

    private static final int SugguestCodecProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
    private static int sSuggestCodecLevel = -1;
    private static int sSuggestCodecFailedCount = 0;
    private static int sSuggestMaxCodecLevel = MediaCodecInfo.CodecProfileLevel.AVCLevel62;

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
            case KEY_PROFILE:
                return UdtOption.sUseSuggestCodec;
            case KEY_LEVEL:
                if (UdtOption.sUseSuggestCodec) {
                    chooseCodecLevel();
                    if (sSuggestCodecLevel > 0) {
                        format.setInteger(KEY_PROFILE, SugguestCodecProfile);
                        format.setInteger(KEY_LEVEL, sSuggestCodecLevel);
                        UdtLn.i("use suggest codec info, profile: " + SugguestCodecProfile
                                + ",level:"+ sSuggestCodecLevel);
                    } else {
                        UdtLn.i("use system default codec info");
                    }
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

    public boolean isCustomCodec() {
        return UdtOption.sUseSuggestCodec;
    }

    public boolean downCodecLevel(String reason) {
        if (sSuggestCodecLevel >0) {
            if (reason.contains("CodecException: Error 0xffffffc3")) {
                sSuggestMaxCodecLevel = sSuggestCodecLevel;
                sSuggestCodecLevel = -1;
            } else if (sSuggestCodecFailedCount++ > 3) {
                UdtLn.i("udt: not use suggest codec");
                UdtOption.sUseSuggestCodec = false;
            }
            return true;
        }
        return false;
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

    private static final String mimeType = MIMETYPE_VIDEO_AVC;

    public static void chooseCodecLevel()  {
        if (sSuggestCodecLevel > -1) {
            return;
        }

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
                    if (level.profile != SugguestCodecProfile) {
                        continue;
                    }
                    UdtLn.i("dump suggest codecs info:, level: " + level.level
                            + ", profile: " + level.profile);
                    if (level.level > sSuggestCodecLevel && level.level < sSuggestMaxCodecLevel
                            || sSuggestCodecLevel < 0) {
                        sSuggestCodecLevel = level.level;
                    }
                }
                break;
            }
        }
    }
}

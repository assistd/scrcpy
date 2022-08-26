package com.genymobile.scrcpy.udt;

import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.ScreenEncoder;

import java.util.HashMap;

public class UdtDevice {
    private UdtController udtController;
    private UdtEncoder udtEncoder;
    private DesktopConnection connection;
    private Device device;
    private Thread deviceMessageSenderThread;

    public static UdtDevice build(Device device,DesktopConnection connection, ScreenEncoder encoder, Options options) {
        if (!UdtOption.SUPPORT) {
            return null;
        }
        return new UdtDevice(device, connection, encoder, options);
    }

    private UdtDevice(Device device, DesktopConnection connection, ScreenEncoder encoder, Options options) {
        UdtLn.i("udt: init device");
        this.connection = connection;
        this.udtEncoder  = new UdtEncoder(encoder);
        this.udtController = new UdtController(this, options, connection);
        connection.getReader().setUdtController(udtController);

        this.device = device;
        Combiner.bind(device, this);

        deviceMessageSenderThread = startUDtDeviceMessageSender(udtController.getUdtSender());
    }

    private Thread startUDtDeviceMessageSender(final UdtSender sender) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (Exception e) {
                    UdtLn.i("io Exception with client err: " + e + ", connection:" + connection);
                    stop();
                }
            }
        });
        thread.start();
        return thread;
    }

    public UdtEncoder getUdtEncoder() {
        return udtEncoder;
    }

    public int getRotation() {
        return device.getScreenInfo().getDeviceRotation();
    }

    public void reqIDRFrame() {
        if (udtEncoder != null) {
            udtEncoder.onReqIDRFrame();
        }
    }

    public void setBitRate(int bitrate) {
        if (udtEncoder != null) {
            udtEncoder.onBitrateChanged(bitrate);
        }
    }

    public void reqPauseVideo(boolean pause) {
        if (udtEncoder != null) {
            udtEncoder.onPauseVideo(pause);
        }
    }

    public void stop() {
        if (udtController != null) {
            udtController.stop();
            udtController = null;
        }

        if (udtEncoder != null) {
            udtEncoder.onExitVideo();
            udtEncoder = null;
        }

        if (deviceMessageSenderThread != null) {
            deviceMessageSenderThread.interrupt();
        }

        Combiner.unBind(device);
    }

    public static class Combiner {
        private static final HashMap<Device, UdtDevice> bindMaps = new HashMap<>();

        public static void bind(Device device, UdtDevice udtDevice) {
            bindMaps.put(device, udtDevice);
        }

        public static void unBind(Device device) {
            bindMaps.remove(device);
        }

        public static UdtDevice get(Device device) {
            return bindMaps.get(device);
        }
    }
}

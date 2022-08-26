package com.genymobile.scrcpy.udt;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.Controller;
import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.DeviceMessageSender;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.ScreenEncoder;
import com.genymobile.scrcpy.Size;

import java.io.IOException;
import java.util.List;

public class UdtServer {
    private static int sClientCount = 0;

    // sync with {com.genymobile.scrcpy.Server.scrcpy()}
    public static boolean scrcpy(Options options) throws IOException {
        setProcessArgs(UdtOption.SOCKET_NAME);
        ScreenEncoder.sSurfaceName = UdtOption.SOCKET_NAME;
        UdtLn.setTag(UdtOption.SOCKET_NAME);

        boolean control = options.getControl();
        boolean sendDummyByte = options.getSendDummyByte();

        UdtLn.i("Start wait multi connection");
        try {
            open(control, sendDummyByte,
                    new DesktopConnectionListener() {
                        @Override
                        public void onConnect(DesktopConnection connection) {
                            sClientCount++;
                            UdtLn.i("on connect and current count = " + sClientCount);
                            StreamClient client = new StreamClient(connection, options);
                            client.start();
                        }
                    });
        } catch (Exception e) {
            throw e;
        }
        return true;
    }

    private static class StreamClient extends Thread {
        DesktopConnection connection;
        Options options;

        StreamClient(DesktopConnection connection, Options options) {
            this.connection = connection;
            this.options = options;
        }

        @Override
        public void interrupt() {
            try {
                connection.close();
            }
            catch (IOException e) {
                UdtLn.i("StreamClient interrupt by err: " + e);
            }
        }

        @Override
        public void run() {
            UdtLn.i("StreamClient start for connect: " + connection);
            List<CodecOption> codecOptions = options.getCodecOptions();
            boolean control = options.getControl();
            try {
                final Device device = new Device(options);
                streamScreen(connection, device, options, codecOptions, control);
            } catch (IOException e) {
                UdtLn.i("client: " + connection +
                        ", exit by IOException: " + e);
                System.exit(1);
            } catch (Exception e) {
                UdtLn.i("client: " + connection +
                        ", exit by Exception: " + e);
            } finally {
                try {
                    connection.close();
                } catch (Exception e1) {
                }
                sClientCount--;
                UdtLn.i("stream stop and last client count: " + sClientCount);
            }
        }
    }

    // sync with {com.genymobile.scrcpy.Server.startController()}
    private static void streamScreen(DesktopConnection connection, Device device, Options options,
                                     List<CodecOption> codecOptions, boolean control) throws IOException {
        if (options.getSendDeviceMeta()) {
            Size videoSize = device.getScreenInfo().getVideoSize();
            connection.sendDeviceMeta(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
        }
        ScreenEncoder screenEncoder = new ScreenEncoder(options.getSendFrameMeta(), options.getBitRate(), options.getMaxFps(), codecOptions,
                options.getEncoderName(), options.getDownsizeOnError());

        Thread controllerThread = null;
        Thread deviceMessageSenderThread = null;
        UdtDevice udtDevice = null;
        if (control) {
            final Controller controller = new Controller(device, connection, options.getClipboardAutosync(), options.getPowerOn());
            if (UdtOption.SUPPORT) {
                udtDevice = UdtDevice.build(device, connection, screenEncoder, options);
            }

            // asynchronous
            controllerThread = startController(controller, udtDevice);
            deviceMessageSenderThread = startDeviceMessageSender(controller.getSender());

            device.setClipboardListener(new Device.ClipboardListener() {
                @Override
                public void onClipboardTextChanged(String text) {
                    controller.getSender().pushClipboardText(text);
                }
            });
        }

        try {
            // synchronous
            screenEncoder.streamScreen(device, connection.getVideoFd());
        } catch (IOException e) {
            // this is expected on close
            UdtLn.w("Screen streaming stopped for " + connection.getVideoFd());
        } finally {
            // initThread.interrupt();
            if (controllerThread != null) {
                controllerThread.interrupt();
            }
            if (deviceMessageSenderThread != null) {
                deviceMessageSenderThread.interrupt();
            }
            if (UdtOption.SUPPORT) {
                if (udtDevice != null) {
                    udtDevice.stop();
                }
            }
        }
    }

    // sync with {com.genymobile.scrcpy.Server.startController()}
    private static Thread startController(final Controller controller, UdtDevice device) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.control();
                } catch (IOException e) {
                    // this is expected on close
                    UdtLn.d("Controller stopped");
                    if (UdtOption.SUPPORT) {
                        if (device != null) {
                            device.stop();
                        }
                    }
                }
            }
        });
        thread.start();
        return thread;
    }

    // sync with {com.genymobile.scrcpy.Server.startDeviceMessageSender()}
    private static Thread startDeviceMessageSender(final DeviceMessageSender sender) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sender.loop();
                } catch (IOException | InterruptedException e) {
                    // this is expected on close
                    UdtLn.d("Device message sender stopped");
                }
            }
        });
        thread.start();
        return thread;
    }

    public interface DesktopConnectionListener {
        void onConnect(DesktopConnection connection) ;
    }

    //NOTE: only support forward mode.
    public static void open(boolean control, boolean sendDummyByte, DesktopConnectionListener listener) throws IOException {
        LocalSocket videoSocket;
        LocalSocket controlSocket = null;
        LocalServerSocket localServerSocket = new LocalServerSocket(UdtOption.SOCKET_NAME);
        try {
            do {
                videoSocket = localServerSocket.accept();
                if (sendDummyByte) {
                    // send one byte so the client may read() to detect a connection error
                    videoSocket.getOutputStream().write(0);
                }
                if (control) {
                    try {
                        controlSocket = localServerSocket.accept();
                    } catch (IOException | RuntimeException e) {
                        videoSocket.close();
                        throw e;
                    }
                }
                if (listener != null) {
                    listener.onConnect(DesktopConnection.build(videoSocket, controlSocket));
                }
            } while (listener != null);
        } finally {
            localServerSocket.close();
        }
    }

    private static void setProcessArgs(String str) {
        try {
            Object[] objArr = {str};
            android.os.Process.class
                    .getMethod("setArgV0", new Class[]{String.class})
                    .invoke(android.os.Process.class, objArr);
        } catch (Exception e) {
            UdtLn.i("setProcessArgs error:" + e.toString());
        }
    }
}

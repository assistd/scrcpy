package com.genymobile.scrcpy.udt;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.Controller;
import com.genymobile.scrcpy.DesktopConnection;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.DeviceMessageSender;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.ScreenEncoder;
import com.genymobile.scrcpy.Size;

import java.io.IOException;
import java.util.List;

public class UdtServer {
    private static int sClientCount = 0;
    private static final String PROCESS_NAME = "udt-scrcpy";

    // sync with {com.genymobile.scrcpy.Server.scrcpy()}
    public static boolean scrcpy(Options options) throws IOException {
        setProcessArgs(PROCESS_NAME);

        List<CodecOption> codecOptions = options.getCodecOptions();

        // Thread initThread = startInitThread(options);

        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean sendDummyByte = options.getSendDummyByte();

        final IOException[] runException = new IOException[1];
        final int MAX_THREAD_COUNT = 2 * 10; // just support 10 concurrent client
        java.util.concurrent.ExecutorService executors =
                java.util.concurrent.Executors.newFixedThreadPool(MAX_THREAD_COUNT);

        UdtLn.i("Start wait multi connection");
        try {
            open(control, sendDummyByte,
                    new DesktopConnectionListener() {
                        @Override
                        public void onConnect(DesktopConnection connection) {
                            sClientCount++;
                            UdtLn.i("client: " + connection +
                                    ", stream start and last client count: " + sClientCount);
                            executors.submit(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        final Device device = new Device(options);
                                        streamScreen(connection, device, options, codecOptions, control);
                                    } catch (IOException e) {
                                        runException[0] = e;
                                        UdtLn.i("client: " + connection +
                                                ", exit by IOException: " +  e);
                                    } finally {
                                        try {
                                            connection.close();
                                        } catch (Exception e1) {
                                        }
                                        sClientCount--;
                                        Ln.i("stream stop and last client count: " + sClientCount);
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            throw e;
        }

        while (runException[0].toString().length() == 0) {
            UdtLn.i("loop for wait stream connection: ");
            android.os.SystemClock.sleep(5000);
        }

        try {
            executors.awaitTermination(0L, java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {}

        return true;
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
            UdtLn.d("Screen streaming stopped");
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
            Ln.i("setProcessArgs error:" + e.toString());
        }
    }
}

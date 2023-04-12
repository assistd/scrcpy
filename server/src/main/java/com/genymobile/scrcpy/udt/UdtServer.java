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
import java.lang.ref.WeakReference;
import java.util.List;

public class UdtServer {
    private static int sClientCount = 0;

    // sync with {com.genymobile.scrcpy.Server.scrcpy()}
    public static boolean scrcpy(Options options) throws IOException {
        setProcessArgs(UdtOption.SOCKET_NAME);
        ScreenEncoder.sSurfaceName = UdtOption.SOCKET_NAME;
        UdtLn.setTag(UdtOption.SOCKET_NAME);

        boolean sendDummyByte = options.getSendDummyByte();
        UdtLn.i("Start wait multi connection");

        // Start control server
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    open(UdtOption.SOCKET_NAME+"-ctrl", false,
                            new DesktopConnectionListener() {
                                @Override
                                public void onConnect(LocalSocket socket) {
                                    sClientCount++;
                                    UdtLn.i("[ctrl] on connect and current count = " + sClientCount);
                                    try {
                                        StreamClient client = new StreamClient(DesktopConnection.build(null, socket), options);
                                        client.start();
                                    } catch (IOException e) {
                                        // ignore
                                    }
                                }
                            });
                } catch (Exception e) {
                    // this is expected on close
                    UdtLn.d("ctrl server stopped");
                }
            }
        }).start();

        // Start video server
        try {
            open(UdtOption.SOCKET_NAME+"-video", sendDummyByte,
                    new DesktopConnectionListener() {
                        @Override
                        public void onConnect(LocalSocket socket) {
                            sClientCount++;
                            UdtLn.i("[video] on connect and current count = " + sClientCount);
                            try {
                                StreamClient client = new StreamClient(DesktopConnection.build(socket, null), options);
                                client.start();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    });
        } catch (Exception e) {
            throw e;
        }
        return true;
    }

    private static class StreamClient extends Thread {
        WeakReference<DesktopConnection> connectionRef;
        Options options;

        StreamClient(DesktopConnection connection, Options options) {
            this.connectionRef = new WeakReference<>(connection);
            this.options = options;
        }

        @Override
        public void interrupt() {
            DesktopConnection connection = connectionRef.get();
            if (connection == null) {
                return;
            }

            try {
                connection.close();
            }
            catch (IOException e) {
                UdtLn.i("StreamClient interrupt by err: " + e);
            }
        }

        @Override
        public void run() {
            DesktopConnection connection = connectionRef.get();
            if (connection == null) {
                return;
            }

            // FIXME: 这里通过connection字段是否为空判断connection类型，后续应该重构
            boolean isVideo = connection.getVideoFd() != null;

            UdtLn.i("StreamClient start for connect: " + connection);
            List<CodecOption> codecOptions = options.getCodecOptions();
            try {
                final Device device = new Device(options);
                streamScreen(connection, device, options, codecOptions, isVideo);
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

                if (UdtUtils.DEBUG_MEM) {
                    UdtUtils.dumpMem();
                }

                sClientCount--;
                UdtLn.i("stream stop and last client count: " + sClientCount);
            }
        }
    }

    // sync with {com.genymobile.scrcpy.Server.startController()}
    private static void streamScreen(DesktopConnection connection, Device device, Options options,
                                     List<CodecOption> codecOptions, boolean isVideo) throws IOException {
        // 如果该client为video client
        if (isVideo) {
            if (options.getSendDeviceMeta()) {
                Size videoSize = device.getScreenInfo().getVideoSize();
                connection.sendDeviceMeta(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
            }

            ScreenEncoder screenEncoder = new ScreenEncoder(options.getSendFrameMeta(), options.getBitRate(), options.getMaxFps(), codecOptions,
                    options.getEncoderName(), options.getDownsizeOnError());

            // FIXME: 这里有隐藏的风险，需要注意
            UdtDevice udtDevice = UdtDevice.Combiner.get(device);
            if (udtDevice != null) {
                udtDevice.setUdtEncoder(screenEncoder);
            }
            try {
                // synchronous
                screenEncoder.streamScreen(device, connection.getVideoFd());
            } catch (IOException e) {
                // this is expected on close
                UdtLn.w("Screen streaming stopped for " + connection.getVideoFd());
            } finally {
                // initThread.interrupt();
            }
        } else {
            // 该client为control client
            Thread controllerThread = null;
            UdtDevice udtDevice = null;
            final Controller controller = new Controller(device, connection, options.getClipboardAutosync(), options.getPowerOn());
            if (UdtOption.SUPPORT) {
                udtDevice = UdtDevice.build(device, connection, null, options);
            }

            device.setClipboardListener(new Device.ClipboardListener() {
                @Override
                public void onClipboardTextChanged(String text) {
                    controller.getSender().pushClipboardText(text);
                }
            });

            // asynchronous
            controllerThread = startController(controller, udtDevice);

            // synchronous
            try {
                controller.getSender().loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
                UdtLn.d("Device message sender stopped");
            }

            if (UdtOption.SUPPORT) {
                if (udtDevice != null) {
                    udtDevice.stop();
                }
            }
            if (controllerThread != null) {
                controllerThread.interrupt();
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
        void onConnect(LocalSocket socket) ;
    }

    // UdtOption.SOCKET_NAME
    //NOTE: only support forward mode.
    public static void open(String socketName, boolean sendDummyByte, DesktopConnectionListener listener) throws IOException {
        LocalSocket socket;
        LocalSocket controlSocket = null;
        LocalServerSocket localServerSocket = new LocalServerSocket(socketName);
        try {
            do {
                socket = localServerSocket.accept();
                if (sendDummyByte) {
                    // send one byte so the client may read() to detect a connection error
                    socket.getOutputStream().write(0);
                }
                if (listener != null) {
                    listener.onConnect(socket);
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

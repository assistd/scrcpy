package com.genymobile.scrcpy.udt;

import java.util.Locale;

public class UdtOption {
    public static Boolean SUPPORT = true;
    public static String SOCKET_NAME = "scrcpy";

    public static boolean sRescaleImage = false;
    public static boolean sRotationAutoSync = false;

    public static boolean createOptions( String key, String value) {
        switch (key) {
            case "udt_enable":
                SUPPORT = Boolean.parseBoolean(value);
                return true;
            case "udt_socket_name":
                SOCKET_NAME =  value;
                return true;
            case "udt_log_level":
                UdtLn.Level level = UdtLn.Level.valueOf(value.toUpperCase(Locale.ENGLISH));
                UdtLn.initLogLevel(level);
                return true;
            case "udt_libs_path":
                UdtLn.i("udt_libs_path = " + value);
                System.setProperty(JpgEncoder.LIB_PATH, value);
                return true;
            case "scale_image":
                sRescaleImage = Boolean.parseBoolean(value);
                return true;
            case "rotation_autosync":
                sRotationAutoSync = Boolean.parseBoolean(value);
                return true;
            default:
                return false;
        }
    }
}

package com.genymobile.scrcpy.udt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

public class UdtUtils {
    public static Boolean DEBUG_MEM = Objects.equals(
            System.getProperty("debug.udt.dumpmem", "0"), "1");
    private static long lastTick = 0;

    public static void dumpMem() {
        if (Math.abs(System.currentTimeMillis() - lastTick) > 5 * 60 * 1000) {
            lastTick = System.currentTimeMillis();
            try {
                Date date = new Date(lastTick);
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-mm-dd-hh-mm-ss");
                String dumpPath = "/sdcard/data-" + simpleDateFormat.format(date) + ".hprof";
                android.os.Debug.dumpHprofData(dumpPath);
            } catch (Exception e) {
                UdtLn.e("dumpHprofData failed: " + e);
            }
        }
    }
}

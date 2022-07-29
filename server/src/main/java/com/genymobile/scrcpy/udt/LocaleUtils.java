package com.genymobile.scrcpy.udt;

import android.annotation.SuppressLint;
import android.os.Build;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

public class LocaleUtils {

    @SuppressLint("PrivateApi")
    public static boolean changeLanguage(java.util.Locale locate) {
        try {
            Class<?> iamClass;
            Method m;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                iamClass = Class.forName("android.app.ActivityManagerNative");
                m = iamClass.getMethod("getDefault");
            } else {
                iamClass = Class.forName("android.app.IActivityManager");
                Class<?> amClass = Class.forName("android.app.ActivityManager");
                m = amClass.getMethod("getService");
            }

            Object iam = m.invoke(iamClass, new Object[0]);
            m.setAccessible(true);

            Method getConfigurationMethod = iamClass.getMethod("getConfiguration");
            getConfigurationMethod.setAccessible(true);
            Object config = getConfigurationMethod.invoke(iam, new Object[0]);

            Class<?> configClass = Class.forName("android.content.res.Configuration");
            Field userSetLocaleField = configClass.getField("userSetLocale");
            userSetLocaleField.setBoolean(config, true);

            Field localeField = configClass.getField("locale");
            localeField.set(config, locate);

            Method updateConfigurationMethod = iamClass.getMethod("updateConfiguration",
                    configClass);
            updateConfigurationMethod.setAccessible(true);
            updateConfigurationMethod.invoke(iam, config);
            return true;
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | SecurityException
                | IllegalAccessException
                | IllegalArgumentException
                | InvocationTargetException
                | NoSuchFieldException e) {
            UdtLn.e("changeLanguage error: ", e);
        }
        return false;
    }

    public static String formatLocaleString(java.util.Locale l) {
        StringBuffer sb = new StringBuffer();
        sb.append(l.getLanguage());
        String country = l.getCountry();
        if (!TextUtils.isEmpty(sb)) {
            sb.append("_");
            sb.append(country);
        }
        sb.append("\t");
        sb.append(l.getDisplayLanguage());
        sb.append("\t");
        sb.append(l.getDisplayCountry());
        return sb.toString();
    }

    public static Boolean isLocaleAvailable(String lang, String country) {
        if ("zh".equals(lang) && "CN".equals(country)) {
            return true;
        }
        java.util.Locale[] locales = java.util.Locale.getAvailableLocales();
        for (java.util.Locale l : locales) {
            if (lang.equals(l.getLanguage()) && country.equals(l.getCountry())) {
                return true;
            }
        }
        return false;
    }

    public static void listLocales(String filterLanguage) {
        java.util.Locale[] locales = java.util.Locale.getAvailableLocales();
        for (java.util.Locale l : locales) {
            if (!TextUtils.isEmpty(filterLanguage)
                    && !filterLanguage.equals(l.getLanguage())) {
                continue;
            }
        }
    }
}

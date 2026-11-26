package com.github.catvod.api;

import android.util.Log;

import androidx.annotation.Keep;

@Keep
public class Quark {

    private static final String TAG = "NextMovieCanary";
    private static String cache = "";

    static {
        Log.i(TAG, "api.Quark loaded");
    }

    public static String getCookie() {
        String c = Pan.cookie("quark");
        if (c == null || c.isEmpty()) c = cache;
        Log.i(TAG, "Quark.getCookie len=" + (c == null ? 0 : c.length()));
        return c == null ? "" : c;
    }

    public static void setCookie(String cookie) {
        cache = cookie == null ? "" : cookie;
        Log.i(TAG, "Quark.setCookie len=" + cache.length());
    }

    public static boolean hasCookie() {
        return !getCookie().isEmpty();
    }

    public static void clearCookie() {
        cache = "";
    }
}

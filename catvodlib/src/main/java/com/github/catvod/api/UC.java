package com.github.catvod.api;

import android.util.Log;

import androidx.annotation.Keep;

@Keep
public class UC {

    private static final String TAG = "NextMovieCanary";
    private static String cache = "";

    public static String getCookie() {
        String c = Pan.cookie("uc");
        if (c == null || c.isEmpty()) c = cache;
        Log.i(TAG, "UC.getCookie len=" + (c == null ? 0 : c.length()));
        return c == null ? "" : c;
    }

    public static void setCookie(String cookie) {
        cache = cookie == null ? "" : cookie;
        Log.i(TAG, "UC.setCookie len=" + cache.length());
    }

    public static boolean hasCookie() { return !getCookie().isEmpty(); }
}

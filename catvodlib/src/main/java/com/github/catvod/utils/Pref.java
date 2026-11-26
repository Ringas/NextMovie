package com.github.catvod.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Keep;

import com.github.catvod.Runtime;

import java.io.File;

/**
 * Guard 系插件的「配置中心」落在宿主这一层：它的解密段会按 CatVod/FongMi 的约定
 * 调用 com.github.catvod.utils.Pref 存取 cookie_quark 之类的键。
 * 这里做成真实可用的实现：读宿主 prefs + 宿主落盘的 cookie 文件，并且把每次调用都打日志，
 * 用来判定插件到底问的是哪个 key（此前的空壳类会让它拿到 null，表现就是
 * 「(auth) 还没有配置夸克 Cookie，请先去配置中心登录夸克」）。
 */
@Keep
public class Pref {

    private static final String TAG = "NextMovieCanary";
    private static final String NAME = "pref";

    private static SharedPreferences prefs() {
        Context ctx = Runtime.context();
        return ctx == null ? null : ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static String getString(String key, String def) {
        String value = cookieLike(key);
        if (value != null && !value.isEmpty()) {
            Log.i(TAG, "Pref.getString key=" + key + " -> pan-cookie " + value.length());
            return value;
        }
        try {
            SharedPreferences p = prefs();
            String v = p == null ? null : p.getString(key, def);
            if (v != null && !v.isEmpty()) return v;
            Context ctx = Runtime.context();
            if (ctx != null) {
                String alt = ctx.getSharedPreferences("nextmovie", Context.MODE_PRIVATE).getString(key, "");
                if (alt != null && !alt.isEmpty()) return alt;
            }
            Log.i(TAG, "Pref.getString key=" + key + " -> " + (v == null ? "null" : "len=" + v.length()));
            return v == null ? def : v;
        } catch (Throwable e) {
            Log.w(TAG, "Pref.getString failed key=" + key + ": " + e);
            return def;
        }
    }

    public static void putString(String key, String value) {
        try {
            SharedPreferences p = prefs();
            if (p != null) p.edit().putString(key, value).apply();
            Log.i(TAG, "Pref.putString key=" + key + " len=" + (value == null ? 0 : value.length()));
        } catch (Throwable e) {
            Log.w(TAG, "Pref.putString failed key=" + key + ": " + e);
        }
    }

    public static String get(String key) { return getString(key); }
    public static void put(String key, String value) { putString(key, value); }
    public static void set(String key, String value) { putString(key, value); }

    public static int getInt(String key) { return getInt(key, 0); }

    public static int getInt(String key, int def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getInt(key, def);
        } catch (Throwable e) { return def; }
    }

    public static void putInt(String key, int value) {
        try { SharedPreferences p = prefs(); if (p != null) p.edit().putInt(key, value).apply(); } catch (Throwable ignored) { }
    }

    public static boolean getBoolean(String key) { return getBoolean(key, false); }

    public static boolean getBoolean(String key, boolean def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getBoolean(key, def);
        } catch (Throwable e) { return def; }
    }

    public static void putBoolean(String key, boolean value) {
        try { SharedPreferences p = prefs(); if (p != null) p.edit().putBoolean(key, value).apply(); } catch (Throwable ignored) { }
    }

    public static long getLong(String key, long def) {
        try {
            SharedPreferences p = prefs();
            return p == null ? def : p.getLong(key, def);
        } catch (Throwable e) { return def; }
    }

    public static void putLong(String key, long value) {
        try { SharedPreferences p = prefs(); if (p != null) p.edit().putLong(key, value).apply(); } catch (Throwable ignored) { }
    }

    public static void remove(String key) {
        try { SharedPreferences p = prefs(); if (p != null) p.edit().remove(key).apply(); } catch (Throwable ignored) { }
    }

    /** key 里带网盘名的，直接回宿主落盘的真 Cookie（插件最常见的读法）。 */
    private static String cookieLike(String key) {
        if (key == null) return null;
        String k = key.toLowerCase();
        String id = null;
        if (k.contains("quark") || k.contains("夸克")) id = "quark";
        else if (k.contains("uc")) id = "uc";
        else if (k.contains("ali")) id = "ali";
        else if (k.contains("baidu")) id = "baidu";
        if (id == null) return null;
        if (!(k.contains("cookie") || k.contains("token") || k.equals(id))) return null;
        return com.github.catvod.api.Pan.cookie(id);
    }

    static File filesDir() {
        Context ctx = Runtime.context();
        return ctx == null ? null : ctx.getFilesDir();
    }
}

package com.github.catvod.api;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;

/** 网盘 Cookie 的宿主落盘口径：插件侧按文件名自己找，宿主按同一批文件名写。 */
@Keep
public class Pan {

    private static final String TAG = "NextMovieCanary";

    public static String cookie(String id) {
        if (id == null) return "";
        for (String name : names(id)) {
            String c = read(new File(filesDir(), name));
            if (c != null && c.length() > 8) {
                Log.i(TAG, "Pan.cookie id=" + id + " file=" + name + " len=" + c.length());
                return c;
            }
        }
        return "";
    }

    public static String[] names(String id) {
        String lower = id.toLowerCase();
        if (lower.equals("quark")) return new String[]{"quark_cookie.txt", "quark.txt", ".quark", "cookie_quark.txt", "quark_cookie"};
        if (lower.equals("uc")) return new String[]{"uc_cookie.txt", "uc.txt", ".uc", "cookie_uc.txt", "uc_cookie"};
        if (lower.equals("ali")) return new String[]{"ali.txt", ".ali", "aliyundrive.txt", "ali_token.txt", "cookie_ali.txt"};
        if (lower.equals("baidu")) return new String[]{"baidu.txt", ".baidu", "bd_cookie.txt", "baidu_cookie.txt", "cookie_baidu.txt"};
        return new String[]{"cookie_" + lower + ".txt"};
    }

    private static File filesDir() {
        android.content.Context ctx = com.github.catvod.Runtime.context();
        return ctx == null ? null : ctx.getFilesDir();
    }

    private static String read(File f) {
        try {
            if (f == null || !f.isFile() || f.length() <= 0) return null;
            byte[] all = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = 0;
            while (n < all.length) {
                int r = in.read(all, n, all.length - n);
                if (r < 0) break;
                n += r;
            }
            in.close();
            return new String(all, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable e) {
            return null;
        }
    }
}

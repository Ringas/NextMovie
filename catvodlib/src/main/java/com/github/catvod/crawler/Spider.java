package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.github.catvod.Proxy;
import com.github.catvod.Runtime;
import com.github.catvod.net.Net;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Dialog;
import com.github.catvod.utils.Js;
import com.github.catvod.utils.Local;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.Dns;

public abstract class Spider implements AutoCloseable {

    public String siteKey;
    public Net net;
    public Local local;
    public final Js js = new Js();
    private final Dialog dialog = new Dialog();
    /** Marco 形态宿主会通过 initApi 注入 HTTP/代理出入口；插件侧按此拿 127.0.0.1:<port>。 */
    private SpiderApi api;
    private String token = "";

    public void initApi(SpiderApi api) {
        this.api = api;
    }

    public SpiderApi api() {
        return api;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token;
    }

    /**
     * Marco 的 Spider.client()：共享客户端 + TLS 兼容 + DoH。Guard 类插件按这个入口发网盘请求，
     * 缺失时会退回自带客户端（无 DoH / 严格证书链），在部分网络下直接卡到解析超时。
     */
    public static okhttp3.OkHttpClient client() {
        return SharedClient.INSTANCE;
    }

    private static final class SharedClient {
        static final okhttp3.OkHttpClient INSTANCE = build();

        private static okhttp3.OkHttpClient build() {
            okhttp3.OkHttpClient.Builder builder = com.github.catvod.net.OkHttp.client().newBuilder()
                    .dns(com.github.catvod.net.OkHttp.dns())
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true);
            try {
                javax.net.ssl.X509TrustManager trustAll = new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                };
                javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
                context.init(null, new javax.net.ssl.TrustManager[]{trustAll}, new java.security.SecureRandom());
                builder.sslSocketFactory(context.getSocketFactory(), trustAll)
                        .hostnameVerifier((hostname, session) -> hostname != null && !hostname.isEmpty());
            } catch (Exception ignored) {
            }
            return builder.build();
        }
    }

    public JSONObject dialog(JSONObject options) throws Exception {
        return dialog(options, null);
    }

    public JSONObject dialog(JSONObject options, Dialog.Callback callback) throws Exception {
        return dialog.show(options, callback);
    }

    public final void closeDialog() {
        dialog.close();
    }

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public Activity getActivity() {
        Activity activity = Runtime.activity();
        if (activity == null) android.util.Log.w("NextMoviePan", "getActivity -> null (host Runtime empty)");
        return activity;
    }

    public String proxyUrl() {
        return proxyUrl(Collections.emptyMap());
    }

    public String danmakuUrl(String id) {
        return danmakuUrl(siteKey, id);
    }

    public static String danmakuUrl(String siteKey, String id) {
        if (siteKey == null || siteKey.isEmpty() || id == null || id.isEmpty()) throw new IllegalArgumentException("Danmaku requires a site key and ID");
        return new Uri.Builder().scheme("spider").authority(siteKey).appendPath(id).build().toString();
    }

    public String proxyUrl(Map<String, String> params) {
        return proxyUrl(params, true);
    }

    public String proxyUrl(Map<String, String> params, boolean local) {
        if (params == null) params = Collections.emptyMap();
        Uri.Builder url = Uri.parse(Proxy.getUrl(local)).buildUpon();
        url.appendQueryParameter("do", "jar");
        url.appendQueryParameter("siteKey", siteKey);
        for (Map.Entry<?, ?> entry : params.entrySet()) appendProxyParam(url, entry.getKey(), entry.getValue());
        return url.build().toString();
    }

    private void appendProxyParam(Uri.Builder url, Object key, Object value) {
        if (!(key instanceof String name) || !(value instanceof String text)) throw new IllegalArgumentException("Proxy parameters require string keys and values");
        if (name.equals("do") || name.equals("siteKey")) throw new IllegalArgumentException("Reserved proxy parameter");
        url.appendQueryParameter(name, text);
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "{\"class\":[]}";
    }

    public String homeVideoContent() throws Exception {
        return "{\"list\":[]}";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "{\"list\":[]}";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "{\"list\":[]}";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "{\"list\":[]}";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "{}";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return new Object[]{404, "text/plain", new ByteArrayInputStream(new byte[0])};
    }

    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        return proxy(params);
    }

    public void cancelByTag() {
    }

    public String action(String action) throws Exception {
        return "{}";
    }

    public AutoCloseable danmaku(String id, Consumer<JSONObject> emit) throws Exception {
        throw new UnsupportedOperationException("Spider does not support live danmaku");
    }

    public void destroy() {
    }

    @Override
    public void close() {
        closeDialog();
        Throwable failure = closeJavaScript();
        failure = closeNetwork(failure);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private Throwable closeJavaScript() {
        try {
            js.close();
            return null;
        } catch (RuntimeException | Error error) {
            return error;
        }
    }

    private Throwable closeNetwork(Throwable failure) {
        try {
            if (net != null) net.close();
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }
}

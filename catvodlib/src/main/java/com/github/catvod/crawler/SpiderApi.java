package com.github.catvod.crawler;

import android.util.Log;

import com.github.catvod.Proxy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Marco 形态的宿主 HTTP/代理出入口。
 *
 * Guard 系插件在 init 阶段会通过 {@link Spider#initApi(SpiderApi)} 拿到这个对象，
 * 之后用它做两件事：
 * 1) getAddress()/getPort() 找宿主本地代理（分片回环接口），找不到才退化到硬编码 9978；
 * 2) multiReq(...) 批量发请求（网盘分享页 token/list 这类需要并发 + 自定义 TLS/DoH 的调用）。
 *
 * 缺少这一层时，插件只会用自带客户端，且本地代理端口探测失败，表现为 15s 级解析超时。
 */
public class SpiderApi {

    private static final String TAG = "NextMovieSpiderApi";
    private static final int MAX_BATCH = 128;

    public String getAddress(boolean local) {
        return "http://127.0.0.1:" + Proxy.getPort() + "/";
    }

    public String getPort() {
        return String.valueOf(Proxy.getPort());
    }

    public void log(String text) {
        Log.i(TAG, "plugin " + text);
    }

    /** 网页解析；插件主线不依赖，返回空串以保持“无能力”的显式语义。 */
    public String webParse(String url, String html) {
        return "";
    }

    public String multiReq(JsonArray requests) {
        if (requests == null || requests.size() == 0) return "[]";
        JsonArray out = new JsonArray();
        int count = Math.min(requests.size(), MAX_BATCH);
        for (int i = 0; i < count; i++) out.add(executeSingleRequest(requests.get(i)));
        return out.toString();
    }

    public static JsonObject executeSingleRequest(JsonElement element) {
        String url = extractUrl(element);
        JsonObject opts = element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        String method = optString(opts, "method", "GET").toUpperCase();
        int timeoutMs = optInt(opts, "timeout", 15000);
        try {
            OkHttpClient client = Spider.client().newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .followRedirects(optBool(opts, "redirect", true))
                    .build();
            Request.Builder builder = new Request.Builder().url(url);
            JsonObject headers = opts.has("headers") && opts.get("headers").isJsonObject() ? opts.getAsJsonObject("headers") : null;
            if (headers != null) for (Map.Entry<String, JsonElement> e : headers.entrySet()) {
                JsonElement v = e.getValue();
                if (v != null && !v.isJsonNull()) builder.header(e.getKey(), v.getAsString());
            }
            RequestBody body = null;
            String raw = optString(opts, "body", optString(opts, "data", optString(opts, "postData", "")));
            if (!raw.isEmpty() && !"GET".equals(method) && !"HEAD".equals(method)) {
                String type = optString(opts, "postType", "application/json; charset=utf-8");
                body = RequestBody.create(MediaType.parse(type), raw.getBytes(Charset.forName("UTF-8")));
            }
            builder.method(method, body);
            try (Response response = client.newCall(builder.build()).execute()) {
                JsonObject result = new JsonObject();
                result.addProperty("code", response.code());
                result.addProperty("content", response.body() == null ? "" : response.body().string());
                JsonObject outHeaders = new JsonObject();
                Headers rh = response.headers();
                for (String name : rh.names()) outHeaders.addProperty(name, rh.get(name));
                result.add("headers", outHeaders);
                result.addProperty("url", response.request().url().toString());
                return result;
            }
        } catch (Exception e) {
            Log.w(TAG, "req failed " + url + " " + e);
            JsonObject failure = new JsonObject();
            failure.addProperty("code", 0);
            failure.addProperty("content", "");
            failure.add("headers", new JsonObject());
            failure.addProperty("url", url);
            failure.addProperty("error", e.toString());
            return failure;
        }
    }

    private static String extractUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (!element.isJsonObject()) return "";
        JsonObject object = element.getAsJsonObject();
        String url = optString(object, "url", "");
        return url.isEmpty() ? optString(object, "address", "") : url;
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        return value.getAsString();
    }

    private static int optInt(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean optBool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : value.getAsInt() != 0;
        } catch (Exception e) {
            return fallback;
        }
    }
}

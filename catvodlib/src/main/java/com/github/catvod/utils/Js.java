package com.github.catvod.utils;

import java.util.concurrent.CompletableFuture;

/**
 * 本宿主未内置 JS 运行时（quickjs/chaquo 已从工程移除），保留该类以满足插件 ABI。
 * 需要脚本能力的站点会在调用时得到明确的“不支持”异常，而不是 NoClassDefFoundError。
 */
@androidx.annotation.Keep
public final class Js implements AutoCloseable {

    public String eval(String source) throws Exception {
        return eval(source, "{}");
    }

    public String eval(String source, String options) throws Exception {
        throw new UnsupportedOperationException("JS runtime not bundled in NextMovie host");
    }

    public CompletableFuture<String> evalAsync(String source, String options) {
        CompletableFuture<String> result = new CompletableFuture<>();
        result.completeExceptionally(new UnsupportedOperationException("JS runtime not bundled in NextMovie host"));
        return result;
    }

    @Override
    public void close() {
    }
}

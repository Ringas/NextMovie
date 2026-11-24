# 插件宿主 ABI 必须保留，第三方 JAR 会反射访问这些符号
-keep class com.github.catvod.crawler.** { *; }
-keep class com.github.catvod.spider.** { *; }

# 反射调用的插件方法名
-keepclassmembers class * {
    public java.lang.String homeContent(boolean);
    public java.lang.String homeVideoContent();
    public java.lang.String categoryContent(java.lang.String, java.lang.String, boolean, java.util.HashMap);
    public java.lang.String detailContent(java.util.List);
    public java.lang.String searchContent(java.lang.String, boolean);
    public java.lang.String searchContent(java.lang.String, boolean, java.lang.String);
    public java.lang.String playerContent(java.lang.String, java.lang.String, java.util.List);
}

# QuickJsJobs reads the runtime pointer of the pinned Android binding.
-keepclassmembers class com.quickjs.QuickJS {
    long runtimePtr;
}

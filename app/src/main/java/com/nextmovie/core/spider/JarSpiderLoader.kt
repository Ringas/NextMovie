package com.nextmovie.core.spider

import android.content.Context
import com.github.catvod.crawler.Spider
import com.nextmovie.core.config.Site
import com.nextmovie.core.net.Http
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * JAR 插件加载器（独立实现）。
 *
 * 流程：下载 jar → 落盘缓存 → DexClassLoader → 反射实例化 `com.github.catvod.spider.<Name>`。
 * 由 :spider 服务持有，插件崩溃与主界面隔离。
 */
class JarSpiderLoader(
    private val context: Context,
    private val http: Http
) {

    class SpiderLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 按 jar 分锁：同一份 jar 内的站点串行初始化（避免 Guard 原生代理抢同一个端口），
     * 不同 jar 可以并行装载。原来的全局单锁会让详情页 12 个站点的首次初始化互相排队。
     */
    private val loadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun lockFor(site: Site): Mutex =
        loadLocks.getOrPut(site.jar.ifBlank { site.api }) { Mutex() }
    private val jarDir = File(context.filesDir, "spiders").apply { mkdirs() }
    private val dexDir = File(context.codeCacheDir, "spider-dex").apply { mkdirs() }
    private val instances = java.util.concurrent.ConcurrentHashMap<String, Spider>()
    private val classLoaders = java.util.concurrent.ConcurrentHashMap<String, ClassLoader>()
    private val initialized = HashSet<String>()
    private val initializedProxies = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()

    @Synchronized
    fun release() {
        instances.values.forEach { runCatching { it.destroy() } }
        instances.clear()
        initialized.clear()
        initializedProxies.clear()
        classLoaders.clear()
        // Keep optimized dex across process restarts; it is disposable code cache, not runtime state.
    }

    suspend fun obtain(site: Site): Spider = withContext(Dispatchers.IO) {
        try {
            lockFor(site).withLock { obtainInner(site) }
        } catch (t: Throwable) {
            PluginDiag.record(context, site.name, site.api, false, t.message)
            throw t
        }
    }

    private suspend fun obtainInner(site: Site): Spider = withContext(Dispatchers.IO) {
        val api = site.api
        if (!api.startsWith("csp_")) {
            throw SpiderLoadException("v0 只支持 csp_ 前缀的 JAR 插件，当前是 $api")
        }
        instances[site.runtimeKey]?.let { return@withContext it }

        val lifecycleBefore = PluginLifecycle.snapshot(context)
        val jarUrl = site.jar.ifBlank { api }
        if (!jarUrl.startsWith("http://") && !jarUrl.startsWith("https://")) {
            throw SpiderLoadException("站点「${site.name}」没有可下载的插件地址（api=$api）")
        }
        val artifactStart = System.nanoTime()
        fun step(name: String) = android.util.Log.i(
            "NextMovieTiming",
            "plugin-step $name ${(System.nanoTime() - artifactStart) / 1_000_000}ms"
        )
        val jarFile = ensureJar(jarUrl, site.name, site.jarHash)
        android.util.Log.i("NextMovieTiming", "plugin-artifact ${(System.nanoTime() - artifactStart) / 1_000_000}ms")

        // 先做 ABI 预检：Guard 插件通常只带 ARM 的 .so，x86 模拟器上 dlopen 必失败，
        // MuMu 这类带 ARM 翻译的 x86 环境还会直接 SIGSEGV。提前拦下可以给出准确文案并避免原生崩溃。
        step("jar-ready")
        abiMismatch(jarFile)?.let { throw SpiderLoadException(it) }

        // Guard 系插件把 native 库和加密的 secondary dex 放在 jar 的 assets 里：
        //   assets/ftyguard_v8.so  → JNI_OnLoad 里注册 com.github.catvod.spider.DexNative.getSpider
        //   assets/ftyshinidie.guard → 由该 native 方法解密出的二级 dex（真正的 detail/play 实现）
        // .so 必须“以插件 ClassLoader 的身份”加载，否则 JNI_OnLoad 里 FindClass(DexNative)
        // 只会在宿主 classpath 里找，找不到就直接 abort（MuMu 上表现为插件进程闪退）。
        val libDir = File(context.filesDir, "spider-libs/" + jarFile.nameWithoutExtension).apply { mkdirs() }
        val nativeFile = extractNativeLib(jarFile, libDir)
        step("native-extracted")
        val bridgeDex = ensureBridgeDex()

        val loader = classLoaders.getOrPut(jarFile.absolutePath) {
            val dexPath = if (bridgeDex != null) {
                jarFile.absolutePath + File.pathSeparator + bridgeDex.absolutePath
            } else {
                jarFile.absolutePath
            }
            DexClassLoader(dexPath, dexDir.absolutePath, libDir.absolutePath, context.classLoader)
        }

        step("dexloader")
        val hasDexNative = runCatching {
            Class.forName("com.github.catvod.spider.DexNative", false, loader)
        }.isSuccess
        val native = if (hasDexNative) {
            // Guard 插件在 DexNative.<clinit> 里自己把 assets 的 .so 拷到 cacheDir 再 System.load，
            // 那条路径下 JNI_OnLoad 能拿到完整的 Init 状态（context / classLoader）。
            // 宿主抢先 load 会让 JNI_OnLoad 在 Init 尚未就绪时执行，原生方法注册不上，
            // 表现为 DexNative.getSpider “No implementation found”。所以这里不再抢先加载。
            android.util.Log.i("NextMovie", "native 由插件自载（DexNative<clinit>），宿主跳过")
            NativeLoad(emptyList(), emptyList(), translationLayer())
        } else {
            loadNativeViaBridge(loader, nativeFile)
        }

        // CatVod 插件自带的 Init.init(Context) 负责把 secondary dex / 动态库准备好，
        // 不调用它，Guard 系插件（csp_*Guard）基本都会构造失败，所以每个 jar 只调一次。
        step("pre-init")
        var initError: Throwable? = null
        PluginHostState.publishConfig(context, site.origin)
        val needsInit = !initialized.contains(jarFile.absolutePath)
        if (needsInit) {
            initError = runCatching {
                val initClass = Class.forName("com.github.catvod.spider.Init", true, loader)
                initClass.declaredMethods.forEach {
                    android.util.Log.d("NextMovieInit", "Init." + it.name + it.parameterTypes.joinToString(",") { p -> p.simpleName })
                }
                val method = initClass.declaredMethods.firstOrNull {
                    it.name == "init" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Context::class.java
                }
                step("init-entry")
                if (method == null) {
                    // 少数包签名是 init(Context, String)（String 通常是插件 jar 的绝对路径）
                    val strInit = initClass.declaredMethods.firstOrNull {
                        it.name == "init" &&
                            it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == Context::class.java &&
                            it.parameterTypes[1] == String::class.java
                    }
                    if (strInit != null) {
                        strInit.invoke(null, context, jarFile.absolutePath)
                    } else {
                        initClass.declaredMethods.firstOrNull {
                            it.name == "init" && it.parameterTypes.firstOrNull() == Context::class.java
                        }?.invoke(null, context, true)
                    }
                } else {
                    method.invoke(null, context)
                }
                // Guard 插件真正的“准备”入口是 Init.<混淆名>(File)：它解密 assets 里的
                // ftyshinidie.guard 二级 dex 并注册 DexNative 的 native 方法。
                // 只调 init(Context) 不够——那样 detailContent/searchContent/playerContent
                // 会永远是空串（home/category 在主 dex 里才正常）。
                runCatching {
                    val cl = initClass.getMethod("classLoader").invoke(null) as? ClassLoader
                    android.util.Log.d("NextMovieInit", "classLoader=" + cl?.javaClass?.name +
                        " res=" + (cl?.getResource("assets/ftyguard_v8.so") != null))
                }
                initClass.declaredMethods
                    .firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java }
                    ?.let { jarInit ->
                        android.util.Log.d("NextMovieInit", "Init.jarFile -> " + jarInit.name)
                        runCatching { jarInit.isAccessible = true; jarInit.invoke(null, jarFile) }
                            .onSuccess { android.util.Log.d("NextMovieInit", "Init.jarFile ok") }
                            .onFailure { android.util.Log.w("NextMovieInit", "Init.jarFile failed: " + rootCause(it), it) }
                    }
            }.onSuccess {
                android.util.Log.d("NextMovie", "spider Init ok: ${jarFile.name}")
            }.onFailure {
                android.util.Log.w("NextMovie", "spider Init failed: ${rootCause(it)}", it)
            }.exceptionOrNull()
            step("init-done")
        }

        // Initialization belongs to the JAR class loader, once only. Re-running arbitrary
        // load/prepare methods may mutate or destroy a working plugin runtime.
        if (initError != null) throw SpiderLoadException("插件初始化失败：" + rootCause(initError), initError)

        val className = "com.github.catvod.spider." + api.removePrefix("csp_").substringBefore('?')
        val instance = try {
            // 优先走插件自己的 Init.getSpider(name)：它会从二级 dex（真正的实现）里取实例；
            // 直接 Class.forName 拿到的是主 dex 里的空壳，detail/play 全是空串。
            val viaInit = runCatching {
                val initClass = Class.forName("com.github.catvod.spider.Init", true, loader)
                val getSpider = initClass.declaredMethods.firstOrNull {
                    it.name == "getSpider" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                getSpider?.apply { isAccessible = true }?.invoke(null, className) as? Spider
            }.getOrNull()
            viaInit ?: run {
                val clazz = Class.forName(className, true, loader)
                clazz.getDeclaredConstructor().newInstance() as Spider
            }
        } catch (e: Throwable) {
            val hint = when (e) {
                is UnsatisfiedLinkError -> "原生库加载失败（Guard .so 与设备 ABI 不匹配或未解出）"
                is ExceptionInInitializerError -> "类初始化失败（通常是 Guard 的 native/二级 dex 未就绪）"
                is NoClassDefFoundError -> "类未找到（插件包可能不含该类或二级 dex 未解出）"
                is InstantiationException -> "构造函数不可用（插件 ABI 与宿主不匹配）"
                else -> "构造或静态初始化失败"
            }
            val initNote = initError?.let { "；Init 失败：" + rootCause(it) } ?: ""
            throw SpiderLoadException(
                "「${site.name}」插件加载失败：$hint$initNote\n类：$className\n${native.describe()}\n根因：" + rootCause(e),
                e
            )
        }

        initialized.add(jarFile.absolutePath)
        android.util.Log.i("NextMoviePlugin", "class=" + instance.javaClass.name + " init2=" + instance.javaClass.getMethod("init", Context::class.java, String::class.java).declaringClass.name + " init1=" + instance.javaClass.getMethod("init", Context::class.java).declaringClass.name)
        instance.siteKey = site.key
        try {
            SpiderInitializer.initialize(instance, context, site.ext)
        } catch (e: Throwable) {
            throw SpiderLoadException("插件 init 失败：$className", e)
        }

        PluginLifecycle.catchUp(context, lifecycleBefore)
        instances[site.runtimeKey] = instance
        PluginDiag.record(context, site.name, site.api, true, "Init ok，实例已就绪")
        instance
    }

    /** Older plugins keep their own Init singleton instead of consulting Spider.getActivity(). */
    fun bindActivity(site: Site) {
        val host = PluginDialogs.current() ?: return
        val loaders = (classLoaders.values + listOfNotNull(instances[site.runtimeKey]?.javaClass?.classLoader)).distinct()
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                for (loader in loaders) for (name in listOf("Init", "InitOrigin", "AwInit")) runCatching {
                    val cls = Class.forName("com.github.catvod.spider.$name", false, loader)
                    val instance = runCatching { cls.getDeclaredMethod("get").apply { isAccessible = true }.invoke(null) }.getOrNull()
                    for (field in cls.declaredFields) runCatching {
                        val owner = if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else instance ?: return@runCatching
                        field.isAccessible = true
                        when {
                            android.app.Activity::class.java.isAssignableFrom(field.type) && field.type.isInstance(host) -> field.set(owner, host)
                            field.type == java.lang.ref.WeakReference::class.java -> {
                                val old = (field.get(owner) as? java.lang.ref.WeakReference<*>)?.get()
                                if (old is android.app.Activity || field.name.contains("activity", true)) field.set(owner, java.lang.ref.WeakReference(host))
                            }
                        }
                    }
                }
            } finally { done.countDown() }
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun proxyLoaders(site: Site): List<ClassLoader> {
        val roots = listOfNotNull(instances[site.runtimeKey]?.javaClass?.classLoader) + classLoaders.values
        val all = LinkedHashSet<ClassLoader>()
        roots.forEach { root ->
            all.add(root)
            runCatching {
                val init = Class.forName("com.github.catvod.spider.Init", false, root)
                (init.getMethod("classLoader").invoke(null) as? ClassLoader)?.let { all.add(it) }
            }
        }
        return all.toList()
    }

    /** Publish the callback server before playerContent, which may use it synchronously. */
    fun prepareProxy(site: Site, port: Int) {
        for (loader in proxyLoaders(site)) for (name in listOf("Proxy", "ProxyOrigin")) runCatching {
            val cls = Class.forName("com.github.catvod.spider.$name", false, loader)
            runCatching { cls.getMethod("set", Int::class.javaPrimitiveType).invoke(null, port) }
            if (initializedProxies.add(cls)) runCatching { cls.getMethod("init").invoke(null) }
        }
    }

    /**
     * 把宿主扫码拿到的网盘 Cookie 交给来源。
     * 来源实现不统一，这里按常见写法依次尝试：`api.<Pan>.setCookie`、`utils.Pref.putString`。
     */
    fun injectPanCookie(site: Site, panId: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val apiNames = when (panId.lowercase()) {
            "quark" -> listOf("Quark")
            "uc" -> listOf("UC", "Uc")
            "ali", "aliyun" -> listOf("Ali", "AliYun", "Aliyun")
            "baidu" -> listOf("Baidu", "BaiduPan")
            else -> emptyList()
        }
        var done = false
        for (loader in proxyLoaders(site)) {
            for (name in apiNames) {
                for (pkg in listOf("com.github.catvod.api.", "com.github.catvod.spider.")) {
                    val cls = runCatching { Class.forName(pkg + name, false, loader) }.getOrNull() ?: continue
                    for (m in cls.methods.filter { it.name.equals("setCookie", true) || it.name.equals("putCookie", true) }) {
                        runCatching {
                            when (m.parameterTypes.size) {
                                1 -> m.invoke(null, cookie)
                                2 -> m.invoke(null, panId, cookie)
                            }
                            done = true
                            android.util.Log.i("NextMoviePan", "inject ok ${pkg + name}#${m.name}")
                        }.onFailure {
                            android.util.Log.i("NextMoviePan", "inject fail ${pkg + name}#${m.name}: ${it.cause?.message ?: it.message}")
                        }
                    }
                }
            }
            // FongMi 风格：Pref.putString("cookie_quark", cookie)
            runCatching {
                val pref = Class.forName("com.github.catvod.utils.Pref", false, loader)
                val put = pref.methods.firstOrNull { it.name == "putString" && it.parameterTypes.size == 2 }
                if (put != null) {
                    for (key in listOf("cookie_$panId", "cookie_${panId.lowercase()}", panId)) {
                        runCatching { put.invoke(null, key, cookie) }
                    }
                    done = true
                    android.util.Log.i("NextMoviePan", "inject ok Pref.putString")
                }
            }.onFailure {
                android.util.Log.i("NextMoviePan", "inject fail Pref: ${it.cause?.message ?: it.message}")
            }
        }
        return done
    }

    fun proxy(site: Site, params: Map<String, String>): Array<*>? {
        val instance = instances[site.runtimeKey] ?: return null
        val direct = runCatching { instance.proxy(HashMap(params)) }
        direct.getOrNull()?.let { return it }
        var failure: Throwable? = direct.exceptionOrNull()
        for (loader in proxyLoaders(site)) {
            val response = runCatching {
                val proxy = Class.forName("com.github.catvod.spider.Proxy", true, loader)
                proxy.getMethod("proxy", Map::class.java).invoke(null, HashMap(params)) as? Array<*>
            }
            response.getOrNull()?.let { return it }
            failure = response.exceptionOrNull() ?: failure
        }
        if (failure != null) throw IllegalStateException("来源代理调用失败", failure)
        return null
    }

    private suspend fun ensureJar(raw: String, siteName: String, expected: String = ""): File {
        // 配置里可能写成 "https://x/tv/x.jpg;md5;<hash>"：地址是伪装成图片的 jar，
        // 末尾 hash 是配置作者给的校验值。必须校验通过才加载，
        // 否则被换包 / 半途截断时表现为“插件崩溃”，排查成本极高。
        val spec = JarSpec.parse(raw)
        val hash = spec.hash.ifBlank { expected }
        val name = md5(spec.url + "|" + hash) + ".jar"
        val target = File(jarDir, name)
        val stamp = File(jarDir, "$name.md5")
        if (target.length() > 0) {
            if (hash.isBlank()) return target
            val cached = runCatching { stamp.readText().trim() }.getOrDefault("")
            if (cached.equals(hash, ignoreCase = true)) return target
            // 标记对不上（旧包或没写过标记）→ 重新下载，不直接信任磁盘上的文件
        }

        val bytes = runCatching { http.bytes(spec.url) }.getOrElse {
            throw SpiderLoadException("「$siteName」的插件下载失败：${it.message ?: it.javaClass.simpleName}", it)
        }
        val material = runCatching {
            com.nextmovie.core.config.JarBytes.materialize(bytes, hash)
        }.getOrElse { err ->
            throw SpiderLoadException(
                "「$siteName」" + (err.message ?: "插件无效"),
                err
            )
        }
        val temp = File(jarDir, "$name.tmp")
        temp.writeBytes(material.jar)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        if (hash.isNotBlank()) runCatching { stamp.writeText(hash) }
        return target
    }

    /** `url;md5;hash` → 地址 + 可选校验值；也兼容 `url;hash` 这种省略写法。 */
    private data class JarSpec(val url: String, val hash: String) {
        companion object {
            fun parse(raw: String): JarSpec {
                val (url, hash) = com.nextmovie.core.config.PluginArtifact.split(raw)
                return JarSpec(url, hash)
            }
        }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { b -> "%02x".format(b) }

    /** 从 jar 的 assets 里挑出与本进程 ABI 匹配的那个 .so，落到私有目录。 */
    private fun extractNativeLib(jarFile: File, dir: File): File? = runCatching {
        ZipFile(jarFile).use { zip ->
            // Guard 插件同时带 v7(ARM32) 与 v8(AArch64)，按进程位数只取一个：
            // 在 MuMu 这类带 ARM 翻译层的 x86 设备上同时加载两个会直接 SIGSEGV。
            val candidates = zip.entries().asSequence()
                .filter { it.name.endsWith(".so") && it.size > 0 }
                .map { it.name.substringAfterLast('/') to it }
                .toList()
            val chosen = pickNativeForProcess(candidates.map { (name, _) -> name }) ?: return@use null
            android.util.Log.i("NextMovie", "pick native: $chosen（候选：${candidates.joinToString(",") { it.first }}）")
            val entry = candidates.first { it.first == chosen }.second
            // 只用文件名落盘：zip 条目名是 assets/ftyguard_v8.so，
            // 若直接 File(dir, entry.name) 会去写 <dir>/assets/… 而这个目录不存在，抛 ENOENT。
            val out = File(dir, chosen)
            out.parentFile?.mkdirs()
            if (!out.exists() || out.length() != entry.size) {
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
    }.getOrElse {
        android.util.Log.w("NextMovie", "解包原生库失败: ${rootCause(it)}")
        null
    }

    /** 宿主侧的桥 dex（只有一个 System.load），跟着插件 ClassLoader 一起加载。 */
    private fun ensureBridgeDex(): File? = runCatching {
        val out = File(context.filesDir, "spiderbridge.dex")
        if (!out.exists() || out.length() == 0L) {
            context.assets.open("spiderbridge.dex").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        out
    }.getOrElse {
        android.util.Log.w("NextMovie", "bridge dex 准备失败: ${rootCause(it)}")
        null
    }

    /** 用插件 ClassLoader 里的 NativeBridge 去 System.load，保证 JNI_OnLoad 能解析插件类。 */
    private fun loadNativeViaBridge(loader: ClassLoader, lib: File?): NativeLoad {
        val okNames = ArrayList<String>()
        val failures = ArrayList<String>()
        if (lib != null) {
            val abi = elfAbi(lib)
            val viaBridge = runCatching {
                val bridge = Class.forName("com.nextmovie.bridge.NativeBridge", true, loader)
                bridge.getMethod("load", String::class.java).invoke(null, lib.absolutePath)
            }
            // 桥不可用时退回宿主加载（普通 jar 通常也能过，Guard jar 会 abort，故只在桥缺失时尝试）
            val result = viaBridge
            result
                .onSuccess {
                    okNames += "${lib.name}($abi)"
                    android.util.Log.i("NextMovie", "loaded native: ${lib.name} abi=$abi")
                }
                .onFailure {
                    failures += "${lib.name}($abi)：${rootCause(it)}"
                    android.util.Log.e("NextMovie", "load native failed ${lib.name} abi=$abi: ${rootCause(it)}", it)
                }
        }
        return NativeLoad(okNames, failures, translationLayer())
    }

    /** 原生库状态：给 UI 一个能看懂的 ABI 说明，避免只说「构造失败」。 */
    class NativeLoad(
        val loaded: List<String> = emptyList(),
        val failures: List<String> = emptyList(),
        val translation: String? = null
    ) {
        fun describe(): String {
            val device = android.os.Build.SUPPORTED_ABIS.joinToString(",")
            val bridge = translation?.takeIf { it.isNotBlank() && it != "0" }
            return when {
                failures.isNotEmpty() ->
                    "原生库加载失败：" + failures.joinToString("；") +
                        "（设备 ABI：$device${bridge?.let { "，翻译层：$it" } ?: ""}）"
                loaded.isNotEmpty() ->
                    "原生库已加载：" + loaded.joinToString(",") + "（设备 ABI：$device）"
                else -> "插件不含原生库（设备 ABI：$device）"
            }
        }
    }

    /** 按进程位数挑一个最合适的原生库；返回 null 表示这个插件在本机没有可用库。 */
    private fun pickNativeForProcess(names: List<String>): String? {
        val is64 = android.os.Process.is64Bit()
        val want = if (is64) listOf("v8", "x86_64") else listOf("v7", "x86")
        for (token in want) {
            names.firstOrNull { it.contains(token, ignoreCase = true) }?.let { return it }
        }
        return names.firstOrNull()
    }

    /** 有些 x86 模拟器（MuMu / 夜神）靠 libndk / libhoudini 翻译 ARM 指令，读一下桥接属性。 */
    private fun translationLayer(): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val get = c.getMethod("get", String::class.java)
        (get.invoke(null, "ro.dalvik.vm.native.bridge") as? String)?.takeIf { it.isNotBlank() && it != "0" }
            ?: runCatching {
                (get.invoke(null, "ro.product.cpu.abilist") as? String)
            }.getOrNull()
    }.getOrNull()

    /** 读 ELF 头判断 .so 的 CPU 架构，用于区分「arm 库跑在 x86 模拟器」这类问题。 */
    private fun elfAbi(file: File): String =
        runCatching { file.inputStream().use { elfAbi(it) } }.getOrElse { "读取失败" }

    private fun elfAbi(input: java.io.InputStream): String {
        val head = ByteArray(20)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < 20 || head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte()) return "非 ELF"
        return when (head[18].toInt() and 0xFF) {
            0x03 -> "x86"
            0x28 -> "ARM"
            0x3E -> "x86_64"
            0xB7 -> "AArch64"
            else -> "unknown"
        }
    }

    /** 插件里的原生库与当前设备 ABI 是否完全不兼容；兼容返回 null。 */
    private fun abiMismatch(jarFile: File): String? {
        val libs = runCatching {
            ZipFile(jarFile).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".so") && it.size > 0 }
                    .map { it.name.substringAfterLast('/') to zip.getInputStream(it).use(::elfAbi) }
                    .toList()
            }
        }.getOrElse { return null }
        if (libs.isEmpty()) return null

        val deviceAbis = android.os.Build.SUPPORTED_ABIS.toList()
        val fits = libs.any { (_, abi) -> deviceAbis.any { dev -> abiMatches(abi, dev) } }
        if (fits) return null

        val libDesc = libs.joinToString("、") { (name, abi) -> "$name=$abi" }
        val bridge = translationLayer()
        val hint = if (bridge.isNullOrBlank()) {
            "当前镜像没有 ARM 翻译层（纯 x86 系统镜像），ARM 指令无法执行。\n" +
                "出路：① ARM 真机（电视盒子 / 手机）；② 带 libndk/libhoudini 的模拟器（如 MuMu）；③ 换不需要插件的站点。"
        } else {
            "检测到 ARM 翻译层（$bridge），但库与进程位数不匹配。\n" +
                "请换 64 位镜像或 ARM 真机。"
        }
        return "「插件只有 ARM 原生库（$libDesc），当前设备 ABI：${deviceAbis.joinToString("/")}」\n" + hint
    }

    private fun abiMatches(libAbi: String, deviceAbi: String): Boolean = when (libAbi) {
        "ARM" -> deviceAbi.startsWith("armeabi")
        "AArch64" -> deviceAbi == "arm64-v8a"
        "x86" -> deviceAbi == "x86"
        "x86_64" -> deviceAbi == "x86_64"
        else -> false
    }

    private fun rootCause(e: Throwable): String {
        val parts = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            val m = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.name
            if (parts.none { it == m }) parts += m
            t = t.cause
            depth++
        }
        return parts.joinToString(" ← ").take(300)
    }

    private fun ByteArray.startsWithZip(): Boolean =
        size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

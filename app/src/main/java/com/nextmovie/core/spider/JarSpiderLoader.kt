package com.nextmovie.core.spider

import android.content.Context
import com.github.catvod.crawler.Spider
import com.nextmovie.core.config.Site
import com.nextmovie.core.net.Http
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * JAR 插件加载器（独立实现）。
 *
 * 流程：下载 jar → 落盘缓存 → DexClassLoader → 反射实例化 `com.github.catvod.spider.<Name>`。
 * v0 仍在主进程内加载；P1 会迁到 `:spider` 子进程，避免插件崩溃带走 UI。
 */
class JarSpiderLoader(
    private val context: Context,
    private val http: Http
) {

    class SpiderLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val jarDir = File(context.filesDir, "spiders").apply { mkdirs() }
    private val dexDir = File(context.codeCacheDir, "spider-dex").apply { mkdirs() }
    private val instances = HashMap<String, Spider>()
    private val classLoaders = HashMap<String, ClassLoader>()
    private val initialized = HashSet<String>()

    @Synchronized
    fun release() {
        instances.clear()
        classLoaders.clear()
        dexDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun obtain(site: Site): Spider = withContext(Dispatchers.IO) {
        try {
            obtainInner(site)
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
        instances[site.key]?.let { return@withContext it }

        val jarUrl = site.jar.ifBlank { api }
        if (!jarUrl.startsWith("http://") && !jarUrl.startsWith("https://")) {
            throw SpiderLoadException("站点「${site.name}」没有可下载的插件地址（api=$api）")
        }
        val jarFile = ensureJar(jarUrl, site.name, site.jarHash)

        // 先做 ABI 预检：Guard 插件通常只带 ARM 的 .so，x86 模拟器上 dlopen 必失败，
        // MuMu 这类带 ARM 翻译的 x86 环境还会直接 SIGSEGV。提前拦下可以给出准确文案并避免原生崩溃。
        abiMismatch(jarFile)?.let { throw SpiderLoadException(it) }

        // Guard 系插件把 native 库和加密的 secondary dex 放在 jar 的 assets 里：
        //   assets/ftyguard_v8.so  → JNI_OnLoad 里注册 com.github.catvod.spider.DexNative.getSpider
        //   assets/ftyshinidie.guard → 由该 native 方法解密出的二级 dex（真正的 detail/play 实现）
        // .so 必须“以插件 ClassLoader 的身份”加载，否则 JNI_OnLoad 里 FindClass(DexNative)
        // 只会在宿主 classpath 里找，找不到就直接 abort（MuMu 上表现为插件进程闪退）。
        val libDir = File(context.filesDir, "spider-libs/" + jarFile.nameWithoutExtension).apply { mkdirs() }
        val nativeFile = extractNativeLib(jarFile, libDir)
        // Guard 的二级 dex 加密体（assets/ftyshinidie.guard）也落盘：
        // 部分插件的 <混淆名>(File) 入口要的是这个 payload，而不是 jar 自身。
        val guardFile = extractAssetEntry(jarFile, ".guard", libDir, "payload.guard")
        val bridgeDex = ensureBridgeDex()

        val loader = classLoaders.getOrPut(site.key) {
            val dexPath = if (bridgeDex != null) {
                jarFile.absolutePath + File.pathSeparator + bridgeDex.absolutePath
            } else {
                jarFile.absolutePath
            }
            DexClassLoader(dexPath, dexDir.absolutePath, libDir.absolutePath, context.classLoader)
        }

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
        var initError: Throwable? = null
        if (initialized.add(jarFile.absolutePath)) {
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
        }

        // Init 的入口签名各家不一样（init(Context) / init(Context, String) / <混淆名>(File)），
        // 单挑一个试很可能漏掉真正“解二级 dex”的那个。这里把可疑入口全部走一遍，
        // 结果写盘，配上详情页的原始返回就能确定是哪一步没成。
        if (initialized.contains(jarFile.absolutePath)) {
            val results = ArrayList<String>()
            runCatching {
                val initClass = Class.forName("com.github.catvod.spider.Init", true, loader)
                val sig = initClass.declaredMethods.joinToString(", ") { m ->
                    m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                }
                results += "签名: " + sig.take(600)
                initClass.declaredMethods.forEach { m ->
                    if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) return@forEach
                    if (m.parameterTypes.isEmpty() || m.parameterTypes.size > 2) return@forEach
                    val lname = m.name.lowercase()
                    // 只吃一个 File 的方法几乎就是「解密二级 dex」的入口，名字全是混淆字符
                    // （oOoOoOoOoOoOoO0o），按名字过滤会永远漏掉它，detail/play 就只能拿到空串。
                    val fileEntry = m.parameterTypes.size == 1 && m.parameterTypes[0] == File::class.java
                    if (!fileEntry && !(lname.contains("init") || lname.contains("load") || lname.contains("prepare") || lname.contains("guard"))) return@forEach
                    val attempts: List<Array<Any?>> = when {
                        fileEntry -> buildList {
                            add(arrayOf<Any?>(jarFile))
                            guardFile?.let { add(arrayOf<Any?>(it)) }
                        }
                        m.parameterTypes[0] == Context::class.java && m.parameterTypes.size == 1 -> listOf(arrayOf<Any?>(context))
                        m.parameterTypes[0] == Context::class.java && m.parameterTypes[1] == String::class.java ->
                            listOf(arrayOf<Any?>(context, jarFile.absolutePath))
                        m.parameterTypes[0] == Context::class.java && m.parameterTypes[1].name == "boolean" ->
                            listOf(arrayOf<Any?>(context, true))
                        m.parameterTypes[0] == File::class.java && m.parameterTypes.size == 2 -> listOf(arrayOf<Any?>(jarFile, context))
                        m.parameterTypes[0] == String::class.java -> listOf(arrayOf<Any?>(jarFile.absolutePath))
                        else -> return@forEach
                    }
                    val tag = m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"
                    attempts.forEachIndexed { i, args ->
                        val r = runCatching { m.isAccessible = true; m.invoke(null, *args) }
                        val who = if (attempts.size > 1) tag + "#" + (if (i == 0) "jar" else "payload") else tag
                        results += who + " = " + if (r.isSuccess) "ok" else "fail: " + rootCause(r.exceptionOrNull()!!).take(80)
                    }
                }
            }
            PluginDiag.saveInit(context, results.joinToString("\n"))
            android.util.Log.d("NextMovieInit", results.joinToString(" | "))
        }

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

        instance.siteKey = site.key
        try {
            instance.init(context, site.ext.takeIf { it.isNotBlank() })
        } catch (e: Throwable) {
            throw SpiderLoadException("插件 init 失败：$className", e)
        }

        instances[site.key] = instance
        PluginDiag.record(context, site.name, site.api, true, "Init ok，实例已就绪")
        instance
    }

    private suspend fun ensureJar(raw: String, siteName: String, expected: String = ""): File {
        // 配置里可能写成 "https://x/tv/x.jpg;md5;<hash>"：地址是伪装成图片的 jar，
        // 末尾 hash 是配置作者给的校验值。必须校验通过才加载，
        // 否则被换包 / 半途截断时表现为“插件崩溃”，排查成本极高。
        val spec = JarSpec.parse(raw)
        val hash = spec.hash.ifBlank { expected }
        val name = md5(spec.url) + ".jar"
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
        if (bytes.size < 1024) throw SpiderLoadException("插件体积异常：${bytes.size}B")
        if (hash.isNotBlank()) {
            val actual = md5(bytes)
            if (!actual.equals(hash, ignoreCase = true)) {
                throw SpiderLoadException(
                    "「$siteName」插件校验失败：期望 ${hash.take(8)}…，实际 ${actual.take(8)}…（可能被替换或下载不完整）"
                )
            }
        }
        // CatVod 插件是 zip（jar）包，头四个字节应为 PK；不是就当坏包处理，避免 DexClassLoader 抛底层异常
        if (!bytes.startsWithZip()) throw SpiderLoadException("「$siteName」的插件不是有效的 jar 包")
        val temp = File(jarDir, "$name.tmp")
        temp.writeBytes(bytes)
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
                val parts = raw.split(';').map { it.trim() }
                if (parts.size >= 3 && parts[1].equals("md5", ignoreCase = true)) return JarSpec(parts[0], parts[2])
                if (parts.size == 2 && parts[1].length == 32 && parts[1].all { it.isLetterOrDigit() }) return JarSpec(parts[0], parts[1])
                return JarSpec(raw.trim(), "")
            }
        }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { b -> "%02x".format(b) }

    /** 从 jar 里按后缀取一个资源落盘（assets 里的 .guard 这类加密负载）。 */
    private fun extractAssetEntry(jarFile: File, suffix: String, dir: File, name: String): File? = runCatching {
        ZipFile(jarFile).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(suffix) && it.size > 0 }
                ?: return@use null
            val out = File(dir, name)
            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        }
    }.getOrNull()

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

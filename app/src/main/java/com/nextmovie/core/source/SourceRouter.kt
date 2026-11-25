package com.nextmovie.core.source

import com.nextmovie.core.config.Site
import com.nextmovie.core.source.adapter.AdapterEngine
import com.nextmovie.core.source.catpaw.CatPawEngine
import com.nextmovie.core.source.python.PythonEngine

/**
 * 按 [SourceKind] 分发到具体引擎。
 *
 * 现有生产路径仍走 [com.nextmovie.core.AppRepository] 内的 CMS / SpiderHost；
 * 本路由器是统一扩展点：新运行时先在这里挂上，再逐步把 AppRepository 切到委托模式。
 */
class SourceRouter(
    private val cms: SourceEngine? = null,
    private val jar: SourceEngine? = null,
    private val js: SourceEngine? = null,
    private val python: SourceEngine = PythonEngine(),
    private val catpaw: SourceEngine = CatPawEngine(),
    private val adapter: SourceEngine = AdapterEngine()
) {
    fun kindOf(site: Site): SourceKind = SourceKind.of(site)

    fun engineFor(site: Site): SourceEngine {
        val kind = kindOf(site)
        val engine = when (kind) {
            SourceKind.CMS -> cms
            SourceKind.JAR -> jar
            SourceKind.JS -> js
            SourceKind.PYTHON -> python
            SourceKind.CATPAW -> catpaw
            SourceKind.ADAPTER -> adapter
            SourceKind.NETDISK -> jar // 网盘播放仍由插件 proxy 路径承载
            SourceKind.UNSUPPORTED -> null
        }
        return engine ?: UnavailableEngine(
            kind,
            when (kind) {
                SourceKind.CMS -> "CMS 引擎未注入"
                SourceKind.JAR, SourceKind.JS -> "插件引擎未注入，请走 SpiderHost 主路径"
                SourceKind.PYTHON -> "Python 源需要 Chaquopy 运行时，当前构建未启用"
                SourceKind.CATPAW -> "CatPaw / Node 服务包尚未接入本机 worker"
                SourceKind.ADAPTER -> "该纯适配器尚未注册"
                SourceKind.NETDISK -> "网盘能力由当前插件提供，请在「账户与网盘」中授权"
                SourceKind.UNSUPPORTED -> site.unavailableReason ?: "无法识别的源类型：${site.api}"
            }
        )
    }

    /** 能力矩阵，供设置页 / 关于页展示。 */
    fun capabilityMatrix(): List<CapabilityRow> = listOf(
        CapabilityRow(SourceKind.CMS, ready = true, detail = "JSON / XML 已通"),
        CapabilityRow(SourceKind.JAR, ready = true, detail = "DexClassLoader + 进程隔离 + 本地代理"),
        CapabilityRow(SourceKind.JS, ready = true, detail = "QuickJS 最小 Spider API"),
        CapabilityRow(SourceKind.PYTHON, ready = python.isAvailable, detail = pythonStatus()),
        CapabilityRow(SourceKind.CATPAW, ready = catpaw.isAvailable, detail = catpawStatus()),
        CapabilityRow(SourceKind.NETDISK, ready = true, detail = "插件扫码 + loopback 代理（夸克等）"),
        CapabilityRow(SourceKind.ADAPTER, ready = adapter.isAvailable, detail = adapterStatus())
    )

    private fun pythonStatus(): String =
        if (python.isAvailable) "Chaquopy 已启用" else "骨架已就位，默认关闭（包体与内存评估中）"

    private fun catpawStatus(): String =
        if (catpaw.isAvailable) "本机 Node worker 已启用" else "骨架已就位，待接入服务包校验与回环端口"

    private fun adapterStatus(): String =
        if (adapter.isAvailable) "已注册协议适配器" else "注册表为空，可按站点逐步添加"
}

data class CapabilityRow(
    val kind: SourceKind,
    val ready: Boolean,
    val detail: String
)

package com.nextmovie.core.source.catpaw

import com.nextmovie.core.config.Site
import com.nextmovie.core.model.PlayResult
import com.nextmovie.core.model.Vod
import com.nextmovie.core.model.VodDetail
import com.nextmovie.core.source.SourceEngine
import com.nextmovie.core.source.SourceException
import com.nextmovie.core.source.SourceKind

/**
 * CatPaw / Node 服务包引擎（规划实现）。
 *
 * 目标形态参考 Marco 的本机 Node worker：
 * - 用户导入带 MD5 的服务包，落盘后校验
 * - 每个包独立 worker，播放代理只监听 127.0.0.1
 * - 配置页与播放接口隔离，不把临时端口写入订阅
 *
 * Android TV 上嵌入完整 Node 成本高；优先评估：
 * 1) 轻量 QuickJS 已覆盖的 JS 站点
 * 2) 将 CatPaw 包中的可静态化规则抽到 [com.nextmovie.core.source.adapter]
 *
 * [isAvailable] 当前恒 false。
 */
object CatPawFlags {
    const val ENABLED = false
}

class CatPawEngine : SourceEngine {
    override val kind: SourceKind = SourceKind.CATPAW
    override val isAvailable: Boolean = CatPawFlags.ENABLED

    private fun deny(site: Site): Nothing = throw SourceException(
        "「${site.name}」需要 CatPaw / Node 服务包（${site.api}）。" +
            "当前版本尚未接入本机 Node worker。"
    )

    override suspend fun home(site: Site, filter: Boolean): List<Vod> = deny(site)
    override suspend fun categories(site: Site): List<Pair<String, String>> = deny(site)
    override suspend fun category(
        site: Site,
        tid: String,
        page: String,
        filter: Boolean,
        extend: Map<String, String>
    ): List<Vod> = deny(site)
    override suspend fun detail(site: Site, id: String, name: String): VodDetail? = deny(site)
    override suspend fun search(site: Site, key: String, quick: Boolean, page: String?): List<Vod> = deny(site)
    override suspend fun play(site: Site, flag: String, id: String): PlayResult? = deny(site)
}

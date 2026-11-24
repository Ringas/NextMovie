package com.nextmovie.core.net

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放链路的数据源：走 OkHttp + SmartDns，和配置/详情请求用同一套抗污染解析。
 *
 * 为什么不用 media3 的 DefaultHttpDataSource：它内部是 HttpURLConnection，
 * 只能吃系统 DNS。盒子/路由器把源站域名投毒成境外 IP 时，列表和详情都能打开，
 * 一点播放就卡到 12 秒超时。
 *
 * 注意：播放是长连接，必须把 callTimeout 归零，否则看 30 秒就被整体超时掐掉。
 */
object PlayerDataSources {

    private val client: OkHttpClient by lazy {
        Http.defaultClient().newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
            .also {
                // 一眼确认播放链路确实挂在 SmartDns 上（dns=SmartDns）
                android.util.Log.d("NextMoviePlay", "dataSource=OkHttpDataSource dns=${it.dns.javaClass.simpleName}")
            }
    }

    fun factory(headers: Map<String, String>): DataSource.Factory =
        OkHttpDataSource.Factory(client)
            .setUserAgent(Http.UA)
            .setDefaultRequestProperties(headers)
}

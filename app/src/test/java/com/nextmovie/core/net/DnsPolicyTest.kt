package com.nextmovie.core.net

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对应线上事故：盒子把 caiji.dyttzyapi.com 解析成 104.244.43.208（Twitter 段），
 * 12 秒超时后报「数据源加载失败」。这里锁住「投毒 IP 必须被识别」与「DoH 真身要留下」两条规则。
 */
class DnsPolicyTest {

    @Test
    fun `facebook 段 v4 也算投毒`() {
        org.junit.Assert.assertTrue(DnsPolicy.isPoisoned("157.240.17.41"))
        org.junit.Assert.assertTrue(DnsPolicy.isPoisoned("31.13.1.2"))
        org.junit.Assert.assertTrue(DnsPolicy.isPoisoned("108.160.169.178"))
        org.junit.Assert.assertFalse(DnsPolicy.isPoisoned("23.225.147.245"))
    }

    @Test
    fun poisonedIpsAreRejected() {
        assertTrue(DnsPolicy.isPoisoned("104.244.43.208"))
        assertTrue(DnsPolicy.isPoisoned("199.59.149.136"))
        assertFalse(DnsPolicy.isPoisoned("23.225.147.245"))
        assertFalse(DnsPolicy.isPoisoned("103.252.114.61"))
    }

    @Test
    fun parseAnswersKeepsCnameRealMirrors() {
        val json = """
            {"Status":0,"Answer":[
              {"name":"caiji.dyttzyapi.com","type":5,"data":"vvv1.heisi-dns.com."},
              {"name":"vvv1.heisi-dns.com","type":1,"data":"23.225.147.245"},
              {"name":"vvv1.heisi-dns.com","type":1,"data":"23.225.145.238"}]}
        """.trimIndent()
        val ips = DnsPolicy.parseAnswers(json).map { it.hostAddress }
        assertEquals(listOf("23.225.147.245", "23.225.145.238"), ips)
    }

    @Test
    fun parseAnswersDropsPoisonOnly() {
        val json = """{"Answer":[{"type":1,"data":"199.59.149.136"}]}"""
        assertTrue(DnsPolicy.parseAnswers(json).isEmpty())
    }

    @Test
    fun privateAndLoopbackAreUnusable() {
        assertFalse(DnsPolicy.isUsable(InetAddress.getByName("10.9.9.9")))
        assertFalse(DnsPolicy.isUsable(InetAddress.getByName("192.168.1.2")))
        assertFalse(DnsPolicy.isUsable(InetAddress.getByName("127.0.0.1")))
        assertFalse(DnsPolicy.isUsable(InetAddress.getByName("104.244.43.208")))
        assertTrue(DnsPolicy.isUsable(InetAddress.getByName("103.252.114.61")))
    }
}

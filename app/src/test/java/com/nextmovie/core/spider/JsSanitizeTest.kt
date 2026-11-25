package com.nextmovie.core.spider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSanitizeTest {

    @Test
    fun `export default object becomes spider`() {
        val out = JsScriptSupport.sanitizeExports("export default { init, home, play };")
        assertTrue(out.contains("var __spider"))
        assertFalse(out.contains("export default"))
    }

    @Test
    fun `minified export default brace`() {
        val out = JsScriptSupport.sanitizeExports("const a=1;export default{init,home};")
        assertTrue(out.contains("var __spider"))
    }

    @Test
    fun `module detection`() {
        assertTrue(JsScriptSupport.isEsModule("import x from 'https://a/b.js';\nexport default {}", "https://x/a.js"))
        assertTrue(JsScriptSupport.isEsModule("export default { home };", "https://x/a.js"))
        assertFalse(JsScriptSupport.isEsModule("function home(){ return '{}'; }", "https://x/a.js"))
        assertTrue(JsScriptSupport.isEsModule("function home(){}", "https://x/a.mjs"))
    }
}

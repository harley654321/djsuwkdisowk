package io.vdl.cloudkit

import io.vdl.cloudkit.internal.Packer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Packer (JsUnpacker port) tests against the REAL mixdrop embed capture
 * from mxdrop.top/e/gjn98o4lbqk06z (live 2026-10-10). These prove the
 * unpack algorithm recovers the exact MDCore.wurl value a recloudstream
 * upstream change would break loudly here instead of at download time.
 */
class PackerTest {

    private fun fixture(name: String): String {
        val url = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("fixture missing: $name")
        return File(url.toURI()).readText()
    }

    @Test
    fun `unpack recovers MDCore wurl from the real mixdrop capture`() {
        val page = fixture("mixdrop-e-real.html")
        assertTrue("fixture is packed", Packer.isPacked(page))
        val unpacked = Packer.getAndUnpack(page)
        assertNotNull("real capture unpacks", unpacked)
        val wurl = Regex("""wurl.*?="(.*?)";""").find(unpacked!!)?.groupValues?.get(1)
        assertEquals(
            "exact wurl captured live 2026-10-10 from mxdrop.top",
            "//30xplewoo.mxcontent.net/v2/gjn98o4lbqk06z.mp4?s=nrJkEo8k82KtUYDwGJToRQ&e=1791633814&_t=1791613624",
            wurl,
        )
    }

    @Test
    fun `unpack returns null on inconsistent symbol table`() {
        // radix 10 with 26 symbols claimed but only 5 provided: degrade, no throw
        val broken = "eval(function(p,a,c,k,e,d){}('0|1|2|3|4',10,26,'|||||'.split('|'),0,{}))"
        assertNull(Packer.unpack(broken))
    }

    @Test
    fun `unpack returns null when nothing is packed`() {
        assertNull(Packer.getAndUnpack("<html><body>plain page</body></html>"))
    }
}

package io.vdl.cloudkit

import io.vdl.cloudkit.internal.CloudHttp
import io.vdl.cloudkit.internal.UpnshareExtractor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPNShare domain routing contract.
 */
class UpnshareRoutingTest {

    @Test
    fun `matches recognizes uns bio subdomains and rejects others`(): Unit {
        val extractor = UpnshareExtractor(CloudHttp(client()))
        assertTrue(extractor.matches("https://animeav1.uns.bio/#kjkatd"))
        assertTrue(extractor.matches("https://server1.uns.bio/#abc"))
        assertTrue(extractor.matches("https://vidstack.io/#abc"))
        assertFalse(extractor.matches("https://example.com/#abc"))
    }

    private fun client(): okhttp3.OkHttpClient = okhttp3.OkHttpClient()
}

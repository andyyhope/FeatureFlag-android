package com.andyyhope.featureflag.sample.companion

import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two committed debug keys. The bridge's permission is signature-level, so "the same key" and
 * "another key" are the whole difference between the debug and the untrusted companion. Unit tests
 * run in the module's directory, samples/companion.
 */
class SampleKeysTest {

    private fun certificate(path: String, alias: String): X509Certificate {
        val store = KeyStore.getInstance("PKCS12")
        File(path).inputStream().use { input -> store.load(input, PASSWORD) }
        assertTrue(store.isKeyEntry(alias), "$path has no key called '$alias'")
        return store.getCertificate(alias) as X509Certificate
    }

    // Section: the two keystores

    @Test
    fun theSharedKeyIsTheSampleKey() {
        val shared = certificate(SHARED, "sample")

        assertEquals("CN=FeatureFlag Sample (debug only)", shared.subjectX500Principal.name)
    }

    @Test
    fun theUntrustedKeyIsADifferentCertificate() {
        val shared = certificate(SHARED, "sample")
        val untrusted = certificate(UNTRUSTED, "untrusted")

        assertEquals("CN=FeatureFlag Sample untrusted (debug only)", untrusted.subjectX500Principal.name)
        assertFalse(shared.encoded.contentEquals(untrusted.encoded), "both keystores hold the same certificate")
    }

    private companion object {
        const val SHARED = "../keys/shared-debug.keystore"
        const val UNTRUSTED = "../keys/untrusted.keystore"
        val PASSWORD = "android".toCharArray()
    }
}

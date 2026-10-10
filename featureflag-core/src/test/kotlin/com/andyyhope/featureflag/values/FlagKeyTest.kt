package com.andyyhope.featureflag.values

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Ported from FlagKeyTests and KeyEncodingEdgeCaseTests. */
class FlagKeyTest {

    private fun kebab(name: String): String = KeyEncoding.Kebabcase.key(FlagKeyPath(listOf(name))).rawValue

    // Paths

    @Test
    fun rootPathIsEmpty() {
        assertEquals(emptyList(), FlagKeyPath.ROOT.propertyNames)
    }

    @Test
    fun appendingBuildsAPathWithoutMutatingTheOriginal() {
        val base = FlagKeyPath.ROOT.appending("checkout")
        val path = base.appending("applePay")
        assertEquals(listOf("checkout", "applePay"), path.propertyNames)
        assertEquals(listOf("checkout"), base.propertyNames)
    }

    @Test
    fun prependingPutsAComponentAtTheFrontWithoutMutatingTheOriginal() {
        val base = FlagKeyPath(listOf("express", "oneTap"))
        assertEquals(listOf("checkout", "express", "oneTap"), base.prepending("checkout").propertyNames)
        assertEquals(listOf("express", "oneTap"), base.propertyNames)
    }

    // Kebab-case

    @Test
    fun kebabcaseSplitsWordsAsSwiftDoes() {
        assertEquals("new-onboarding", kebab("newOnboarding"))
        assertEquals("checkout", kebab("checkout"))
        assertEquals("enable-api", kebab("enableAPI"))
        assertEquals("api-key", kebab("apiKey"))
        assertEquals("use-https-only", kebab("useHTTPSOnly"))
        assertEquals("checkout-v2", kebab("checkoutV2"))
        assertEquals("api", kebab("API"))
        assertEquals("new-onboarding", kebab("NewOnboarding"))
        assertEquals("v2-enabled", kebab("v2Enabled"))
        assertEquals("enable2-fa", kebab("enable2FA"))
        assertEquals("legacy_name", kebab("legacy_name"))
    }

    @Test
    fun splittingHandlesTheSmallestNames() {
        assertEquals(listOf("a"), KeyEncoding.splitWords("a"))
        assertEquals(listOf("A"), KeyEncoding.splitWords("A"))
        assertEquals(emptyList(), KeyEncoding.splitWords(""))
        assertEquals(listOf("API"), KeyEncoding.splitWords("API"))
    }

    @Test
    fun nestedComponentsJoinWithADot() {
        val path = FlagKeyPath.ROOT.appending("checkout").appending("express").appending("oneTap")
        assertEquals(FlagKey("checkout.express.one-tap"), KeyEncoding.Kebabcase.key(path))
        assertEquals(FlagKey(""), KeyEncoding.Kebabcase.key(FlagKeyPath.ROOT))
        assertEquals(FlagKey("one-tap"), KeyEncoding.Kebabcase.key(FlagKeyPath(listOf("oneTap"))))
    }

    @Test
    fun encodingIgnoresTheDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("enable-api", kebab("enableAPI"))
            assertEquals("user-id", kebab("userID"))
            assertEquals("checkout.is_in", KeyEncoding.Snakecase.key(FlagKeyPath(listOf("checkout", "isIN"))).rawValue)
        } finally {
            Locale.setDefault(saved)
        }
    }

    // Other encodings

    @Test
    fun snakecaseAndVerbatim() {
        val path = FlagKeyPath.ROOT.appending("checkout").appending("applePay")
        assertEquals(FlagKey("checkout.apple_pay"), KeyEncoding.Snakecase.key(path))
        assertEquals(FlagKey("checkout.applePay"), KeyEncoding.Verbatim.key(path))
    }

    @Test
    fun customEncodingsAreHonoured() {
        val path = FlagKeyPath(listOf("checkout", "applePay"))
        assertEquals(FlagKey("checkout/applePay"), KeyEncoding(separator = "/") { it }.key(path))
        assertEquals(FlagKey("CHECKOUT.APPLEPAY"), KeyEncoding { it.uppercase() }.key(path))
        assertEquals(FlagKey("ab"), KeyEncoding(separator = "") { it }.key(FlagKeyPath(listOf("a", "b"))))
        assertEquals(FlagKey("a::b"), KeyEncoding(separator = "::") { it }.key(FlagKeyPath(listOf("a", "b"))))
    }

    // Keys

    @Test
    fun keysAreValuesThatDescribeThemselvesAsTheirRawValue() {
        val storage = mutableMapOf(FlagKey("a.b") to 1)
        assertEquals(1, storage[FlagKey("a.b")])
        assertEquals(FlagKey("a.b"), FlagKey("a.b"))
        assertNotEquals(FlagKey("a.b"), FlagKey("a.c"))
        assertEquals("checkout.apple-pay", FlagKey("checkout.apple-pay").toString())
        assertEquals("checkout.apple-pay", "${FlagKey("checkout.apple-pay")}")
    }
}

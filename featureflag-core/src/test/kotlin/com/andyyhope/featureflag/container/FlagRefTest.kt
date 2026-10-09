package com.andyyhope.featureflag.container

import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.KeyEncoding
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagRefTest {
    @Test fun keyEncodesThePathWithTheGivenEncoding() {
        val path = FlagKeyPath(listOf("checkout", "applePay"))
        val ref = FlagRef(path, "applePay", "Apple Pay", null, FlagCodecs.boolean) { false }
        assertEquals(FlagKey("checkout.apple-pay"), ref.key(KeyEncoding.Kebabcase))
        assertEquals(FlagKey("checkout.apple_pay"), ref.key(KeyEncoding.Snakecase))
    }

    @Test fun theDefaultIsReadOnFirstUseAndOnlyOnce() {
        var reads = 0
        val ref = FlagRef(FlagKeyPath(listOf("maxItems")), "maxItems", "Maximum items", null, FlagCodecs.int) {
            reads++
            10
        }
        assertEquals(0, reads)
        assertEquals(10, ref.defaultValue)
        assertEquals(10, ref.defaultValue)
        assertEquals(1, reads)
    }

    @Test fun describesItselfByPath() {
        val ref = FlagRef(FlagKeyPath(listOf("checkout", "tier")), "tier", "Tier", null, FlagCodecs.string) { "free" }
        assertEquals("FlagRef(checkout.tier)", ref.toString())
    }
}

package com.andyyhope.featureflag.values

import com.andyyhope.featureflag.Fixtures
import org.junit.Test
import kotlin.test.assertEquals

class KeyEncodingGoldenTest {

    @Test
    fun everyPropertyNameEncodesAsAppleDoes() {
        val rows = Fixtures.tsv("keys/key-encoding.tsv")
        for ((name, kebab, snake) in rows) {
            val path = FlagKeyPath(listOf(name))
            assertEquals(kebab, KeyEncoding.Kebabcase.key(path).rawValue, "kebab-case of '$name'")
            assertEquals(snake, KeyEncoding.Snakecase.key(path).rawValue, "snake_case of '$name'")
        }
        assertEquals(true, rows.size > 60)
    }
}

package com.andyyhope.featureflag.test

import com.andyyhope.featureflag.audit.FlagMappingAudit
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.RemoteOverrideException
import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.values.KeyEncoding

/**
 * Fails with an [AssertionError] unless a payload wires up every flag it should. Any test
 * framework reports that as a failure: JUnit 4 or 5, kotlin.test, Kotest.
 *
 * ```kotlin
 * @Test fun stagingConfigMapsEveryFlag() =
 *     assertFlagsFullyMapped(AppFlagsSpec, File("staging.json").readBytes(), strict = true, ignoring = listOf("meta"))
 * ```
 *
 * The failure message is the whole audit, so a large config reports every problem in one run.
 *
 * @param strict also require every value in the payload to be read by a flag.
 * @param ignoring path prefixes whose unconsumed values are allowed under [strict].
 */
public fun assertFlagsFullyMapped(
    spec: FlagContainerSpec<*>,
    payload: ByteArray,
    mapper: RemoteOverrideMapper = DotPathMapper,
    keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
    strict: Boolean = false,
    ignoring: List<String> = emptyList(),
) {
    val audit = try {
        FlagMappingAudit(spec, payload, mapper, keyEncoding)
    } catch (error: RemoteOverrideException) {
        throw AssertionError("The payload could not be read: ${error.message}", error)
    }
    if (!audit.isComplete(strict, ignoring)) {
        throw AssertionError("\n" + audit.description(strict, ignoring))
    }
}

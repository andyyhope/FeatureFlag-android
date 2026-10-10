package com.andyyhope.featureflag.scenario

import com.andyyhope.featureflag.remote.DotPathMapper
import com.andyyhope.featureflag.remote.RemoteOverrideMapper
import com.andyyhope.featureflag.remote.RemoteValue
import com.andyyhope.featureflag.schema.FlagSchema
import com.andyyhope.featureflag.values.FlagKey

/** A port of Examples/DemoApp/RemoteConfigurations.swift: payloads standing in for a fetch. */
internal data class RemoteConfiguration(
    val id: String,
    val name: String,
    val summary: String,
    val json: String,
    /** Expected to be rejected, so a UI can say so up front. */
    val isDeliberatelyBroken: Boolean,
) {
    val data: ByteArray get() = json.toByteArray()

    companion object {
        fun forEnvironment(environment: DemoEnvironment): RemoteConfiguration = when (environment) {
            DemoEnvironment.Production -> production
            DemoEnvironment.Staging -> staging
            DemoEnvironment.Local -> local
        }

        val production = RemoteConfiguration(
            id = "production",
            name = "Production",
            summary = "Conservative: new work off, live backend",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": false },
                    "checkout": { "applePay": false }
                  },
                  "config": {
                    "apiEndpoint": "https://api.example.com",
                    "paymentMethods": [
                      { "name": "Visa", "kind": "card", "enabled": true, "minimumSpend": 0 },
                      { "name": "Mastercard", "kind": "card", "enabled": true, "minimumSpend": 0 }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        val staging = RemoteConfiguration(
            id = "staging",
            name = "Staging",
            summary = "Everything on, pointed at the staging backend",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": true },
                    "checkout": { "applePay": true }
                  },
                  "config": {
                    "apiEndpoint": "https://staging.api.example.com",
                    "paymentMethods": [
                      { "name": "Visa", "kind": "card", "enabled": true, "minimumSpend": 0 },
                      { "name": "Apple Pay", "kind": "wallet", "enabled": true, "minimumSpend": 0 },
                      { "name": "Bank transfer", "kind": "transfer", "enabled": true, "minimumSpend": 10,
                        "limits": [
                          { "currency": "AUD", "maximum": 5000 },
                          { "currency": "NZD", "maximum": 5500 }
                        ] }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        val local = RemoteConfiguration(
            id = "local",
            name = "Local",
            summary = "Everything on, pointed at a machine on your desk",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": true },
                    "checkout": { "applePay": true }
                  },
                  "config": {
                    "apiEndpoint": "http://localhost:8080",
                    "paymentMethods": [
                      { "name": "Test card", "kind": "card", "enabled": true, "minimumSpend": 0 }
                    ]
                  }
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        /** One bad field; all-or-nothing means the valid applePay beside it is not applied either. */
        val malformed = RemoteConfiguration(
            id = "malformed",
            name = "Malformed payload",
            summary = """onboarding.v2 sends "yes" where a boolean belongs""",
            json = """
                {
                  "featureToggles": {
                    "onboarding": { "v2": "yes" },
                    "checkout": { "applePay": true }
                  },
                  "config": { "apiEndpoint": "https://api.example.com" }
                }
            """.trimIndent(),
            isDeliberatelyBroken = true,
        )

        /** The flags arrive as a list, and a flag's name is a field rather than a key: no dot path reaches them. */
        val experiments = RemoteConfiguration(
            id = "experiments",
            name = "List of experiments",
            summary = "A shape no dot path can reach, reshaped by a custom mapper",
            json = """
                {
                  "experiments": [
                    { "flag": "new-onboarding", "enabled": true },
                    { "flag": "checkout.apple-pay", "enabled": true }
                  ]
                }
            """.trimIndent(),
            isDeliberatelyBroken = false,
        )

        /** The same shape with one flag misspelled, which only a custom mapper can produce. */
        val experimentsWithATypo = RemoteConfiguration(
            id = "experiments-typo",
            name = "List of experiments, misspelled",
            summary = "new-onbaording is not a flag this app has",
            json = """
                {
                  "experiments": [
                    { "flag": "new-onbaording", "enabled": true },
                    { "flag": "checkout.apple-pay", "enabled": true }
                  ]
                }
            """.trimIndent(),
            isDeliberatelyBroken = true,
        )
    }
}

/** A port of Examples/DemoApp/LocalConfiguration.swift: the config bundled for each environment. */
internal data class LocalConfiguration(val json: String) {
    val data: ByteArray get() = json.toByteArray()

    companion object {
        fun forEnvironment(environment: DemoEnvironment): LocalConfiguration = when (environment) {
            DemoEnvironment.Production -> production
            DemoEnvironment.Staging -> staging
            DemoEnvironment.Local -> local
        }

        val production = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": false } },
                  "config": { "apiEndpoint": "https://api.example.com" }
                }
            """.trimIndent(),
        )

        val staging = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": false } },
                  "config": { "apiEndpoint": "https://staging-local.example" }
                }
            """.trimIndent(),
        )

        val local = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "applePay": true } },
                  "config": { "apiEndpoint": "http://localhost:8080" }
                }
            """.trimIndent(),
        )
    }
}

/**
 * A port of DemoRemoteMapper.swift. It reads both shapes this backend has ever sent: the
 * dot-path one, by composing DotPathMapper, and a list of `{ "flag", "enabled" }` experiments.
 */
internal object DemoRemoteMapper : RemoteOverrideMapper {
    override fun map(payload: RemoteValue, schema: FlagSchema): Map<FlagKey, RemoteValue> {
        val records = payload.valueAt("experiments") as? RemoteValue.Array ?: return DotPathMapper.map(payload, schema)
        val result = LinkedHashMap<FlagKey, RemoteValue>()
        for (record in records.value) {
            val name = record.valueAt("flag") as? RemoteValue.String
            val enabled = record.valueAt("enabled") as? RemoteValue.Bool
            // Named by the key the schema publishes, so a typo here is reported as an unknown key.
            if (name != null && enabled != null) result[FlagKey(name.value)] = enabled
        }
        return result
    }
}

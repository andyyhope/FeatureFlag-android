package com.andyyhope.featureflag.sample.host

/** A port of iOS Examples/DemoApp/LocalConfiguration.swift: the config bundled for each environment. */
data class LocalConfiguration(val json: String) {
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
                  "featureToggles": { "checkout": { "googlePay": false } },
                  "config": { "apiEndpoint": "https://api.example.com" }
                }
            """.trimIndent(),
        )

        val staging = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "googlePay": false } },
                  "config": { "apiEndpoint": "https://staging-local.example" }
                }
            """.trimIndent(),
        )

        val local = LocalConfiguration(
            """
                {
                  "featureToggles": { "checkout": { "googlePay": true } },
                  "config": { "apiEndpoint": "http://localhost:8080" }
                }
            """.trimIndent(),
        )
    }
}

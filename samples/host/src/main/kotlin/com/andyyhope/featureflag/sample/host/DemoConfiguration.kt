package com.andyyhope.featureflag.sample.host

import com.andyyhope.featureflag.remote.EnvironmentConfiguration

/**
 * The environment's two layers: a config bundled in the app, and one fetched for it. The library
 * does no networking and reads no files; these lambdas hand it the bytes. `remote` suspends
 * because a real fetch would.
 */
fun demoConfiguration(
    remote: suspend (DemoEnvironment) -> ByteArray? = { environment ->
        RemoteConfiguration.forEnvironment(environment).data
    },
): EnvironmentConfiguration<DemoEnvironment> = EnvironmentConfiguration(
    AppFlagsSpec,
    mapper = DemoRemoteMapper,
    localName = "Local",
    remoteName = "Remote",
    local = { environment -> LocalConfiguration.forEnvironment(environment).data },
    remote = remote,
)

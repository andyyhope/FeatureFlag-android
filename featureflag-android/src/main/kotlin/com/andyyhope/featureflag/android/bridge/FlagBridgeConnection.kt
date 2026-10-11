package com.andyyhope.featureflag.android.bridge

import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.schema.FlagSchema
import kotlin.time.Duration

/** What [FlagBridgeClient.connect] found: the host's schema, or the first thing to fix. */
public sealed interface FlagBridgeConnection {

    /** What to tell the person using the companion. `null` only for [Connected]. */
    public val message: String?

    /** Everything works. [schema] is the host's published schema. */
    public data class Connected(public val schema: FlagSchema) : FlagBridgeConnection {
        override val message: String? get() = null
    }

    /** The companion's manifest lacks `<uses-permission>` for [permission], which also hides the host. */
    public data class UsesPermissionMissing(public val permission: String) : FlagBridgeConnection {
        override val message: String
            get() = "This app does not request '$permission'. " +
                "Add <uses-permission android:name=\"$permission\" /> to the companion's manifest."
    }

    /** No app with [hostPackage] is installed. */
    public data class HostNotFound(public val hostPackage: String) : FlagBridgeConnection {
        override val message: String
            get() = "No app with the package '$hostPackage' is installed. Install and run the host app, " +
                "and check the package name: a debug build often adds a suffix such as '.debug'."
    }

    /** The host's bridge permission was not granted: the two apps are signed with different keys. */
    public data class PermissionDenied(
        public val hostPackage: String,
        /** SHA-256 of each certificate, uppercase hex, no separators. */
        public val hostCertificates: List<String>,
        public val companionCertificates: List<String>,
    ) : FlagBridgeConnection {
        override val message: String
            get() = "'$hostPackage' only lets apps signed with its own key connect. Sign this app with the same key, " +
                "or list this app's certificate in the host's knownSigner permission (Android 12 and later). " +
                "Host: ${hostCertificates.joinToString(", ")}. This app: ${companionCertificates.joinToString(", ")}."
    }

    /** The host is installed but does not include `featureflag-bridge`. */
    public data class BridgeNotInstalled(public val hostPackage: String) : FlagBridgeConnection {
        override val message: String
            get() = "'$hostPackage' does not include featureflag-bridge. Add it to the host app's dependencies."
    }

    /** The host did not answer within [timeout]. */
    public data class Unresponsive(
        public val hostPackage: String,
        public val timeout: Duration,
    ) : FlagBridgeConnection {
        override val message: String
            get() = "'$hostPackage' did not answer within $timeout. It may be stuck starting up; open it once, then try again."
    }

    /** The host speaks another protocol, or published a schema this build cannot read. */
    public data class Incompatible(public val reason: String) : FlagBridgeConnection {
        override val message: String get() = reason
    }

    /** The host has never published its schema, or the file was deleted. */
    public data object NotPublished : FlagBridgeConnection {
        override val message: String get() = FlagSchemaException.NotPublished().message.orEmpty()
    }
}

package docs.troubleshooting

import com.andyyhope.featureflag.android.bridge.FlagBridgeConnection
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract

private const val HOST = "com.example.app"

/** What a companion without the host's permission in its manifest shows. */
fun usesPermissionMissingMessage(): String =
    FlagBridgeConnection.UsesPermissionMissing(FlagBridgeContract.permission(HOST)).message

/** What a companion shows when it cannot see the host. */
fun hostNotFoundMessage(): String = FlagBridgeConnection.HostNotFound(HOST).message

/** What a companion signed with another key shows. */
fun permissionDeniedMessage(): String = FlagBridgeConnection.PermissionDenied(
    hostPackage = HOST,
    hostCertificates = listOf("3A1F8C0E52B7D9461C2E0F9A7B83D5C6E1F04A2B9C7D3E5F6081A2B3C4D5E6F7"),
    companionCertificates = listOf("B04C2E7A19D35F6C8E0A4B7D2F9C1E3A5B8D0F2C4E6A8B1D3F5C7E9A0B2D4F61"),
).message

/** What a companion shows before the host has ever published its schema. */
fun notPublishedMessage(): String = FlagBridgeConnection.NotPublished.message

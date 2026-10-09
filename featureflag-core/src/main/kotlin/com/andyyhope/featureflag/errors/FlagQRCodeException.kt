package com.andyyhope.featureflag.errors

/** Why a QR code could not be made or read. */
public sealed class FlagQRCodeException(message: String) : RuntimeException(message) {

    /** The payload will not fit in one QR code, even compressed. Carries the override count so the message can say what to remove. */
    public class PayloadTooLarge(
        public val bytes: Int,
        public val limit: Int,
        public val overrideCount: Int,
    ) : FlagQRCodeException(
        "$overrideCount override${if (overrideCount == 1) "" else "s"} need $bytes characters and a QR code holds $limit. " +
            "Reset a few, or export JSON instead.",
    )

    /** Not a flag code, or a version this build does not understand. */
    public class UnrecognisedFormat : FlagQRCodeException("Not a flag code. A flag code starts with \"FFQR1:\".")

    /** A flag code that could not be decompressed, which usually means a partial scan. */
    public class Corrupt : FlagQRCodeException(
        "The code could not be decompressed, which usually means a partial scan. Try again with the whole code in frame.",
    )
}

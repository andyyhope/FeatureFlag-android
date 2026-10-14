package com.andyyhope.featureflag.ui

/**
 * Every word the companion shows. English only, as on iOS, and verbatim from the iOS companion
 * (docs/superpowers/notes/2026-10-14-m3-ios-views.md) unless the M3 contract gives Android prose.
 * The few strings iOS has no equivalent for are marked "Android".
 */
internal object FlagStrings {

    // Tabs
    const val OVERRIDES = "Overrides"
    const val FLAGS = "Flags"
    const val SIGNALS = "Signals"

    // Shell and status
    const val NO_FLAGS_YET = "No flags yet"
    const val TRY_AGAIN = "Try again"
    const val NO_TABS = "No tabs"
    const val NO_TABS_MESSAGE =
        "This companion was given an empty tab list, so there is nothing to show. " +
            "Pass at least one, or omit the argument for the default two."

    fun duplicateTabId(id: String): String =
        "A companion cannot have two tabs with the id '$id'. Give one of them a different id, or drop it."

    fun duplicateSignal(name: String): String =
        "More than one signal group defines '$name'. A signal travels as its raw value, " +
            "so it has to be unique across every group."

    // Android: actions iOS gets from the system (navigation, edit mode, swipe actions, Live Activities)
    const val BACK = "Back"
    const val ACTIONS = "Actions"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val DELETE = "Delete"
    const val NOTIFICATION_CHANNEL = "Signal countdowns"
    const val SENDING_IN = "Sending in"

    // Browser and groups
    const val FEATURE_FLAGS = "Feature Flags"
    const val SEARCH_FLAGS = "Search flags"
    const val GROUPS = "Groups"
    const val NO_FLAGS = "No flags"
    const val NO_FLAGS_MESSAGE = "The app has not published any flags yet."
    const val NO_MATCHES = "No matches"

    fun noMatches(query: String): String = "No flag matches “$query”."

    fun flagCount(count: Int): String = "$count flag${plural(count)}"

    // Rows
    const val OVERRIDDEN = "Overridden"
    const val RESET_TO_DEFAULT = "Reset to the app's default"
    const val NOT_A_VALID_VALUE = "Not a valid value for this flag"
    const val COPY_VALUE = "Copy value"
    const val COPIED = "Copied"
    const val EDIT = "Edit"
    const val UNREADABLE = "Unreadable"
    const val OK = "OK"
    const val CANCEL = "Cancel"

    fun listSummary(count: Int, typeName: String): String = "$count $typeName${plural(count)}"

    fun recordCount(count: Int): String = "$count record${plural(count)}"

    fun twoRecordsShareA(keyName: String): String = "Two records share a $keyName"

    // List editor
    const val ADD = "Add"

    fun itemCount(count: Int): String = "$count item${plural(count)}"

    fun listFooter(typeName: String): String =
        "Every element is a $typeName. Removing them all leaves an empty array, which is a value like any " +
            "other — reset the flag if you want the app's default back."

    // Records
    const val DUPLICATE = "Duplicate"
    const val UNTITLED = "Untitled"
    const val RECORD = "Record"
    const val MISSING_VALUE = "—"
    const val NOT_A_LIST_OF_RECORDS = "This value is not a list of records."
    const val UNREADABLE_FOOTER =
        "The app cannot read it either, so it is using its default. Reset the flag to edit it here."

    fun recordsFooter(names: List<String>): String =
        "Each record has ${names.size} field${plural(names.size)}: ${names.joinToString(", ")}."

    fun duplicateKeyReason(keyName: String, value: String): String =
        "Two records share the same $keyName, $value — it is what tells one record from another, so they cannot."

    // Detail
    const val DETAIL_FOOTER =
        "Anything you set here stays set. A value set by hand outranks whatever a backend sends, until you reset it."
    const val DETAILS = "Details"
    const val KEY = "Key"
    const val DEFAULT = "Default"
    const val REMOTELY_OVERRIDABLE = "Remotely overridable"
    const val NO = "No"
    const val NO_REMOTE_KEY_FOOTER =
        "No remote key, so nothing a payload contains can change this flag. For a flag that decides which " +
            "payload gets fetched, that is deliberate — otherwise the two drive each other."
    const val REMOTE_KEY_FOOTER = "This flag carries a remote key, so a payload can change it."
    const val NO_SUCH_FLAG_FOOTER =
        "Check the key as the schema publishes it — a nested flag is “checkout.apple-pay”, " +
            "not “applePay”."

    fun remoteKey(key: String): String = "Yes — $key"

    fun noSuchFlag(key: String): String = "This app publishes no flag called “$key”."

    // Overrides
    const val NOTHING_OVERRIDDEN = "Nothing overridden"
    const val NOTHING_OVERRIDDEN_MESSAGE = "Every flag is reporting the value the app was built with."
    const val OVERRIDES_FOOTER = "Tap the arrow to put a flag back to the app's default."
    const val JSON = "JSON"
    const val JSON_FOOTER =
        "Only overrides travel — the defaults are already in the app. This is what a QR code carries, compressed."
    const val COPY_JSON = "Copy JSON"
    const val SHOW_QR_CODE = "Show QR code"
    const val SHARE_JSON = "Share JSON"
    const val RESET_EVERY_OVERRIDE = "Reset every override"
    const val RESET_EVERY_OVERRIDE_TITLE = "Reset every override?"
    const val RESET_EVERY_OVERRIDE_MESSAGE = "Every flag goes back to the value the app was built with."
    const val RESET = "Reset"
    const val IMPORT_ELLIPSIS = "Import…"
    const val IMPORT_FOOTER =
        "Paste an exported document or a scanned code. Import is all or nothing: one unknown key or wrong " +
            "type rejects the whole thing."

    fun changedCount(count: Int): String = "$count changed"

    // Import sheet
    const val IMPORT = "Import"
    const val IMPORT_HELP = "Paste exported JSON, or the contents of a scanned flag code."
    const val NOT_A_FLAG_DOCUMENT = "That is not a flag document."

    fun notAFlagInThisApp(key: String): String = "$key: not a flag in this app"

    fun wrongType(key: String): String = "$key: wrong type"

    fun notOneOfItsChoices(key: String): String = "$key: not one of its choices"

    fun newerFormat(version: Long): String = "This document was written by a newer version (format $version)."

    // QR sheet
    const val SCAN_TO_COPY = "Scan to copy"
    const val DONE = "Done"
    const val COPY_IMAGE = "Copy image"
    const val IMAGE_COPIED = "Image copied"
    const val SAVE = "Save"
    const val COPY_CODE = "Copy code"
    const val CODE_COPIED = "Code copied"
    const val COULD_NOT_RENDER = "Could not render the code."

    /** Android: the PNG that Copy image and Save hand over could not be written. */
    fun couldNotWriteImage(reason: String): String = "Could not write the image: $reason"

    /** iOS has no singular here. */
    fun qrCodeDescription(count: Int): String = "QR code containing $count overrides"

    /** iOS prints "(s)" literally. */
    fun overrideCount(count: Int): String = "$count override(s)"

    /** The view's wording, always plural, unlike core's FlagQRCodeException message. */
    fun qrTooLarge(count: Int, bytes: Int, limit: Int): String =
        "$count overrides need $bytes characters, and a QR code holds $limit. Reset a few, or export JSON instead."

    // Signals
    const val DELAY = "Delay"
    const val DELAY_FOOTER_INSTANT =
        "Sent the moment you tap. If the app isn't running, Android starts it to deliver the signal."
    const val DELAY_FOOTER_DELAYED =
        "Tap a signal, then switch to the app. It fires when the circle completes, by which time the app is in " +
            "front and listening."
    const val SIGNALS_FOOTER = "Signals carry no payload. State belongs in flags, which you can already edit."
    const val TAP_AGAIN_TO_CANCEL = "Tap again to cancel."
    const val TAKES_EFFECT_AFTER_A_RELAUNCH = "Takes effect after a relaunch"
    const val RECENT = "Recent"

    fun sendingIn(seconds: Long): String = "Sending in $seconds seconds. Tap to cancel."

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}

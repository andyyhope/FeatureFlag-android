package com.andyyhope.featureflag.ui.screens

/** A screen pushed onto a tab's back stack. Each saves as text, so a stack survives recreation. */
internal sealed interface FlagRoute {

    /** The group whose property path is [path]. */
    data class Group(val path: List<String>) : FlagRoute

    /** The list editor for the array flag [key]. */
    data class ListEditor(val key: String) : FlagRoute

    /** The records list at [trail] inside the record-list flag [key]: the flag's own list when [trail] is empty. */
    data class Records(val key: String, val trail: List<RecordStep> = emptyList()) : FlagRoute

    /** Record [index] of the list at [trail] inside the flag [key]. */
    data class Record(val key: String, val trail: List<RecordStep>, val index: Int) : FlagRoute

    /** A signal group laid out on its own screen. */
    data class SignalGroup(val title: String) : FlagRoute
}

/** One step into a nested records list: field [field] of the record at [index]. */
internal data class RecordStep(val index: Int, val field: String)

/** Routes as single strings, for rememberSaveable. */
internal object FlagRouteCodec {

    private const val SEPARATOR = "\u001F"
    private const val GROUP = "group"
    private const val LIST = "list"
    private const val RECORDS = "records"
    private const val RECORD = "record"
    private const val SIGNAL_GROUP = "signal-group"

    fun encode(route: FlagRoute): String = when (route) {
        is FlagRoute.Group -> listOf(GROUP) + route.path
        is FlagRoute.ListEditor -> listOf(LIST, route.key)
        is FlagRoute.Records -> listOf(RECORDS, route.key) + steps(route.trail)
        is FlagRoute.Record -> listOf(RECORD, route.key, route.index.toString()) + steps(route.trail)
        is FlagRoute.SignalGroup -> listOf(SIGNAL_GROUP, route.title)
    }.joinToString(SEPARATOR)

    fun decode(text: String): FlagRoute {
        val parts = text.split(SEPARATOR)
        return when (parts.first()) {
            GROUP -> FlagRoute.Group(parts.drop(1))
            LIST -> FlagRoute.ListEditor(parts[1])
            RECORDS -> FlagRoute.Records(parts[1], trail(parts.drop(2)))
            RECORD -> FlagRoute.Record(parts[1], trail(parts.drop(3)), parts[2].toInt())
            SIGNAL_GROUP -> FlagRoute.SignalGroup(parts[1])
            else -> error("Not a saved route: $text")
        }
    }

    private fun steps(trail: List<RecordStep>): List<String> = trail.flatMap { listOf(it.index.toString(), it.field) }

    private fun trail(parts: List<String>): List<RecordStep> =
        parts.chunked(2) { (index, field) -> RecordStep(index.toInt(), field) }
}

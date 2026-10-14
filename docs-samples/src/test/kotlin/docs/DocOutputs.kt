package docs

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = emptyMap()
}

package com.andyyhope.featureflag.schema

import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.errors.FlagSchemaException
import com.andyyhope.featureflag.errors.FlagSerializationException
import com.andyyhope.featureflag.json.IsoDate
import com.andyyhope.featureflag.json.JsonReader
import com.andyyhope.featureflag.json.JsonSyntaxException
import com.andyyhope.featureflag.json.JsonValue
import com.andyyhope.featureflag.json.JsonWriter
import com.andyyhope.featureflag.records.FlagRecordField
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import com.andyyhope.featureflag.values.KeyEncoding
import com.andyyhope.featureflag.values.toBox
import com.andyyhope.featureflag.values.toJson
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * A description of a host app's flag tree, complete enough for a companion app that has never seen
 * the host's types to render an editor for it. Byte-compatible with Swift's `FlagSchema` JSON.
 */
public data class FlagSchema(
    public val formatVersion: Int = CURRENT_FORMAT_VERSION,
    public val generatedAt: Instant = Instant.now(),
    /** Which app published this, for a companion that can see more than one. */
    public val applicationName: String? = null,
    /** What this set of flags is for, as the container describes itself. */
    public val description: String? = null,
    /** Every flag, depth first, in declaration order. */
    public val flags: List<Entry>,
    /** Every group, so the editor can label its sections. */
    public val groups: List<Group> = emptyList(),
) {
    /** One flag, keyed as it is stored. */
    public data class Entry(
        public val key: FlagKey,
        public val propertyPath: List<String>,
        public val description: String,
        public val valueType: FlagValueType,
        public val defaultValue: FlagValueBox,
        public val cases: List<FlagValueBox>? = null,
        public val remoteKey: String? = null,
        public val recordShape: List<FlagRecordField>? = null,
    )

    /** One nested container. Groups namespace their children and nothing else. */
    public data class Group(public val propertyPath: List<String>, public val description: String)

    /** Flags that resolve to the same key, mapped to the property paths that produced them. */
    public val duplicateKeys: Map<FlagKey, List<List<String>>> get() = duplicateKeys(flags)

    /** Every collision as one readable clause, or null when there are none. */
    public val duplicateKeyDescription: String?
        get() = duplicateKeys.takeIf { it.isNotEmpty() }
            ?.entries
            ?.sortedBy { it.key.rawValue }
            ?.joinToString("; ") { (key, paths) ->
                "'${key.rawValue}' is claimed by ${paths.joinToString(" and ") { it.joinToString(".") }}"
            }

    /** The declared type of every flag. Tolerates a duplicated key: the first wins. */
    public val valueTypes: Map<FlagKey, FlagValueType>
        get() = buildMap { flags.forEach { putIfAbsent(it.key, it.valueType) } }

    /** The permitted values of every enum flag. Flags that are not enums are absent. */
    public val valueCases: Map<FlagKey, List<FlagValueBox>>
        get() = buildMap {
            flags.forEach { entry -> entry.cases?.takeIf { it.isNotEmpty() }?.let { putIfAbsent(entry.key, it) } }
        }

    /** The record shape of every record flag. Flags that hold no records are absent. */
    public val recordShapes: Map<FlagKey, List<FlagRecordField>>
        get() = buildMap {
            flags.forEach { entry -> entry.recordShape?.takeIf { it.isNotEmpty() }?.let { putIfAbsent(entry.key, it) } }
        }

    /** The first entry per key, in schema order. Tolerates a duplicated key, as [valueTypes] does. */
    internal val entriesByKey: Map<FlagKey, Entry>
        get() = buildMap { flags.forEach { putIfAbsent(it.key, it) } }

    /** The document, exactly as Apple's `JSONSerialization` writes it. */
    public fun jsonBytes(): ByteArray {
        // A non-finite default has no JSON spelling; name the flag rather than fail somewhere deeper.
        flags.firstOrNull { it.defaultValue.containsNonFiniteNumber }?.let {
            throw FlagSerializationException.NonFiniteNumber(it.key)
        }
        return JsonWriter.prettyBytes(toJson())
    }

    /**
     * Writes [FILE_NAME] into [directory], creating it if needed, replacing any earlier one
     * atomically, so a reader never sees half a file. Safe to call from several threads or
     * processes at once: each writes its own temporary file and the last move wins.
     *
     * @throws IOException naming the target file, carrying the underlying failure.
     */
    public fun write(directory: File): File {
        val bytes = jsonBytes()
        val target = File(directory, FILE_NAME)
        // mkdirs() also returns false when another writer created the directory first.
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not write $target, because $directory is not a directory and could not be created.")
        }
        var temporary: File? = null
        try {
            temporary = File.createTempFile(FILE_NAME, ".tmp", directory)
            temporary.writeBytes(bytes)
            replace(temporary, target)
        } catch (e: IOException) {
            throw IOException("Could not write $target.", e)
        } finally {
            temporary?.delete()
        }
        return target
    }

    /** Moves [source] over [target] atomically where the file system can, and plainly where it cannot. */
    private fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (ignored: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun toJson(): JsonValue = JsonValue.Object(
        buildMap {
            put("formatVersion", JsonValue.Integer(formatVersion.toLong()))
            put("generatedAt", JsonValue.Text(IsoDate.format(generatedAt)))
            put("flags", JsonValue.Array(flags.map { it.toJson() }))
            put("groups", JsonValue.Array(groups.map { it.toJson() }))
            applicationName?.let { put("applicationName", JsonValue.Text(it)) }
            description?.let { put("description", JsonValue.Text(it)) }
        },
    )

    public companion object {
        public const val FILE_NAME: String = "flag-schema.json"
        public const val CURRENT_FORMAT_VERSION: Int = 1

        /**
         * Describes [spec] without building it. Two flags that encode to one key is a declaration
         * mistake with no runtime remedy, so it fails here, on the first launch of the build that has it.
         */
        public fun of(
            spec: FlagContainerSpec<*>,
            keyEncoding: KeyEncoding = KeyEncoding.Kebabcase,
            applicationName: String? = null,
            generatedAt: Instant = Instant.now(),
        ): FlagSchema {
            val flags = mutableListOf<Entry>()
            val groups = mutableListOf<Group>()
            collect(spec.flagDescriptors, keyEncoding, flags, groups)
            val schema = FlagSchema(
                generatedAt = generatedAt,
                applicationName = applicationName,
                description = spec.flagContainerDescription,
                flags = flags,
                groups = groups,
            )
            check(schema.duplicateKeyDescription == null) {
                "Flags must resolve to unique keys, but ${schema.duplicateKeyDescription}. " +
                    "Rename one of the properties, or use a KeyEncoding that tells them apart."
            }
            return schema
        }

        /** Reads a published document. Unknown fields are ignored; what cannot be trusted is refused. */
        public fun parse(bytes: ByteArray): FlagSchema {
            val root = try {
                JsonReader.parse(bytes)
            } catch (e: JsonSyntaxException) {
                malformed("not a JSON object", e)
            }
            val fields = (root as? JsonValue.Object)?.fields ?: malformed("not a JSON object")
            val version = when (val raw = fields["formatVersion"]) {
                is JsonValue.Integer -> raw.value
                // Swift reads the field with `as? Int`, which turns an NSNumber `true` into 1.
                is JsonValue.Bool -> if (raw.value) 1L else 0L
                else -> malformed("missing formatVersion")
            }
            if (version != CURRENT_FORMAT_VERSION.toLong()) throw FlagSchemaException.UnsupportedFormatVersion(version)
            val entries = (objects(fields["flags"]) ?: malformed("missing flags")).map(::entry)
            duplicateKeys(entries).keys.minByOrNull { it.rawValue }?.let {
                malformed("more than one flag uses the key '$it'")
            }
            return FlagSchema(
                formatVersion = version.toInt(),
                generatedAt = text(fields["generatedAt"])?.let(IsoDate::parse) ?: Instant.EPOCH,
                applicationName = text(fields["applicationName"]),
                description = text(fields["description"]),
                flags = entries,
                groups = objects(fields["groups"]).orEmpty().mapNotNull(::group),
            )
        }

        /** Reads the schema a host published into [directory]. */
        public fun read(directory: File): FlagSchema {
            val bytes = try {
                File(directory, FILE_NAME).readBytes()
            } catch (e: IOException) {
                throw FlagSchemaException.NotPublished(e)
            }
            return parse(bytes)
        }

        private fun collect(
            nodes: List<FlagSchemaNode>,
            encoding: KeyEncoding,
            flags: MutableList<Entry>,
            groups: MutableList<Group>,
        ) {
            nodes.forEach { node ->
                when (node) {
                    is FlagSchemaNode.Flag -> with(node.descriptor) {
                        flags += Entry(
                            key = encoding.key(keyPath),
                            propertyPath = keyPath.propertyNames,
                            description = description,
                            valueType = valueType,
                            defaultValue = defaultValue,
                            cases = cases,
                            remoteKey = remoteKey,
                            recordShape = recordShape,
                        )
                    }
                    is FlagSchemaNode.Group -> {
                        groups += Group(node.group.keyPath.propertyNames, node.group.description)
                        collect(node.group.children, encoding, flags, groups)
                    }
                }
            }
        }

        private fun duplicateKeys(flags: List<Entry>): Map<FlagKey, List<List<String>>> =
            flags.groupBy({ it.key }, { it.propertyPath }).filterValues { it.size > 1 }

        private fun entry(fields: Map<String, JsonValue>): Entry {
            val key = text(fields["key"])
            val typeName = text(fields["valueType"])
            val type = typeName?.let(FlagValueType::parse)
            if (key == null || typeName == null || type == null) malformed("flag entry is missing a key or value type")
            val default = fields["defaultValue"]?.toBox(type) ?: malformed("flag '$key' has a default that is not a $typeName")
            return Entry(
                key = FlagKey(key),
                propertyPath = strings(fields["propertyPath"]) ?: listOf(key),
                description = text(fields["description"]) ?: "",
                valueType = type,
                defaultValue = default,
                cases = (fields["cases"] as? JsonValue.Array)?.items?.mapNotNull { it.toBox(type) },
                remoteKey = text(fields["remoteKey"]),
                recordShape = objects(fields["recordShape"])?.mapNotNull(::recordField),
            )
        }

        private fun recordField(fields: Map<String, JsonValue>): FlagRecordField? {
            val name = text(fields["name"]) ?: return null
            val type = text(fields["type"])?.let(FlagValueType::parse) ?: return null
            return FlagRecordField(
                name = name,
                type = type,
                cases = (fields["cases"] as? JsonValue.Array)?.items?.mapNotNull { it.toBox(type) },
                defaultValue = fields["default"]?.toBox(type),
                fields = objects(fields["fields"])?.mapNotNull(::recordField),
                isKey = (fields["isKey"] as? JsonValue.Bool)?.value ?: false,
                isOptional = (fields["isOptional"] as? JsonValue.Bool)?.value ?: false,
                decodedName = text(fields["decodedName"]),
            )
        }

        private fun group(fields: Map<String, JsonValue>): Group? {
            val path = strings(fields["propertyPath"]) ?: return null
            return Group(path, text(fields["description"]) ?: "")
        }

        private fun malformed(reason: String, cause: Throwable? = null): Nothing =
            throw FlagSchemaException.Malformed(reason, cause)

        private fun text(value: JsonValue?): String? = (value as? JsonValue.Text)?.value

        /** Every item a string, or null: a list with anything else in it is not a list of strings. */
        private fun strings(value: JsonValue?): List<String>? =
            (value as? JsonValue.Array)?.items?.map { text(it) ?: return null }

        /** Every item an object, or null, as Swift's `as? [[String: Any]]` does. */
        private fun objects(value: JsonValue?): List<Map<String, JsonValue>>? =
            (value as? JsonValue.Array)?.items?.map { (it as? JsonValue.Object)?.fields ?: return null }
    }
}

private fun FlagSchema.Entry.toJson(): JsonValue = JsonValue.Object(
    buildMap {
        put("key", JsonValue.Text(key.rawValue))
        put("propertyPath", JsonValue.Array(propertyPath.map { JsonValue.Text(it) }))
        put("description", JsonValue.Text(description))
        put("valueType", JsonValue.Text(valueType.typeName))
        put("defaultValue", defaultValue.toJson())
        cases?.let { cases -> put("cases", JsonValue.Array(cases.map { it.toJson() })) }
        remoteKey?.let { put("remoteKey", JsonValue.Text(it)) }
        recordShape?.let { shape -> put("recordShape", JsonValue.Array(shape.map { it.toJson() })) }
    },
)

/** `isKey` and `isOptional` are written only when set, so an older shape reads the same. */
private fun FlagRecordField.toJson(): JsonValue = JsonValue.Object(
    buildMap {
        put("name", JsonValue.Text(name))
        put("type", JsonValue.Text(type.typeName))
        cases?.let { cases -> put("cases", JsonValue.Array(cases.map { it.toJson() })) }
        defaultValue?.let { put("default", it.toJson()) }
        fields?.let { fields -> put("fields", JsonValue.Array(fields.map { it.toJson() })) }
        if (isKey) put("isKey", JsonValue.Bool(true))
        if (isOptional) put("isOptional", JsonValue.Bool(true))
        decodedName?.let { put("decodedName", JsonValue.Text(it)) }
    },
)

private fun FlagSchema.Group.toJson(): JsonValue = JsonValue.Object(
    mapOf(
        "propertyPath" to JsonValue.Array(propertyPath.map { JsonValue.Text(it) }),
        "description" to JsonValue.Text(description),
    ),
)

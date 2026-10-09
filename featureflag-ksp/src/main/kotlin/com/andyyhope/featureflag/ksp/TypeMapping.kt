package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Origin
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ksp.toClassName

/** Every core name generated code refers to, in one place. */
internal object Names {
    private const val ROOT = "com.andyyhope.featureflag"
    private const val VALUES = "$ROOT.values"
    private const val RECORDS = "$ROOT.records"
    private const val SCHEMA = "$ROOT.schema"
    private const val CONTAINER = "$ROOT.container"

    const val FLAG_CONTAINER: String = "$ROOT.FlagContainer"
    const val FLAG: String = "$ROOT.Flag"
    const val FLAG_GROUP: String = "$ROOT.FlagGroup"
    const val FLAG_RECORD: String = "$ROOT.FlagRecord"
    const val FLAG_RECORD_KEY: String = "$ROOT.FlagRecordKey"
    const val FLAG_RECORD_PROPERTY: String = "$ROOT.FlagRecordProperty"
    const val FLAG_STRING_ENUM: String = "$VALUES.FlagStringEnum"
    const val FLAG_INT_ENUM: String = "$VALUES.FlagIntEnum"
    const val FLAG_RECORDS_NAME: String = "$RECORDS.FlagRecords"

    val FLAG_CODECS: ClassName = ClassName(VALUES, "FlagCodecs")
    val FLAG_CODEC: ClassName = ClassName(VALUES, "FlagCodec")
    val FLAG_KEY: ClassName = ClassName(VALUES, "FlagKey")
    val FLAG_KEY_PATH: ClassName = ClassName(VALUES, "FlagKeyPath")
    val FLAG_VALUE_BOX: ClassName = ClassName(VALUES, "FlagValueBox")
    val KEY_ENCODING: ClassName = ClassName(VALUES, "KeyEncoding")
    val FLAG_RECORDS: ClassName = ClassName(RECORDS, "FlagRecords")
    val FLAG_RECORD_CODEC: ClassName = ClassName(RECORDS, "FlagRecordCodec")
    val FLAG_RECORD_FIELD: ClassName = ClassName(RECORDS, "FlagRecordField")
    val FLAG_DESCRIPTOR: ClassName = ClassName(SCHEMA, "FlagDescriptor")
    val FLAG_GROUP_DESCRIPTOR: ClassName = ClassName(SCHEMA, "FlagGroupDescriptor")
    val FLAG_SCHEMA_NODE: ClassName = ClassName(SCHEMA, "FlagSchemaNode")
    val FLAG_LOOKUP: ClassName = ClassName(CONTAINER, "FlagLookup")
    val FLAG_CONTAINER_SPEC: ClassName = ClassName(CONTAINER, "FlagContainerSpec")
    val FLAG_REF: ClassName = ClassName(CONTAINER, "FlagRef")
    val READ: MemberName = MemberName(CONTAINER, "read")
    val LAZY: MemberName = MemberName("kotlin", "lazy")
    val LIST_OF: MemberName = MemberName("kotlin.collections", "listOf")
    val BUILD_MAP: MemberName = MemberName("kotlin.collections", "buildMap")
    val INSTANT: ClassName = ClassName("java.time", "Instant")
    val URI: ClassName = ClassName("java.net", "URI")

    /** `Outer.Inner` becomes `Outer_Inner`, so a nested declaration's generated name is still unique. */
    fun generatedName(declaration: KSClassDeclaration, suffix: String): ClassName {
        val simpleNames = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
            .map { it.simpleName.asString() }
            .toList()
            .asReversed()
        return ClassName(declaration.packageName.asString(), simpleNames.joinToString("_") + suffix)
    }
}

/** Whether this declaration carries the annotation named by [qualifiedName]. */
internal fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotations.any {
    it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

/** The value of [argument] on the annotation named [qualifiedName], or null when absent. */
internal fun KSAnnotated.annotationArgument(qualifiedName: String, argument: String): Any? = annotations
    .firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName }
    ?.arguments
    ?.firstOrNull { it.name?.asString() == argument }
    ?.value

/** Expands typealiases until a class remains, keeping nullability. */
internal fun KSType.expanded(): KSType {
    var type = this
    while (true) {
        val alias = type.declaration as? KSTypeAlias ?: return type
        val target = alias.type.resolve()
        type = if (type.isMarkedNullable) target.makeNullable() else target
    }
}

/** How one flag or record-field type becomes a `FlagCodecs` expression. */
internal class TypeMapping(private val resolver: Resolver) {

    sealed interface Result {
        /** [placeholder] is the value a record prototype passes for a field with no default. */
        data class Supported(
            val codec: CodeBlock,
            val placeholder: CodeBlock,
            val originatingFiles: List<KSFile>,
        ) : Result

        /** [record] names the `@FlagRecord` class when the type is one, or a list of one. */
        data class Unsupported(val record: String?) : Result

        data class MissingRecordCodec(val record: String, val codec: String) : Result
    }

    /** Maps a non-null [type]; nullability is the caller's decision. */
    fun map(type: KSType): Result {
        val expanded = type.expanded()
        if (expanded.isMarkedNullable) return Result.Unsupported(null)
        val declaration = expanded.declaration as? KSClassDeclaration ?: return Result.Unsupported(null)
        return when (declaration.qualifiedName?.asString()) {
            "kotlin.Boolean" -> scalar("boolean", "false")
            "kotlin.Int" -> scalar("int", "0")
            "kotlin.Long" -> scalar("long", "0L")
            "kotlin.Double" -> scalar("double", "0.0")
            "kotlin.Float" -> scalar("float", "0f")
            "kotlin.String" -> scalar("string", "\"\"")
            "kotlin.ByteArray" -> scalar("byteArray", "ByteArray(0)")
            "java.time.Instant" -> Result.Supported(
                CodeBlock.of("%T.instant", Names.FLAG_CODECS),
                CodeBlock.of("%T.EPOCH", Names.INSTANT),
                emptyList(),
            )
            "java.net.URI" -> Result.Supported(
                CodeBlock.of("%T.uri", Names.FLAG_CODECS),
                CodeBlock.of("%T(%S)", Names.URI, ""),
                emptyList(),
            )
            "kotlin.collections.List" -> collection(expanded.arguments[0].type?.resolve(), "list", "emptyList()")
            "kotlin.collections.Map" -> dictionary(expanded)
            Names.FLAG_RECORDS_NAME -> records(expanded.arguments[0].type?.resolve())
            else -> named(declaration)
        }
    }

    private fun scalar(codec: String, placeholder: String) = Result.Supported(
        CodeBlock.of("%T.%N", Names.FLAG_CODECS, codec),
        CodeBlock.of(placeholder),
        emptyList(),
    )

    private fun collection(element: KSType?, function: String, placeholder: String): Result {
        if (element == null) return Result.Unsupported(null)
        return when (val inner = map(element)) {
            is Result.Supported -> Result.Supported(
                CodeBlock.of("%T.%N(%L)", Names.FLAG_CODECS, function, inner.codec),
                CodeBlock.of(placeholder),
                inner.originatingFiles,
            )
            else -> inner
        }
    }

    private fun dictionary(type: KSType): Result {
        val key = type.arguments[0].type?.resolve()?.expanded()
        if (key == null || key.isMarkedNullable || key.declaration.qualifiedName?.asString() != "kotlin.String") {
            return Result.Unsupported(null)
        }
        return collection(type.arguments[1].type?.resolve(), "map", "emptyMap()")
    }

    /**
     * `FlagRecords<R>` needs `RFlagRecord`. It is generated when `R` is a `@FlagRecord` in this module;
     * otherwise it has to exist already, written by hand or generated in the module that declares `R`.
     */
    private fun records(element: KSType?): Result {
        val record = element?.expanded()?.declaration as? KSClassDeclaration ?: return Result.Unsupported(null)
        if (element.isMarkedNullable) return Result.Unsupported(null)
        val codec = Names.generatedName(record, "FlagRecord")
        val annotated = record.hasAnnotation(Names.FLAG_RECORD)
        val generatedHere = annotated && record.origin == Origin.KOTLIN
        val exists = resolver.getClassDeclarationByName(resolver.getKSNameFromString(codec.canonicalName)) != null
        return when {
            generatedHere || exists -> Result.Supported(
                CodeBlock.of("%T.records(%T)", Names.FLAG_CODECS, codec),
                CodeBlock.of("%T()", Names.FLAG_RECORDS),
                listOfNotNull(record.containingFile),
            )
            annotated -> Result.MissingRecordCodec(record.simpleName.asString(), codec.simpleName)
            else -> Result.Unsupported(null)
        }
    }

    private fun named(declaration: KSClassDeclaration): Result {
        if (declaration.hasAnnotation(Names.FLAG_RECORD)) return Result.Unsupported(declaration.simpleName.asString())
        if (declaration.classKind != ClassKind.ENUM_CLASS) return Result.Unsupported(null)
        val supertypes = declaration.getAllSuperTypes().mapNotNull { it.declaration.qualifiedName?.asString() }.toSet()
        val function = when {
            Names.FLAG_STRING_ENUM in supertypes -> "stringEnum"
            Names.FLAG_INT_ENUM in supertypes -> "intEnum"
            else -> "enumByName"
        }
        val enumName = declaration.toClassName()
        return Result.Supported(
            CodeBlock.of("%T.%N(%T.entries)", Names.FLAG_CODECS, function, enumName),
            CodeBlock.of("%T.entries.first()", enumName),
            listOfNotNull(declaration.containingFile),
        )
    }
}

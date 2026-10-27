package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** One primary-constructor property of a `@FlagRecord` class. */
internal class RecordField(
    val name: String,
    val type: TypeName,
    val codec: CodeBlock,
    val placeholder: CodeBlock,
    val hasDefault: Boolean,
    val isKey: Boolean,
    val isOptional: Boolean,
    val decodedName: String?,
) {
    val codecProperty: String get() = "${name}Codec"

    /** Optional fields have no fallback: absence is null, as in Swift. */
    val readsDefault: Boolean get() = hasDefault && !isOptional
}

internal class RecordModel(
    val name: ClassName,
    val codec: ClassName,
    val visibility: KModifier,
    val fields: List<RecordField>,
    val originatingFiles: List<KSFile>,
) {
    val key: RecordField? get() = fields.firstOrNull { it.isKey }
}

/**
 * Writes `RFlagRecord : FlagRecordCodec<R>` for one `@FlagRecord` class `R`, plus `FlagRecords<R>[key]`
 * when `R` names a key. Everything it reads has already passed [Checks.record].
 */
internal class RecordGenerator(resolver: Resolver, private val codeGenerator: CodeGenerator) {

    private val types = TypeMapping(resolver)

    fun parse(declaration: KSClassDeclaration): RecordModel {
        val files = mutableListOf<KSFile>()
        declaration.containingFile?.let(files::add)
        val fields = checkNotNull(declaration.primaryConstructor).parameters.map { field(it, files) }
        return RecordModel(
            name = declaration.toClassName(),
            codec = Names.generatedName(declaration, "FlagRecord"),
            visibility = declaration.generatedVisibility(),
            fields = fields,
            originatingFiles = files.distinct(),
        )
    }

    private fun field(parameter: KSValueParameter, files: MutableList<KSFile>): RecordField {
        val type = parameter.type.resolve()
        val mapped = types.map(type.makeNotNullable()) as TypeMapping.Result.Supported
        files += mapped.originatingFiles
        return RecordField(
            name = checkNotNull(parameter.name).asString(),
            type = type.makeNotNullable().toTypeName(),
            codec = mapped.codec,
            placeholder = if (type.isMarkedNullable) CodeBlock.of("null") else mapped.placeholder,
            hasDefault = parameter.hasDefault,
            isKey = parameter.hasAnnotation(Names.FLAG_RECORD_KEY),
            isOptional = type.isMarkedNullable,
            decodedName = parameter.annotationArgument(Names.FLAG_RECORD_PROPERTY, "key") as? String,
        )
    }

    fun generate(model: RecordModel) {
        val file = FileSpec.builder(model.codec).addType(codec(model))
        val key = model.key
        // An Int key would never be reached: List's own get(index: Int) wins over an extension.
        if (key != null && key.type != INT) file.addFunction(lookup(model, key))
        file.build().writeTo(codeGenerator, aggregating = false, originatingKSFiles = model.originatingFiles)
    }

    private fun codec(model: RecordModel): TypeSpec {
        val needsPrototype = model.fields.any { it.readsDefault }
        val builder = TypeSpec.objectBuilder(model.codec)
            .addModifiers(model.visibility)
            .addSuperinterface(Names.FLAG_RECORD_CODEC.parameterizedBy(model.name))
        model.fields.forEach { field ->
            builder.addProperty(
                PropertySpec
                    .builder(field.codecProperty, Names.FLAG_CODEC.parameterizedBy(field.type), KModifier.PRIVATE)
                    .initializer(field.codec)
                    .build(),
            )
        }
        builder.addProperty(
            PropertySpec.builder("keyField", STRING.copy(nullable = true), KModifier.OVERRIDE)
                .initializer("%S", model.key?.name)
                .build(),
        )
        builder.addProperty(
            PropertySpec.builder("shape", LIST.parameterizedBy(Names.FLAG_RECORD_FIELD), KModifier.OVERRIDE)
                .delegate(shape(model, needsPrototype))
                .build(),
        )
        builder.addFunction(boxes(model))
        builder.addFunction(fromBoxes(model))
        if (needsPrototype) builder.addFunction(prototype(model))
        return builder.build()
    }

    private fun shape(model: RecordModel, needsPrototype: Boolean): CodeBlock {
        val body = CodeBlock.builder().beginControlFlow("%M", Names.LAZY)
        if (needsPrototype) body.addStatement("val defaults = prototype()")
        body.add("%M(\n", Names.LIST_OF).indent()
        model.fields.forEach { field ->
            val arguments = mutableListOf(CodeBlock.of("%S", field.name), CodeBlock.of("%N", field.codecProperty))
            if (field.readsDefault) arguments += CodeBlock.of("default = defaults.%N", field.name)
            if (field.isKey) arguments += CodeBlock.of("isKey = true")
            if (field.isOptional) arguments += CodeBlock.of("isOptional = true")
            field.decodedName?.let { arguments += CodeBlock.of("decodedName = %S", it) }
            body.add("%T.of(%L),\n", Names.FLAG_RECORD_FIELD, arguments.joinToCode())
        }
        return body.unindent().add(")\n").endControlFlow().build()
    }

    /** A null optional field is left out, as in Swift: there is no null to store. */
    private fun boxes(model: RecordModel): FunSpec {
        val body = CodeBlock.builder().beginControlFlow("return %M", Names.BUILD_MAP)
        model.fields.forEach { field ->
            if (field.isOptional) {
                body.addStatement(
                    "record.%N?.%M { put(%S, %N.encode(it)) }",
                    field.name, Names.LET, field.name, field.codecProperty,
                )
            } else {
                body.addStatement("put(%S, %N.encode(record.%N))", field.name, field.codecProperty, field.name)
            }
        }
        return FunSpec.builder("boxes")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("record", model.name)
            .returns(MAP.parameterizedBy(STRING, Names.FLAG_VALUE_BOX))
            .addCode(body.endControlFlow().build())
            .build()
    }

    /** A record is all of its required fields or nothing; a missing optional one reads as null. */
    private fun fromBoxes(model: RecordModel): FunSpec {
        val body = CodeBlock.builder().add("val record = %T(\n", model.name).indent()
        model.fields.forEach { field ->
            val fallback = if (field.isOptional) "" else " ?: return null"
            body.add(
                "%N = boxes[%S]?.%M(%N::decode)$fallback,\n",
                field.name, field.name, Names.LET, field.codecProperty,
            )
        }
        body.unindent().add(")\n").addStatement("return record")
        return FunSpec.builder("fromBoxes")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("boxes", MAP.parameterizedBy(STRING, Names.FLAG_VALUE_BOX))
            .returns(model.name.copy(nullable = true))
            .addCode(body.build())
            .build()
    }

    /** The record built with placeholders for the fields that have no default, so the rest read their defaults. */
    private fun prototype(model: RecordModel): FunSpec {
        val placeholders = model.fields
            .filterNot { it.hasDefault }
            .map { CodeBlock.of("%N = %L", it.name, it.placeholder) }
        val message = "Could not read the field defaults of '${model.name.simpleName}': its constructor threw when " +
            "'${model.codec.simpleName}' called it with placeholder values (false, 0, \"\", empty, an enum's first " +
            "case) for the fields that have no default. Give those fields defaults the constructor accepts."
        return FunSpec.builder("prototype")
            .addModifiers(KModifier.PRIVATE)
            .returns(model.name)
            .beginControlFlow("return try")
            .addStatement("%T(%L)", model.name, placeholders.joinToCode())
            .nextControlFlow("catch (e: %T)", EXCEPTION)
            .addStatement("throw %T(%S, e)", ILLEGAL_STATE, message)
            .endControlFlow()
            .build()
    }

    /** `records["prod"]`, comparing stored forms the way Swift's `FlagRecords[key]` does. */
    private fun lookup(model: RecordModel, key: RecordField): FunSpec = FunSpec.builder("get")
        .addModifiers(model.visibility, KModifier.OPERATOR)
        .receiver(Names.FLAG_RECORDS.parameterizedBy(model.name))
        .addParameter("key", key.type)
        .returns(model.name.copy(nullable = true))
        .addStatement("val codec = %L", key.codec)
        .addStatement("val wanted = codec.encode(key)")
        .addStatement("return %M { codec.encode(it.%N) == wanted }", Names.FIRST_OR_NULL, key.name)
        .build()

    private companion object {
        val EXCEPTION: ClassName = ClassName("kotlin", "Exception")
        val ILLEGAL_STATE: ClassName = ClassName("kotlin", "IllegalStateException")
    }
}

package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/** One `@FlagContainer` interface, read for generation. */
internal class ContainerModel(
    val name: ClassName,
    val refs: ClassName,
    val spec: ClassName,
    val description: String?,
    val visibility: KModifier,
    val members: List<ContainerMember>,
    val originatingFiles: List<KSFile>,
)

internal sealed interface ContainerMember {
    val name: String
    val description: String

    class Flag(
        override val name: String,
        override val description: String,
        val remoteKey: String?,
        val type: TypeName,
        val codec: CodeBlock,
    ) : ContainerMember

    class Group(
        override val name: String,
        override val description: String,
        val type: ClassName,
        val refs: ClassName,
        val spec: ClassName,
    ) : ContainerMember
}

/**
 * Writes `XRefs` and `XSpec` for one `@FlagContainer` interface `X`. Everything it reads has already
 * passed [Checks.container], so an unsupported type here is a bug in the checks, not in the input.
 */
internal class ContainerGenerator(private val resolver: Resolver, private val codeGenerator: CodeGenerator) {

    private val types = TypeMapping(resolver)

    fun parse(declaration: KSClassDeclaration): ContainerModel {
        val files = mutableListOf<KSFile>()
        declaration.containingFile?.let(files::add)
        val members = declaration.getDeclaredProperties().mapNotNull { property ->
            when {
                property.hasAnnotation(Names.FLAG_GROUP) -> group(property, files)
                property.hasAnnotation(Names.FLAG) -> flag(property, files)
                else -> null
            }
        }.toList()
        val description = declaration.annotationArgument(Names.FLAG_CONTAINER, "description") as? String
        return ContainerModel(
            name = declaration.toClassName(),
            refs = Names.generatedName(declaration, "Refs"),
            spec = Names.generatedName(declaration, "Spec"),
            description = description?.takeIf { it.isNotEmpty() },
            visibility = declaration.generatedVisibility(),
            members = members,
            originatingFiles = files.distinct(),
        )
    }

    private fun flag(property: KSPropertyDeclaration, files: MutableList<KSFile>): ContainerMember.Flag {
        val type = property.type.resolve()
        val mapped = types.map(type) as TypeMapping.Result.Supported
        files += mapped.originatingFiles
        return ContainerMember.Flag(
            name = property.simpleName.asString(),
            description = property.annotationArgument(Names.FLAG, "description") as? String ?: "",
            remoteKey = (property.annotationArgument(Names.FLAG, "remoteKey") as? String)?.takeIf { it.isNotEmpty() },
            type = type.toTypeName(),
            codec = mapped.codec,
        )
    }

    private fun group(property: KSPropertyDeclaration, files: MutableList<KSFile>): ContainerMember.Group {
        val target = property.type.resolve().expanded(resolver).declaration as KSClassDeclaration
        target.containingFile?.let(files::add)
        return ContainerMember.Group(
            name = property.simpleName.asString(),
            description = property.annotationArgument(Names.FLAG_GROUP, "description") as? String ?: "",
            type = target.toClassName(),
            refs = Names.generatedName(target, "Refs"),
            spec = Names.generatedName(target, "Spec"),
        )
    }

    fun generate(model: ContainerModel) {
        FileSpec.builder(model.spec)
            .addType(refs(model))
            .addType(spec(model))
            .build()
            .writeTo(codeGenerator, aggregating = false, originatingKSFiles = model.originatingFiles)
    }

    private fun refs(model: ContainerModel): TypeSpec {
        val properties = model.members.map { member ->
            when (member) {
                is ContainerMember.Flag -> flagRef(model, member)
                is ContainerMember.Group -> PropertySpec.builder(member.name, member.refs)
                    .initializer("%T(prefix.appending(%S))", member.refs, member.name)
                    .build()
            }
        }
        return TypeSpec.classBuilder(model.refs)
            .addModifiers(model.visibility, KModifier.OPEN)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("prefix", Names.FLAG_KEY_PATH).build())
            .addProperties(properties)
            .build()
    }

    /**
     * `FlagRef(path, name, description, remoteKey, codec) { … }`. `XSpec`'s own ref evaluates the default
     * getter once, against `XSpec.flagDefaults`; a ref under a mounted group shares that one value.
     */
    private fun flagRef(model: ContainerModel, member: ContainerMember.Flag): PropertySpec =
        PropertySpec.builder(member.name, Names.FLAG_REF.parameterizedBy(member.type))
            .initializer(
                "%T(prefix.appending(%S), %S, %S, %S, %L) {\n⇥" +
                    "if (this === %T) %T.flagDefaults.%N else %T.%N.defaultValue\n⇤}",
                Names.FLAG_REF, member.name, member.name, member.description, member.remoteKey, member.codec,
                model.spec, model.spec, member.name, model.spec, member.name,
            )
            .build()

    private fun spec(model: ContainerModel): TypeSpec {
        val flags = model.members.filterIsInstance<ContainerMember.Flag>()
        return TypeSpec.objectBuilder(model.spec)
            .addModifiers(model.visibility)
            .superclass(model.refs)
            .addSuperclassConstructorParameter("%T.ROOT", Names.FLAG_KEY_PATH)
            .addSuperinterface(Names.FLAG_CONTAINER_SPEC.parameterizedBy(model.name))
            .addProperty(
                PropertySpec.builder("flagContainerDescription", STRING.copy(nullable = true), KModifier.OVERRIDE)
                    .initializer("%S", model.description)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("flagDefaults", model.name, KModifier.OVERRIDE)
                    .initializer("%L", defaults(model))
                    .build(),
            )
            .addProperty(
                PropertySpec
                    .builder("flagDescriptors", LIST.parameterizedBy(Names.FLAG_SCHEMA_NODE), KModifier.OVERRIDE)
                    .delegate(descriptors(model))
                    .build(),
            )
            .addFunction(
                FunSpec.builder("createFlags")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("lookup", Names.FLAG_LOOKUP)
                    .addParameter("prefix", Names.FLAG_KEY_PATH)
                    .returns(model.name)
                    .addStatement("return Impl(lookup, prefix)")
                    .build(),
            )
            .addType(impl(model, flags))
            .apply { if (flags.isNotEmpty()) addType(keys(flags)) }
            .build()
    }

    /** `object : X`, overriding only the groups: a flag without a default getter fails to compile here. */
    private fun defaults(model: ContainerModel): TypeSpec = TypeSpec.anonymousClassBuilder()
        .addSuperinterface(model.name)
        .addProperties(
            model.members.filterIsInstance<ContainerMember.Group>().map { group ->
                PropertySpec.builder(group.name, group.type, KModifier.OVERRIDE)
                    .getter(FunSpec.getterBuilder().addStatement("return %T.flagDefaults", group.spec).build())
                    .build()
            },
        )
        .build()

    private fun descriptors(model: ContainerModel): CodeBlock {
        val body = CodeBlock.builder().beginControlFlow("%M", Names.LAZY).add("%M(\n", Names.LIST_OF).indent()
        model.members.forEach { member ->
            when (member) {
                is ContainerMember.Flag -> body.add(
                    "%T.Flag(%T.of(%S, %S, %S, %N.codec, %N.defaultValue)),\n",
                    Names.FLAG_SCHEMA_NODE, Names.FLAG_DESCRIPTOR, member.name, member.description,
                    member.remoteKey, member.name, member.name,
                )
                is ContainerMember.Group -> body.add(
                    "%T.Group(%T(%S, %T(%M(%S)), %S, %T.flagDescriptors.map { it.prefixed(%S) })),\n",
                    Names.FLAG_SCHEMA_NODE, Names.FLAG_GROUP_DESCRIPTOR, member.name, Names.FLAG_KEY_PATH,
                    Names.LIST_OF, member.name, member.description, member.spec, member.name,
                )
            }
        }
        return body.unindent().add(")\n").endControlFlow().build()
    }

    /**
     * Reads through the lookup, falling back to `XSpec.name.defaultValue`: the default getter, evaluated
     * once against the compiled defaults, so every read path agrees on it.
     */
    private fun impl(model: ContainerModel, flags: List<ContainerMember.Flag>): TypeSpec {
        val builder = TypeSpec.classBuilder("Impl")
            .addModifiers(KModifier.PRIVATE)
            .addSuperinterface(model.name)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("flagLookup", Names.FLAG_LOOKUP)
                    .addParameter("flagPrefix", Names.FLAG_KEY_PATH)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("flagLookup", Names.FLAG_LOOKUP, KModifier.PRIVATE)
                    .initializer("flagLookup")
                    .build(),
            )
        if (flags.isNotEmpty()) {
            builder.addProperty(
                PropertySpec.builder("flagKeys", KEYS, KModifier.PRIVATE)
                    .initializer("%T(flagLookup.keyEncoding, flagPrefix)", KEYS)
                    .build(),
            )
        }
        model.members.forEach { member ->
            val property = when (member) {
                is ContainerMember.Flag -> PropertySpec.builder(member.name, member.type, KModifier.OVERRIDE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement(
                                "return flagLookup.%M(flagKeys.%N, %T.%N.codec) { %T.%N.defaultValue }",
                                Names.READ, member.name, model.spec, member.name, model.spec, member.name,
                            )
                            .build(),
                    )
                is ContainerMember.Group -> PropertySpec.builder(member.name, member.type, KModifier.OVERRIDE)
                    .initializer("%T.createFlags(flagLookup, flagPrefix.appending(%S))", member.spec, member.name)
            }
            builder.addProperty(property.build())
        }
        return builder.build()
    }

    /** Each flag's key, encoded once per instance rather than on every read. */
    private fun keys(flags: List<ContainerMember.Flag>): TypeSpec = TypeSpec.classBuilder(KEYS)
        .addModifiers(KModifier.PRIVATE)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("encoding", Names.KEY_ENCODING)
                .addParameter("prefix", Names.FLAG_KEY_PATH)
                .build(),
        )
        .addProperties(
            flags.map {
                PropertySpec.builder(it.name, Names.FLAG_KEY)
                    .initializer("encoding.key(prefix.appending(%S))", it.name)
                    .build()
            },
        )
        .build()

    private companion object {
        val KEYS: ClassName = ClassName("", "Keys")
    }
}

/** `internal` when this declaration, or one enclosing it, is internal; `public` otherwise. */
internal fun KSClassDeclaration.generatedVisibility(): KModifier {
    val internal = generateSequence(this) { it.parentDeclaration as? KSClassDeclaration }
        .any { Modifier.INTERNAL in it.modifiers }
    return if (internal) KModifier.INTERNAL else KModifier.PUBLIC
}

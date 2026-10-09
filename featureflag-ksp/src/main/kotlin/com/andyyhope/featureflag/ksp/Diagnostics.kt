package com.andyyhope.featureflag.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isProtected
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin

/**
 * Every message the processor reports. The wording follows the Swift macros' diagnostics
 * (`FlagContainerDiagnostic`, `FlagRecordDiagnostic`) wherever the mistake is the same one.
 */
internal object Diagnostics {

    // Containers

    const val INTERFACES_ONLY: String = "'@FlagContainer' can only be applied to an interface"

    const val OPTIONAL_UNSUPPORTED: String =
        "flags cannot be optional: a flag always has a value, because 'default' is what it falls back to. " +
            "Use a sentinel the type already has, or an enum with a case for 'unset'"

    fun containerPrivate(name: String): String =
        "'@FlagContainer' interface '$name' cannot be private: the generated '${name}Spec' has to implement it"

    fun containerTypeParameters(name: String): String =
        "'@FlagContainer' interface '$name' cannot have type parameters: " +
            "every flag's type has to be known when the schema is published"

    fun containerSupertypes(name: String): String =
        "'@FlagContainer' interface '$name' cannot extend other interfaces: declare its flags and groups " +
            "directly, and nest shared ones with '@FlagGroup'"

    fun abstractFunction(name: String): String =
        "'$name' is an abstract function, which the generated implementation cannot provide: " +
            "'@FlagContainer' interfaces declare flags and groups as properties. Give it a body, or move it elsewhere"

    fun reservedName(name: String, container: String): String =
        "'$name' is reserved for the code generated for '$container'. Rename the property"

    fun unattributedProperty(name: String): String =
        "'$name' has no '@Flag' or '@FlagGroup', so the generated implementation cannot provide it. " +
            "Add '@FlagGroup(description = …)' if it is a nested container, '@Flag(description = …)' and a " +
            "default getter if it is a value, or give it a getter if it is neither"

    fun recordListNeedsFlag(name: String): String =
        "'$name' holds a list of records, which is a flag's value rather than a nested container, so it needs " +
            "'@Flag(description = …)' — '@FlagGroup' is for a nested '@FlagContainer'"

    fun defaultRequired(name: String, type: String): String =
        "'@Flag' needs a 'default' value to fall back to: give '$name' a getter, as in 'val $name: $type get() = …'"

    fun mutableProperty(name: String): String =
        "'$name' must be a 'val': flags are read through a pole and changed with an override, never by assignment"

    fun propertyShape(name: String): String =
        "'$name' must be a plain property the generated code can override: not private, and without a receiver"

    fun groupNotAContainer(name: String, type: String): String =
        "'$name' is a '@FlagGroup', but '$type' is not a '@FlagContainer' interface"

    fun groupNotGenerated(name: String, type: String, spec: String): String =
        "'$name' is a '@FlagGroup' of '$type', but '$spec' does not exist: " +
            "apply the featureflag-ksp processor to the module that declares '$type'"

    fun groupHasGetter(name: String): String =
        "'$name' is a '@FlagGroup', so the generated code provides it: remove its getter"

    fun groupCycle(name: String, container: String, path: String): String =
        "'@FlagGroup' '$name' makes '$container' contain itself ($path), so its flags could never finish building"

    fun unsupportedFlagType(name: String, type: String): String =
        "'$name' has type '$type', which a flag cannot hold. Use Boolean, Int, Long, Double, Float, String, " +
            "ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of a " +
            "'@FlagRecord' class"

    fun recordAsFlag(record: String): String =
        "a record is stored as a list — declare the flag as 'FlagRecords<$record>' rather than '$record' or " +
            "'List<$record>'"

    // Records

    const val RECORD_CLASS_ONLY: String = "'@FlagRecord' can only be applied to a class with a primary constructor"

    const val FIELDS_REQUIRED: String =
        "'@FlagRecord' needs at least one field: a record is a shape, and an empty one gives the editor nothing to show"

    const val KEY_CANNOT_BE_OPTIONAL: String =
        "a record's key cannot be optional: it is the field that tells one record from another, " +
            "so every record has to carry it"

    fun recordPrivate(name: String): String =
        "'@FlagRecord' class '$name' cannot be private: the generated '${name}FlagRecord' has to construct it"

    fun parameterNotProperty(name: String): String =
        "'$name' is a constructor parameter but not a property, so a record could not be written back out. " +
            "Declare it 'val $name'"

    fun oneKeyOnly(names: List<String>): String =
        "a record has one key at most, and ${names.joinToString(", ")} are all marked '@FlagRecordKey' — " +
            "the key is the single field that tells one record from another"

    fun unsupportedFieldType(name: String, type: String): String =
        "record field '$name' has type '$type', which a record field cannot hold. Use Boolean, Int, Long, Double, " +
            "Float, String, ByteArray, Instant, URI, an enum, a List or Map<String, …> of those, or FlagRecords of " +
            "a '@FlagRecord' class; a nullable type makes the field optional"

    fun recordAsField(record: String): String =
        "a record is stored as a list — declare the field as 'FlagRecords<$record>' rather than '$record' or " +
            "'List<$record>'"

    fun recordNotGenerated(record: String, codec: String): String =
        "'$codec' does not exist: apply the featureflag-ksp processor to the module that declares '$record'"

    fun emptyDecodedName(name: String): String =
        "'@FlagRecordProperty' on '$name' needs a non-empty 'key': it is the name the field is read from in a payload"
}

/**
 * Every check that runs before generation. Each reports at the declaration that is wrong, so the
 * author never meets an error inside generated code. Returns false when anything was reported.
 */
internal class Checks(private val resolver: Resolver, private val logger: KSPLogger) {

    private val types = TypeMapping(resolver)

    fun container(declaration: KSClassDeclaration): Boolean {
        if (declaration.classKind != ClassKind.INTERFACE) return report(Diagnostics.INTERFACES_ONLY, declaration)
        val name = declaration.simpleName.asString()
        var valid = true
        if (declaration.isHidden()) valid = report(Diagnostics.containerPrivate(name), declaration)
        if (declaration.typeParameters.isNotEmpty()) {
            valid = report(Diagnostics.containerTypeParameters(name), declaration)
        }
        val supertypes = declaration.superTypes.map { it.resolve().declaration.qualifiedName?.asString() }
        if (supertypes.any { it != "kotlin.Any" }) valid = report(Diagnostics.containerSupertypes(name), declaration)
        declaration.getDeclaredFunctions().filter { it.isAbstract }.forEach {
            valid = report(Diagnostics.abstractFunction(it.simpleName.asString()), it)
        }
        declaration.getDeclaredProperties().forEach { if (!property(it, name)) valid = false }
        return valid
    }

    private fun property(property: KSPropertyDeclaration, container: String): Boolean {
        val name = property.simpleName.asString()
        val isFlag = property.hasAnnotation(Names.FLAG)
        val isGroup = property.hasAnnotation(Names.FLAG_GROUP)
        val type = property.type.resolve()
        return when {
            name in RESERVED -> report(Diagnostics.reservedName(name, container), property)
            !isFlag && !isGroup -> when {
                !property.isAbstract() -> true
                type.isRecordList() -> report(Diagnostics.recordListNeedsFlag(name), property)
                else -> report(Diagnostics.unattributedProperty(name), property)
            }
            property.isMutable -> report(Diagnostics.mutableProperty(name), property)
            property.extensionReceiver != null || property.isPrivate() ->
                report(Diagnostics.propertyShape(name), property)
            isGroup -> group(property, name, type)
            else -> flag(property, name, type)
        }
    }

    private fun group(property: KSPropertyDeclaration, name: String, type: KSType): Boolean {
        if (type.isRecordList()) return report(Diagnostics.recordListNeedsFlag(name), property)
        if (!property.isAbstract()) return report(Diagnostics.groupHasGetter(name), property)
        val target = type.expanded().declaration as? KSClassDeclaration
        val isContainer = target != null && !type.isMarkedNullable && target.classKind == ClassKind.INTERFACE &&
            target.hasAnnotation(Names.FLAG_CONTAINER)
        if (target == null || !isContainer) return report(Diagnostics.groupNotAContainer(name, type.render()), property)
        val spec = Names.generatedName(target, "Spec")
        val exists = target.origin == Origin.KOTLIN ||
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(spec.canonicalName)) != null
        if (!exists) {
            return report(Diagnostics.groupNotGenerated(name, target.simpleName.asString(), spec.simpleName), property)
        }
        return true
    }

    private fun flag(property: KSPropertyDeclaration, name: String, type: KSType): Boolean {
        if (type.isMarkedNullable) return report(Diagnostics.OPTIONAL_UNSUPPORTED, property)
        if (property.isAbstract()) return report(Diagnostics.defaultRequired(name, type.render()), property)
        return when (val mapped = types.map(type)) {
            is TypeMapping.Result.Supported -> true
            is TypeMapping.Result.Unsupported -> report(
                mapped.record?.let(Diagnostics::recordAsFlag) ?: Diagnostics.unsupportedFlagType(name, type.render()),
                property,
            )
            is TypeMapping.Result.MissingRecordCodec ->
                report(Diagnostics.recordNotGenerated(mapped.record, mapped.codec), property)
        }
    }

    fun record(declaration: KSClassDeclaration): Boolean {
        val constructor = declaration.primaryConstructor
        val concrete = declaration.classKind == ClassKind.CLASS &&
            Modifier.ABSTRACT !in declaration.modifiers && Modifier.SEALED !in declaration.modifiers
        if (!concrete || constructor == null) return report(Diagnostics.RECORD_CLASS_ONLY, declaration)
        val name = declaration.simpleName.asString()
        if (declaration.isHidden() || constructor.isPrivate()) {
            return report(Diagnostics.recordPrivate(name), declaration)
        }
        if (constructor.parameters.isEmpty()) return report(Diagnostics.FIELDS_REQUIRED, declaration)
        var valid = true
        constructor.parameters.forEach { if (!field(it)) valid = false }
        val keys = constructor.parameters.filter { it.hasAnnotation(Names.FLAG_RECORD_KEY) }
        if (keys.size > 1) {
            valid = report(Diagnostics.oneKeyOnly(keys.map { it.name?.asString().orEmpty() }), declaration)
        }
        return valid
    }

    private fun field(parameter: KSValueParameter): Boolean {
        val name = parameter.name?.asString().orEmpty()
        val type = parameter.type.resolve()
        val decodedName = parameter.annotationArgument(Names.FLAG_RECORD_PROPERTY, "key") as? String
        return when {
            !parameter.isVal && !parameter.isVar -> report(Diagnostics.parameterNotProperty(name), parameter)
            parameter.hasAnnotation(Names.FLAG_RECORD_KEY) && type.isMarkedNullable ->
                report(Diagnostics.KEY_CANNOT_BE_OPTIONAL, parameter)
            decodedName != null && decodedName.isEmpty() -> report(Diagnostics.emptyDecodedName(name), parameter)
            else -> when (val mapped = types.map(type.makeNotNullable())) {
                is TypeMapping.Result.Supported -> true
                is TypeMapping.Result.Unsupported -> report(
                    mapped.record?.let(Diagnostics::recordAsField)
                        ?: Diagnostics.unsupportedFieldType(name, type.render()),
                    parameter,
                )
                is TypeMapping.Result.MissingRecordCodec ->
                    report(Diagnostics.recordNotGenerated(mapped.record, mapped.codec), parameter)
            }
        }
    }

    /** Reports every `@FlagGroup` that leads back to its own container; returns the containers involved. */
    fun cycles(containers: List<KSClassDeclaration>): Set<KSClassDeclaration> {
        val byName = containers.associateBy { it.qualifiedName?.asString() }
        val cyclic = mutableSetOf<KSClassDeclaration>()
        for (container in containers) {
            for ((property, target) in groups(container)) {
                val path = pathBack(container, byName[target], byName, listOf(container)) ?: continue
                cyclic += container
                report(
                    Diagnostics.groupCycle(
                        property.simpleName.asString(),
                        container.simpleName.asString(),
                        path.joinToString(" → ") { it.simpleName.asString() },
                    ),
                    property,
                )
            }
        }
        return cyclic
    }

    /**
     * Qualified names of the containers and records [declaration]'s generated code refers to: group
     * targets and `@FlagRecord` classes in flag types. Used to withhold what depends on a withheld one.
     */
    fun dependencies(declaration: KSClassDeclaration): Set<String> {
        val names = mutableSetOf<String>()
        declaration.primaryConstructor?.takeIf { declaration.hasAnnotation(Names.FLAG_RECORD) }
            ?.parameters?.forEach { names += it.type.resolve().recordClasses() }
        declaration.getDeclaredProperties().forEach {
            val type = it.type.resolve()
            if (it.hasAnnotation(Names.FLAG_GROUP)) {
                type.expanded().declaration.qualifiedName?.asString()?.let(names::add)
            } else if (it.hasAnnotation(Names.FLAG)) {
                names += type.recordClasses()
            }
        }
        return names
    }

    private fun pathBack(
        start: KSClassDeclaration,
        next: KSClassDeclaration?,
        byName: Map<String?, KSClassDeclaration>,
        path: List<KSClassDeclaration>,
    ): List<KSClassDeclaration>? = when {
        next == null -> null
        next == start -> path + next
        next in path -> null
        else -> groups(next).firstNotNullOfOrNull { (_, target) ->
            pathBack(start, byName[target], byName, path + next)
        }
    }

    private fun groups(container: KSClassDeclaration): List<Pair<KSPropertyDeclaration, String?>> =
        container.getDeclaredProperties()
            .filter { it.hasAnnotation(Names.FLAG_GROUP) }
            .map { it to it.type.resolve().expanded().declaration.qualifiedName?.asString() }
            .toList()

    private fun report(message: String, at: KSNode): Boolean {
        logger.error(message, at)
        return false
    }

    private companion object {
        /** Members of the generated `XSpec` and its `Impl`, which a property of the same name would clash with. */
        val RESERVED: Set<String> = setOf(
            "flagDefaults", "flagDescriptors", "flagContainerDescription", "createFlags", "flagLookup", "flagKeys",
        )
    }
}

/** Private or protected here or on an enclosing declaration: generated code in another file cannot reach it. */
internal fun KSClassDeclaration.isHidden(): Boolean =
    generateSequence(this) { it.parentDeclaration as? KSClassDeclaration }.any { it.isPrivate() || it.isProtected() }

/** A type written the way a person would write it, for messages. */
internal fun KSType.render(): String {
    val arguments = arguments.joinToString(", ") { it.type?.resolve()?.render() ?: "*" }
    val base = declaration.simpleName.asString() + if (arguments.isEmpty()) "" else "<$arguments>"
    return if (isMarkedNullable) "$base?" else base
}

/** Whether this is `FlagRecords<…>`, in any spelling. */
internal fun KSType.isRecordList(): Boolean =
    expanded().declaration.qualifiedName?.asString() == Names.FLAG_RECORDS_NAME

/** Qualified names of every `@FlagRecord` class [this] type mentions, through lists, maps and `FlagRecords`. */
private fun KSType.recordClasses(): Set<String> {
    val expanded = expanded()
    val own = expanded.declaration.takeIf { it.hasAnnotation(Names.FLAG_RECORD) }?.qualifiedName?.asString()
    return setOfNotNull(own) + expanded.arguments.flatMap { it.type?.resolve()?.recordClasses().orEmpty() }
}

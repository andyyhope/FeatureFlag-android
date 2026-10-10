package com.andyyhope.featureflag

/**
 * Declares a set of flags. Apply it to an interface; featureflag-ksp generates `XSpec`, the
 * [com.andyyhope.featureflag.container.FlagContainerSpec] a pole is built from, and `XRefs`, the
 * typed [com.andyyhope.featureflag.container.FlagRef] tree.
 *
 * ```kotlin
 * @FlagContainer(description = "Everything the checkout team can turn on")
 * interface AppFlags {
 *     @Flag(description = "Show the redesigned onboarding", remoteKey = "featureToggles.onboarding.v2")
 *     val newOnboarding: Boolean get() = false
 *
 *     @FlagGroup(description = "Checkout")
 *     val checkout: CheckoutFlags
 * }
 * ```
 *
 * @property description what this set of flags is for, shown by the companion. Empty says nothing.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagContainer(val description: String = "")

/**
 * Declares one flag. The property's default getter is its compiled default: the value it falls back
 * to when no source supplies one, or what is stored will not decode.
 *
 * The getter is evaluated once, against the compiled defaults. A getter that reads another flag
 * sees that flag's compiled default, never a value a source supplies, and a non-constant one
 * (`Instant.now()`) keeps the value of that first evaluation. Every read path reports that one
 * value: `pole.flags`, `pole[ref]`, `pole.flow(ref)`, `pole.resolution(ref)` and the schema.
 *
 * @property description what the flag does, for the companion app.
 * @property remoteKey dot path into a remote payload. Empty means the flag is not remotely overridable.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Flag(val description: String, val remoteKey: String = "")

/**
 * Nests another `@FlagContainer`, namespacing its keys under this property's name. A group has no
 * value of its own and never gates the flags beneath it.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagGroup(val description: String)

/**
 * Makes a class something a flag can hold a list of, as `FlagRecords<R>`. Every primary-constructor
 * property is a field, in declaration order; a nullable one is optional, and a constructor default is
 * the field's default. featureflag-ksp generates `RFlagRecord`, its codec.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecord

/** Marks the field that tells one record from another. A record has one at most. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecordKey

/**
 * Reads a field from a payload under [key] instead of its property name. Decode-only: the record's
 * stored form stays keyed by the property name.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class FlagRecordProperty(val key: String)

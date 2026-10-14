package com.andyyhope.featureflag.android.sources

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.andyyhope.featureflag.android.bridge.FlagBridgeContract
import com.andyyhope.featureflag.container.FlagContainerSpec
import com.andyyhope.featureflag.container.FlagLookup
import com.andyyhope.featureflag.container.FlagRef
import com.andyyhope.featureflag.errors.FlagException
import com.andyyhope.featureflag.pole.FlagPole
import com.andyyhope.featureflag.schema.FlagDescriptor
import com.andyyhope.featureflag.schema.FlagSchemaNode
import com.andyyhope.featureflag.sources.FlagChange
import com.andyyhope.featureflag.values.FlagCodecs
import com.andyyhope.featureflag.values.FlagKey
import com.andyyhope.featureflag.values.FlagKeyPath
import com.andyyhope.featureflag.values.FlagValueBox
import com.andyyhope.featureflag.values.FlagValueType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SharedPreferencesSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences: SharedPreferences = context.getSharedPreferences("flags-under-test", Context.MODE_PRIVATE)

    private fun makeSource(): SharedPreferencesSource = SharedPreferencesSource(preferences, "local")

    /** Runs the listener callbacks the framework posted to the main thread. */
    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // Natural storage (UserDefaultsSourceTests)

    @Test
    fun storesPrimitivesAsThemselves() {
        // Anything that opens the file, such as the host's own code, reads plain values.
        val source = makeSource()
        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        source.setBox(FlagKey("flag.int"), FlagValueBox.Int(7))
        source.setBox(FlagKey("flag.float"), FlagValueBox.Float(2.5f))
        source.setBox(FlagKey("flag.string"), FlagValueBox.String("hi"))
        source.setBox(FlagKey("flag.double"), FlagValueBox.Double(1.5))

        assertEquals(true, preferences.all["flag.bool"])
        assertEquals(7L, preferences.all["flag.int"])
        assertEquals(2.5f, preferences.all["flag.float"])
        assertEquals("hi", preferences.all["flag.string"])
        // A double has no SharedPreferences type, so it is stored as JSON text.
        assertEquals("1.5", preferences.all["flag.double"])
    }

    @Test
    fun storesUrlAsJsonText() {
        makeSource().setBox(FlagKey("flag.url"), FlagValueBox.Url(URI("https://example.com")))

        assertEquals("\"https://example.com\"", preferences.all["flag.url"])
    }

    // Round trips

    @Test
    fun everySupportedTypeRoundTrips() {
        val source = makeSource()
        val cases = listOf(
            FlagValueBox.Bool(true) to FlagValueType.Bool,
            FlagValueBox.Int(-7) to FlagValueType.Int,
            FlagValueBox.Double(3.5) to FlagValueType.Double,
            FlagValueBox.Float(2.5f) to FlagValueType.Float,
            FlagValueBox.String("hello") to FlagValueType.String,
            FlagValueBox.Data(byteArrayOf(0x01, 0x02)) to FlagValueType.Data,
            FlagValueBox.Date(Instant.ofEpochSecond(1_000)) to FlagValueType.Date,
            FlagValueBox.Url(URI("https://example.com/a?b=c")) to FlagValueType.Url,
            FlagValueBox.Array(listOf(FlagValueBox.Int(1), FlagValueBox.Int(2))) to FlagValueType.Array(FlagValueType.Int),
            FlagValueBox.Dictionary(mapOf("a" to FlagValueBox.String("x"))) to FlagValueType.Dictionary(FlagValueType.String),
            FlagValueBox.Array(listOf(FlagValueBox.Array(listOf(FlagValueBox.Bool(true))))) to
                FlagValueType.Array(FlagValueType.Array(FlagValueType.Bool)),
        )

        for ((index, case) in cases.withIndex()) {
            val (box, type) = case
            val key = FlagKey("round.trip.$index")
            source.setBox(key, box)
            assertEquals(box, source.box(key, type), "failed for $type")
        }
    }

    @Test
    fun readingWithTheWrongTypeYieldsNothing() {
        val source = makeSource()
        source.setBox(FlagKey("flag.value"), FlagValueBox.String("not a number"))

        assertNull(source.box(FlagKey("flag.value"), FlagValueType.Int))
        assertNull(source.box(FlagKey("flag.value"), FlagValueType.Date))
    }

    @Test
    fun numericTypesDoNotSilentlyConvert() {
        // Truncating 3.5 into an Int flag, or reading 1 as true, would be a silent wrong answer
        // rather than a fall-through to the next source.
        val source = makeSource()

        source.setBox(FlagKey("flag.double"), FlagValueBox.Double(3.5))
        assertNull(source.box(FlagKey("flag.double"), FlagValueType.Int))
        assertNull(source.box(FlagKey("flag.double"), FlagValueType.Float))

        source.setBox(FlagKey("flag.int"), FlagValueBox.Int(1))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Bool))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Float))
        assertNull(source.box(FlagKey("flag.int"), FlagValueType.Double))

        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        assertNull(source.box(FlagKey("flag.bool"), FlagValueType.Int))
    }

    @Test
    fun aMissingKeyYieldsNothing() {
        assertNull(makeSource().box(FlagKey("absent"), FlagValueType.Bool))
    }

    @Test
    fun settingNullRemovesTheValue() {
        val source = makeSource()
        source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
        source.setBox(FlagKey("flag.bool"), null)

        assertNull(source.box(FlagKey("flag.bool"), FlagValueType.Bool))
        assertFalse(preferences.contains("flag.bool"))
    }

    @Test
    fun adoptsValuesWrittenByOtherCode() {
        // A team migrating from hand-rolled SharedPreferences flags finds them working.
        preferences.edit().putBoolean("already.here", true).commit()

        assertEquals(FlagValueBox.Bool(true), makeSource().box(FlagKey("already.here"), FlagValueType.Bool))
    }

    @Test
    fun aValueStoredWithAnotherTypeReadsAsNothing() {
        // Review Focus 2: the file belongs to the host, whose own code may use the same key.
        preferences.edit()
            .putInt("count", 3)
            .putString("limit", "lots")
            .putStringSet("tags", setOf("a"))
            .putString("launch", "not json")
            .commit()
        val source = makeSource()

        assertNull(source.box(FlagKey("count"), FlagValueType.Int), "an Integer is not the Long an int flag stores")
        assertNull(source.box(FlagKey("limit"), FlagValueType.Int))
        assertNull(source.box(FlagKey("tags"), FlagValueType.String))
        assertNull(source.box(FlagKey("launch"), FlagValueType.Date))

        // Writing the flag replaces the foreign value with one of the right type.
        source.setBox(FlagKey("count"), FlagValueBox.Int(3))
        assertEquals(3L, preferences.all["count"])
        assertEquals(FlagValueBox.Int(3), source.box(FlagKey("count"), FlagValueType.Int))
    }

    @Test
    fun readingNeverCopiesTheWholeStore() {
        val source = SharedPreferencesSource(NoGetAllPreferences(preferences))
        source.setBox(FlagKey("flag.date"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))

        assertEquals(FlagValueBox.Date(Instant.ofEpochSecond(1_000)), source.box(FlagKey("flag.date"), FlagValueType.Date))
    }

    // Naming (SourceEdgeCaseTests)

    @Test
    fun theNameIsTheSourceName() {
        assertEquals("local", makeSource().sourceName)
        assertEquals("SharedPreferences", SharedPreferencesSource(preferences).sourceName)
        assertEquals("Companion", SharedPreferencesSource.companion(context).sourceName)
        assertEquals("Overrides", SharedPreferencesSource.companion(context, "Overrides").sourceName)
    }

    @Test
    fun theCompanionSourceUsesTheWellKnownFile() {
        SharedPreferencesSource.companion(context).setBox(FlagKey("a"), FlagValueBox.Bool(true))

        val file = context.getSharedPreferences(FlagBridgeContract.PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertEquals(true, file.all["a"])
    }

    @Test
    fun emptyCollectionsRoundTrip() {
        val source = makeSource()
        source.setBox(FlagKey("empty.array"), FlagValueBox.Array(emptyList()))
        source.setBox(FlagKey("empty.dictionary"), FlagValueBox.Dictionary(emptyMap()))

        assertEquals(FlagValueBox.Array(emptyList()), source.box(FlagKey("empty.array"), FlagValueType.Array(FlagValueType.String)))
        assertEquals(
            FlagValueBox.Dictionary(emptyMap()),
            source.box(FlagKey("empty.dictionary"), FlagValueType.Dictionary(FlagValueType.Int)),
        )
    }

    @Test
    fun aPartlyWrongCollectionIsRejectedWhole() {
        preferences.edit().putString("mixed", "[1,\"two\"]").commit()

        assertNull(makeSource().box(FlagKey("mixed"), FlagValueType.Array(FlagValueType.Int)))
    }

    @Test
    fun anInvalidStoredUrlIsRejected() {
        preferences.edit().putLong("not.a.url", 42).putString("empty.url", "\"\"").commit()
        val source = makeSource()

        assertNull(source.box(FlagKey("not.a.url"), FlagValueType.Url))
        assertNull(source.box(FlagKey("empty.url"), FlagValueType.Url))
    }

    @Test
    fun aNonFiniteDoubleIsRefusedAndNothingIsWritten() {
        val source = makeSource()

        val error = assertFailsWith<FlagException.UnsupportedValue> {
            source.setBoxes(mapOf(FlagKey("fine") to FlagValueBox.Bool(true), FlagKey("nan") to FlagValueBox.Double(Double.NaN)))
        }

        assertEquals(FlagKey("nan"), error.key)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun aNonFiniteFloatIsRefusedAndNothingIsWritten() {
        val source = makeSource()

        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val error = assertFailsWith<FlagException.UnsupportedValue> {
                source.setBoxes(mapOf(FlagKey("fine") to FlagValueBox.Bool(true), FlagKey("bad") to FlagValueBox.Float(bad)))
            }

            assertEquals(FlagKey("bad"), error.key)
            assertTrue(preferences.all.isEmpty())
        }
    }

    @Test
    fun aNanStoredBySomeoneElseStillReadsBack() {
        preferences.edit().putFloat("nan", Float.NaN).commit()

        val box = makeSource().box(FlagKey("nan"), FlagValueType.Float)

        assertTrue((box as FlagValueBox.Float).value.isNaN())
    }

    // Change notification

    @Test
    fun writingEmitsAChangeForThatKey() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("flag.bool"))), awaitItem())
        }
    }

    @Test
    fun aWriteFromABackgroundThreadIsAnnouncedOnceTheMainLooperRuns() = runBlocking<Unit> {
        // The provider writes on a binder thread; the framework posts the listener callback to the main looper.
        val source = makeSource()
        val background = Executors.newSingleThreadExecutor()

        try {
            source.changes.test {
                background.submit { source.setBox(FlagKey("flag.bool"), FlagValueBox.Bool(true)) }.get(5, TimeUnit.SECONDS)
                idleMainLooper()
                assertEquals(FlagChange.Keys(setOf(FlagKey("flag.bool"))), awaitItem())
            }
        } finally {
            background.shutdownNow()
        }
    }

    @Test
    fun refreshEmitsAWholesaleChange() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.refresh()
            assertEquals(FlagChange.All, awaitItem())
        }
    }

    // Write amplification (AdversarialRegressionTests)

    @Test
    fun anIdenticalWriteAnnouncesNothing() = runBlocking<Unit> {
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
        idleMainLooper()

        source.changes.test {
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            idleMainLooper()
            expectNoEvents()

            source.setBox(FlagKey("a"), FlagValueBox.Bool(false))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("a"))), awaitItem())
        }
    }

    @Test
    fun anIdenticalWriteDoesNotTouchTheFile() {
        val counting = CountingPreferences(preferences)
        val source = SharedPreferencesSource(counting)
        source.setBox(FlagKey("a"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))

        source.setBox(FlagKey("a"), FlagValueBox.Date(Instant.ofEpochSecond(1_000)))
        source.setBox(FlagKey("absent"), null)

        assertEquals(1, counting.edits)
    }

    @Test
    fun removingSomethingAbsentAnnouncesNothing() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.setBox(FlagKey("never.set"), null)
            idleMainLooper()
            expectNoEvents()
        }
    }

    @Test
    fun aFlagChangingTypeIsStillRewritten() = runBlocking<Unit> {
        // 1 and true are different values, whatever an untyped comparison would say.
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Int(1))
        idleMainLooper()

        source.changes.test {
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("a"))), awaitItem())
        }
        assertEquals(true, preferences.all["a"])
    }

    // Two sources over one file (DarwinNotificationTests)

    @Test
    fun oneSourceSeesAnotherSourceWriteToTheSameFile() = runBlocking<Unit> {
        // Stands in for the provider writing on the companion's behalf while the host's pole reads.
        val host = SharedPreferencesSource.companion(context, "host")
        val companion = SharedPreferencesSource.companion(context, "companion")

        host.changes.test {
            companion.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(true))
            idleMainLooper()
            assertEquals(FlagChange.Keys(setOf(FlagKey("new-onboarding"))), awaitItem())
        }
        assertEquals(FlagValueBox.Bool(true), host.box(FlagKey("new-onboarding"), FlagValueType.Bool))
    }

    // Android only

    @Test
    fun setBoxesCommitsOnceAndLeavesEveryKeyWritten() = runBlocking<Unit> {
        preferences.edit().putBoolean("removed", true).commit()
        val counting = CountingPreferences(preferences)
        val source = SharedPreferencesSource(counting)

        source.changes.test {
            source.setBoxes(
                mapOf(
                    FlagKey("a") to FlagValueBox.Bool(true),
                    FlagKey("b") to FlagValueBox.Int(2),
                    FlagKey("c") to FlagValueBox.Array(listOf(FlagValueBox.String("x"))),
                    FlagKey("removed") to null,
                ),
            )
            idleMainLooper()

            val announced = mutableSetOf<FlagKey>()
            while (announced.size < 4) announced += (awaitItem() as FlagChange.Keys).keys
            assertEquals(setOf(FlagKey("a"), FlagKey("b"), FlagKey("c"), FlagKey("removed")), announced)
        }
        assertEquals(1, counting.edits)
        assertEquals(1, counting.commits)
        assertEquals(0, counting.applies)
        assertEquals(mapOf("a" to true, "b" to 2L, "c" to "[\"x\"]"), preferences.all)
    }

    @Test
    fun clearingTheFileEmitsAWholesaleChange() = runBlocking<Unit> {
        val source = makeSource()
        source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
        idleMainLooper()

        source.changes.test {
            preferences.edit().clear().commit()
            idleMainLooper()
            assertEquals(FlagChange.All, awaitItem())
        }
        assertNull(source.box(FlagKey("a"), FlagValueType.Bool))
    }

    @Test
    fun afterCloseNothingIsEmitted() = runBlocking<Unit> {
        val source = makeSource()

        source.changes.test {
            source.close()
            source.setBox(FlagKey("a"), FlagValueBox.Bool(true))
            preferences.edit().putBoolean("b", true).commit()
            idleMainLooper()
            expectNoEvents()
        }
        // Closing stops announcements only; the source still reads and writes.
        assertEquals(FlagValueBox.Bool(true), source.box(FlagKey("a"), FlagValueType.Bool))
    }

    @Test
    fun aPoleOverTheCompanionFileResolvesAnOverrideWrittenByASecondInstance() = runBlocking<Unit> {
        val pole = FlagPole(OnboardingSpec, listOf(SharedPreferencesSource.companion(context)))
        val writer = SharedPreferencesSource.companion(context)

        pole.flow(OnboardingSpec.newOnboarding).test {
            assertFalse(awaitItem())

            writer.setBox(FlagKey("new-onboarding"), FlagValueBox.Bool(true))
            idleMainLooper()

            assertTrue(awaitItem())
        }
        assertTrue(pole[OnboardingSpec.newOnboarding])
        assertEquals("Companion", pole.resolution(OnboardingSpec.newOnboarding).sourceName)
    }

    /** A one-flag container, written by hand: the Android modules do not run KSP. */
    private object OnboardingSpec : FlagContainerSpec<Any> {
        val newOnboarding: FlagRef<Boolean> =
            FlagRef(FlagKeyPath(listOf("newOnboarding")), "newOnboarding", "New onboarding", null, FlagCodecs.boolean) { false }

        override val flagContainerDescription: String? = null
        override val flagDescriptors: List<FlagSchemaNode> = listOf(
            FlagSchemaNode.Flag(FlagDescriptor.of("newOnboarding", "New onboarding", null, FlagCodecs.boolean, false)),
        )
        override val flagDefaults: Any = Any()

        override fun createFlags(lookup: FlagLookup, prefix: FlagKeyPath): Any = Any()
    }

    /** Counts editors and how each one is finished. */
    private class CountingPreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var edits = 0
        var commits = 0
        var applies = 0

        override fun edit(): SharedPreferences.Editor {
            edits++
            return CountingEditor(real.edit())
        }

        private inner class CountingEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commits++
                return editor.commit()
            }

            override fun apply() {
                applies++
                editor.apply()
            }
        }
    }

    /** Fails the test if anything copies the whole file. */
    private class NoGetAllPreferences(real: SharedPreferences) : SharedPreferences by real {
        override fun getAll(): MutableMap<String, *> = throw AssertionError("box() must not copy the whole store")
    }
}

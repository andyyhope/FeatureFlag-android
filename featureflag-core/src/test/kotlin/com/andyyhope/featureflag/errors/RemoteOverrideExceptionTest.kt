package com.andyyhope.featureflag.errors

import com.andyyhope.featureflag.errors.RemoteOverrideProblem.Kind
import com.andyyhope.featureflag.values.FlagKey
import org.junit.Test
import kotlin.test.assertEquals

class RemoteOverrideExceptionTest {

    private val closing =
        "A remote payload is all or nothing, so one bad value leaves every other flag reading whatever sits below this source."

    @Test
    fun aMalformedPayloadSaysWhy() {
        assertEquals(
            "The remote payload could not be read: not valid JSON.",
            RemoteOverrideException.Malformed("not valid JSON").message,
        )
    }

    @Test
    fun aRejectionWithNoProblemsIsStillASentence() {
        assertEquals("Nothing was applied, and no problem was recorded.", RemoteOverrideException.Rejected(emptyList()).message)
    }

    @Test
    fun oneProblemIsSingular() {
        val problem =
            RemoteOverrideProblem(FlagKey("new-onboarding"), "featureToggles.onboarding", Kind.TypeMismatch, "bool", "\"yes\"")
        assertEquals(
            "Nothing was applied. 1 problem:\n" +
                "  • 'featureToggles.onboarding' → new-onboarding: expected bool, got \"yes\"\n" +
                closing,
            RemoteOverrideException.Rejected(listOf(problem)).message,
        )
    }

    @Test
    fun everyKindFallsBackToWordsWhenItHasNoValues() {
        val problems = listOf(
            RemoteOverrideProblem(FlagKey("a"), "x.a", Kind.TypeMismatch),
            RemoteOverrideProblem(FlagKey("b"), "x.b", Kind.UnknownCase),
            RemoteOverrideProblem(FlagKey("new-onbaording"), "new-onbaording", Kind.UnknownKey, found = "true"),
        )
        assertEquals(
            "Nothing was applied. 3 problems:\n" +
                "  • 'x.a' → a: expected another type\n" +
                "  • 'x.b' → b: that value is not one of this flag's cases\n" +
                "  • new-onbaording: no flag has this key, so the mapper that produced it is wrong\n" +
                closing,
            RemoteOverrideException.Rejected(problems).message,
        )
    }

    @Test
    fun anUnknownCaseNamesTheCasesThatWouldHaveWorked() {
        assertEquals(
            "'config.tier' → checkout-tier: \"gold\" is not one of free, pro",
            RemoteOverrideProblem(FlagKey("checkout-tier"), "config.tier", Kind.UnknownCase, "free, pro", "\"gold\"").toString(),
        )
    }
}

// KOTRAIL_CONFIG: rules.test.naming=true, test.annotations=custom.Scenario,custom.Case, test.naming.minWords=2

package custom

import org.junit.jupiter.api.Test

annotation class Scenario

annotation class Case

class CheckoutSpec {
    // Not reported: two words meet the configured minimum.
    @Scenario
    fun `rejects duplicates`() {
    }

    @Case
    fun `charges once`() {
    }

    // Reported: a single word is still below the minimum.
    @Scenario
    fun <!KOTRAIL_TEST_NAME_NOT_DESCRIPTIVE!>charges<!>() {
    }

    // Not reported: the configured list replaces the default one, so @Test no longer marks a test.
    @Test
    fun ignoredByConfiguration() {
    }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, functionDeclaration */

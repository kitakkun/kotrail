import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

class OrderTest {
    // Not reported: a sentence that says what the test verifies.
    @Test
    fun `returns an empty list when nothing matches`() {
    }

    @RepeatedTest(3)
    fun `keeps the order stable across repeated runs`() {
    }

    // Reported: an identifier only hints at what is verified.
    @Test
    fun <!TEST_NAME_NOT_DESCRIPTIVE!>returnsEmptyList<!>() {
    }

    // Reported: backticks alone are not a description.
    @Test
    fun <!TEST_NAME_NOT_DESCRIPTIVE!>`fails`<!>() {
    }

    // Reported: two words are below the default minimum of three.
    @Test
    fun <!TEST_NAME_NOT_DESCRIPTIVE!>`rejects duplicates`<!>() {
    }

    // Not reported: no test annotation, so the rule does not apply.
    fun buildOrder() {
    }
}

// Not reported: production code is never named this way, and carries no test annotation.
fun computeTotal(prices: List<Int>): Int = prices.sum()

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral */

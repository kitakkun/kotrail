// KOTRAIL_CONFIG: rules.test.naming=on, rules.test.naming.style=identifier

import org.junit.jupiter.api.Test

// Android instrumented tests run on a device, which rejects method names with spaces, so a
// compilation for that target asks for the opposite of the default style.
class DeviceTest {
    // Not reported: a plain identifier.
    @Test
    fun opensTheDrawerOnSwipe() {
    }

    // Reported: the name would not survive on the device.
    @Test
    fun <!KOTRAIL_TEST_NAME_NOT_IDENTIFIER!>`opens the drawer on swipe`<!>() {
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration */

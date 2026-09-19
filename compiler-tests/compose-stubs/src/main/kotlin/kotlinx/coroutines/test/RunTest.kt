// Stand-in for kotlinx-coroutines-test's runTest: the plugin recognizes it by name as the scope
// where delay() runs on virtual time.
package kotlinx.coroutines.test

fun runTest(testBody: suspend () -> Unit) {
}

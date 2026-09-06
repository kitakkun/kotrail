package com.kitakkun.kotrail.test

import com.kitakkun.kotrail.test.runners.AbstractJvmBoxTest
import com.kitakkun.kotrail.test.runners.AbstractJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5

/** Walks `compiler-tests/testData` and emits one JUnit 5 class per runner into `test-gen/`. */
fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testDataRoot = "compiler-tests/testData", testsRoot = "compiler-tests/test-gen") {
            testClass<AbstractJvmDiagnosticTest> { model("diagnostics") }
            testClass<AbstractJvmBoxTest> { model("box") }
        }
    }
}

package com.kitakkun.kotrail.test.compat

import org.jetbrains.kotlin.test.runners.codegen.AbstractFirBlackBoxCodegenTestBase

/**
 * The framework's JVM box-test base class, taking the FIR parser to use.
 *
 * Since 2.4.20: `AbstractFirBlackBoxCodegenTestBase` is folded into
 * `AbstractJvmBlackBoxCodegenTestBase`, which takes the parser directly; before, the latter was
 * a generic class with abstract facade properties.
 */
typealias BoxTestBase = AbstractFirBlackBoxCodegenTestBase

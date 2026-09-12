@file:OptIn(ExperimentalCompilerApi::class)

package com.kitakkun.kotrail.test.services

import com.kitakkun.kotrail.KotrailCommandLineProcessor
import com.kitakkun.kotrail.KotrailComponentRegistrar
import com.kitakkun.kotrail.KotrailConfigurationKeys
import com.kitakkun.kotrail.KotrailRule
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoot
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.directives.model.SimpleDirectivesContainer
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
import org.jetbrains.kotlin.test.services.TestServices
import java.io.File

fun TestConfigurationBuilder.configureKotrail() {
    useDirectives(KotrailTestDirectives)
    useConfigurators(::KotrailExtensionRegistrarConfigurator, ::TestDataClasspathConfigurator)
    useCustomRuntimeClasspathProviders(::TestDataRuntimeClasspathProvider)
}

/** Test-data directives understood by the Kotrail runners. */
object KotrailTestDirectives : SimpleDirectivesContainer() {
    /**
     * Plugin options for the module, as `key=value` pairs, e.g.
     * `// KOTRAIL_CONFIG: rules.compose.nesting=true, compose.nesting.maxDepth=2`.
     * Keys are the same ones the command-line processor accepts.
     *
     * Rules are all off before this is applied, so a fixture enables what it exercises.
     *
     * Declared multi-line so that the framework hands over the raw value: its default splitting
     * on whitespace would cut a value such as `exclude=name(*Legacy*) || class(Gen*)` into pieces.
     * Entries are separated here instead, on a comma followed by the next `key=`.
     */
    val KOTRAIL_CONFIG by stringDirective(
        description = "Kotrail plugin options as key=value pairs, comma separated",
        multiLine = true,
    )
}

/** Applies `KOTRAIL_CONFIG` directives and registers the plugin's FIR and IR extensions inside the test compiler. */
internal class KotrailExtensionRegistrarConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val registrar = KotrailComponentRegistrar()
    private val commandLineProcessor = KotrailCommandLineProcessor()

    override val directiveContainers: List<DirectivesContainer> = listOf(KotrailTestDirectives)

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        // Every rule starts off and a fixture opts in to the ones it exercises. Fixtures then say
        // in their own directive what they are about, and a newly added rule cannot start
        // reporting across fixtures that were written for something else.
        for (rule in KotrailRule.switchable) {
            configuration.put(KotrailConfigurationKeys.switchKey(rule), false)
        }

        // Entries are separated by a comma that is followed by the next `key=`; commas inside a
        // value (`preferFunctionReferences.forms=topLevel,bound`) stay with the value.
        val entrySeparator = Regex(""",\s*(?=[A-Za-z][A-Za-z0-9.]*=)""")
        val options = module.directives[KotrailTestDirectives.KOTRAIL_CONFIG]
            .flatMap { it.split(entrySeparator) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (entry in options) {
            val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 }
                ?: error("KOTRAIL_CONFIG entries must be key=value, got '$entry'")
            val option = commandLineProcessor.pluginOptions.firstOrNull { it.optionName == key.trim() }
                ?: error("Unknown Kotrail option '$key' in KOTRAIL_CONFIG")
            commandLineProcessor.processOption(option, value.trim(), configuration)
        }
    }

    override fun CompilerPluginRegistrar.ExtensionStorage.registerCompilerExtensions(
        module: TestModule,
        configuration: CompilerConfiguration,
    ) {
        with(registrar) { registerExtensions(configuration) }
    }
}

/** Puts the annotation artifact and the Compose stubs on the compile classpath of test data. */
internal class TestDataClasspathConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        testDataClasspath().forEach { configuration.addJvmClasspathRoot(it) }
    }
}

/** The same JARs, for running `box()` in codegen tests. */
internal class TestDataRuntimeClasspathProvider(testServices: TestServices) : RuntimeClasspathProvider(testServices) {
    override fun runtimeClassPaths(module: TestModule): List<File> = testDataClasspath()
}

private fun testDataClasspath(): List<File> {
    val classpath = System.getProperty("kotrail.test.classpath")
        ?: error("system property kotrail.test.classpath is not set; check tasks.test in compiler-tests/build.gradle.kts")
    return classpath.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
}

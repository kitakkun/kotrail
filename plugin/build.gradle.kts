import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

val kotlinCompilerVersion: String by rootProject.extra
val kotlinCompatFamily: String by rootProject.extra

kotlin {
    jvmToolchain(21)
    // Version-specific shims live under plugin/src/<family>/kotlin. Exactly one family is
    // compiled into the JAR; shared code only ever references com.kitakkun.kotrail.compat.
    sourceSets.main {
        kotlin.srcDir("src/$kotlinCompatFamily/kotlin")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    // The configuration loader is unit-tested against a CompilerConfiguration.
    testImplementation(libs.kotlin.compiler.embeddable)
    // Unit tests for the compiler-independent parts: the precondition and exclusion languages.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The unit tests check that the committed JSON Schema matches the one the schema table
    // generates, so a rule or setting added without regenerating it fails here.
    systemProperty("kotrail.schema.file", rootDir.resolve("docs/public/kotrail.schema.json").path)
}

// Writes the JSON Schema of kotrail.yaml, for editor completion, next to the docs site's assets.
val generateConfigSchema by tasks.registering(JavaExec::class) {
    // The compiler is compileOnly for the plugin; the generator needs it at run time for the rule table.
    classpath = sourceSets.main.get().runtimeClasspath + configurations.compileClasspath.get()
    mainClass.set("com.kitakkun.kotrail.config.GenerateConfigSchemaKt")
    args(rootDir.resolve("docs/public/kotrail.schema.json").path)
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    // The configuration loader's unit test builds a CompilerConfiguration, whose constructor is
    // opt-in from Kotlin 2.4.0 on; the marker does not exist on 2.3.x.
    if (kotlinCompatFamily != "k2321") {
        compilerOptions.freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    // Checker `check()` overrides use context parameters. They are enabled by default from
    // Kotlin 2.4.0 on; 2.3.x still gates them behind the flag, and passing it on 2.4.0 would
    // only produce a "redundant flag" warning.
    if (kotlinCompatFamily == "k2321") {
        compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// The JAR links against one Kotlin compiler, so the Kotlin version leads the artifact version:
// kotrail-compiler-plugin:2.4.0-0.1.0. The Gradle plugin composes the same coordinate from the
// Kotlin version a consumer applies. See docs/supported-kotlin-versions.md. Shared POM metadata,
// Maven Central, and signing come from the root build script.
mavenPublishing {
    coordinates(artifactId = "kotrail-compiler-plugin", version = "$kotlinCompilerVersion-${project.version}")
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    pom {
        name.set("Kotrail compiler plugin")
        description.set(
            "Compiler checker rules that keep Kotlin code durable when developing with AI. " +
                "Built for Kotlin $kotlinCompilerVersion.",
        )
    }
}

publishing {
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

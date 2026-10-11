package com.kitakkun.kotrail.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ConsumerRulesTest {
    @Test
    fun `the rules only silence the missing Kotrail classes`() {
        val rules = ConsumerRules.TEXT.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf("-dontwarn com.kitakkun.kotrail.**"), rules)
    }

    @Test
    fun `the rules are where R8 reads the rules of a dependency JAR`() {
        assertTrue(ConsumerRules.RESOURCE_PATH.startsWith("META-INF/proguard/"), ConsumerRules.RESOURCE_PATH)
        assertTrue(ConsumerRules.RESOURCE_PATH.endsWith(".pro"), ConsumerRules.RESOURCE_PATH)
    }

    class FakeResourcesPackaging {
        val excludes: MutableSet<String> = mutableSetOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    class FakePackaging {
        val resources = FakeResourcesPackaging()
    }

    class FakeApplicationExtension {
        val packaging = FakePackaging()
    }

    class FakePackagingWithoutResources

    class FakeApplicationExtensionWithoutResources {
        val packaging = FakePackagingWithoutResources()
    }

    @Test
    fun `an application excludes the facts files of older libraries from its packaging and keeps its own excludes`() {
        val android = FakeApplicationExtension()
        assertNull(FactsFilePackaging.exclude(android))
        assertEquals(setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/kotrail/**"), android.packaging.resources.excludes)
    }

    @Test
    fun `an application DSL without the packaging members names the one it could not find`() {
        assertEquals("packaging.resources", FactsFilePackaging.exclude(FakeApplicationExtensionWithoutResources()))
        assertEquals("packaging", FactsFilePackaging.exclude(Any()))
    }

    @Test
    fun `the multiplatform Android DSL publishes the rules file as a consumer keep rule`() {
        val android = FakeKmpAndroid()
        val rulesFile = File("kotrail.pro")
        assertNull(addConsumerKeepRules(android, rulesFile))
        assertTrue(android.optimization.consumerKeepRules.publish)
        assertEquals(listOf<Any>(rulesFile), android.optimization.consumerKeepRules.files)
    }

    @Test
    fun `a multiplatform Android DSL without the members names the one that is missing`() {
        assertEquals("optimization", addConsumerKeepRules(Any(), File("kotrail.pro")))
        assertEquals("optimization.consumerKeepRules", addConsumerKeepRules(FakeKmpAndroidWithoutRules(), File("kotrail.pro")))
    }
}

// These mirror the member names of AGP's KotlinMultiplatformAndroidLibraryExtension,
// KmpOptimization and ConsumerKeepRules, which addConsumerKeepRules finds by reflection.
class FakeKmpAndroid {
    val optimization = FakeOptimization()
}

class FakeOptimization {
    val consumerKeepRules = FakeConsumerKeepRules()
}

class FakeConsumerKeepRules {
    var publish: Boolean = false
    val files: MutableList<Any> = mutableListOf()

    fun file(file: Any) {
        files += file
    }
}

class FakeKmpAndroidWithoutRules {
    val optimization = Any()
}

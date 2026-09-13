package com.kitakkun.kotrail.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinVersionSupportTest {
    @Test
    fun `a published Kotlin version passes`() {
        checkKotlinVersionIsSupported("2.4.0", kotrailVersion = "0.1.0", supported = SUPPORTED)
    }

    @Test
    fun `an unpublished Kotlin version is refused with the versions that exist`() {
        val failure = assertThrows(GradleException::class.java) {
            checkKotlinVersionIsSupported("2.2.20", kotrailVersion = "0.1.0", supported = SUPPORTED)
        }
        val message = failure.message.orEmpty()
        assertTrue(message.contains("Kotlin 2.2.20"), message)
        assertTrue(message.contains("2.3.21, 2.4.0, 2.4.10, 2.4.20"), message)
        assertTrue(message.contains("supported-kotlin-versions.md"), message)
    }

    @Test
    fun `the versions the plugin was built with are the ones it accepts`() {
        assertTrue(SUPPORTED_KOTLIN_VERSIONS.isNotEmpty())
        SUPPORTED_KOTLIN_VERSIONS.forEach { checkKotlinVersionIsSupported(it) }
    }

    private companion object {
        val SUPPORTED = listOf("2.3.21", "2.4.0", "2.4.10", "2.4.20")
    }
}

package com.kitakkun.kotrail.config

import com.kitakkun.kotrail.KotrailRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ConfigSchemaTest {
    @Test
    fun `every rule and every setting is in the generated schema`() {
        val schema = ConfigSchema.jsonSchema()
        for (rule in KotrailRule.entries) assertTrue("\"${rule.key}\":" in schema, rule.key)
        for ((rule, settings) in ConfigSchema.SETTINGS) for (setting in settings) assertTrue("\"${setting.name}\":" in schema, "${rule.key}.${setting.name}")
        assertTrue("\"maxDepth\": {\"type\": \"integer\"" in schema)
        assertTrue("\"enum\": [\"error\", \"warning\"]" in schema)
    }

    @Test
    fun `the committed schema is the generated one`() {
        val path = System.getProperty("kotrail.schema.file") ?: return
        val committed = File(path).takeIf { it.isFile }?.readText() ?: ""
        assertEquals(ConfigSchema.jsonSchema(), committed, "docs/public/kotrail.schema.json is stale; run ./gradlew :compiler-plugin:generateConfigSchema")
    }
}

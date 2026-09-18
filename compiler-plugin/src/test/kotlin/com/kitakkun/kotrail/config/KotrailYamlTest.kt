package com.kitakkun.kotrail.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotrailYamlTest {
    @Test
    fun `block mappings, sequences, flow collections, and scalars in every spelling`() {
        val root = KotrailYaml.parse(
            "kotrail.yaml",
            """
            # A comment, and a document marker.
            ---
            note: Conventions live in CONTRIBUTING.md.   # trailing comment
            exclude: "package(com.acme.generated.*) || file(*Generated.kt)"
            test:
              annotations:
                - kotlin.test.Test
                - 'org.junit.Test'
            rules:
              functionLength:
                maxLines: 60
                exclude: name(main)
              forbiddenCall:
                functions: [kotlin.io.println, java.lang.Thread.sleep]
                calls:
                  globalScope: fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)
              compose.compositionLocals:
                known:
                  com.acme.ui.AppTheme:
                    provides: { content: [com.acme.ui.LocalPalette] }
              preferValueClass: off
              noRedundantElse: ~
            """.trimIndent(),
        )
        assertEquals("Conventions live in CONTRIBUTING.md.", (root["note"] as ConfigNode.Scalar).value)
        assertEquals("package(com.acme.generated.*) || file(*Generated.kt)", (root["exclude"] as ConfigNode.Scalar).value)
        val annotations = ((root["test"] as ConfigNode.Mapping)["annotations"] as ConfigNode.Sequence).items.map { (it as ConfigNode.Scalar).value }
        assertEquals(listOf("kotlin.test.Test", "org.junit.Test"), annotations)
        val rules = root["rules"] as ConfigNode.Mapping
        val functionLength = rules["functionLength"] as ConfigNode.Mapping
        assertEquals("60", (functionLength["maxLines"] as ConfigNode.Scalar).value)
        assertEquals("name(main)", (functionLength["exclude"] as ConfigNode.Scalar).value)
        val forbidden = rules["forbiddenCall"] as ConfigNode.Mapping
        assertEquals(2, (forbidden["functions"] as ConfigNode.Sequence).items.size)
        assertEquals(
            "fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)",
            ((forbidden["calls"] as ConfigNode.Mapping)["globalScope"] as ConfigNode.Scalar).value,
        )
        val theme = ((rules["compose.compositionLocals"] as ConfigNode.Mapping)["known"] as ConfigNode.Mapping)["com.acme.ui.AppTheme"] as ConfigNode.Mapping
        val provides = theme["provides"] as ConfigNode.Mapping
        assertEquals("com.acme.ui.LocalPalette", ((provides["content"] as ConfigNode.Sequence).items.single() as ConfigNode.Scalar).value)
        assertEquals("off", (rules["preferValueClass"] as ConfigNode.Scalar).value)
        assertTrue(rules["noRedundantElse"] is ConfigNode.Null)
        assertEquals("kotrail.yaml:11:15", functionLength["maxLines"]!!.at)
        assertEquals("kotrail.yaml:11:5", functionLength.keyAt("maxLines"))
    }

    @Test
    fun `scalars stay strings and quoting protects YAML punctuation`() {
        val root = KotrailYaml.parse(
            "k.yaml",
            """
            rules:
              noNotNullAssertion:
                enabled: no
                note: "Rule: see ADR-014 # not a comment"
                exclude: '!annotated(com.acme.Reviewed)'
            """.trimIndent(),
        )
        val rule = (root["rules"] as ConfigNode.Mapping)["noNotNullAssertion"] as ConfigNode.Mapping
        assertEquals("no", (rule["enabled"] as ConfigNode.Scalar).value)
        assertEquals("Rule: see ADR-014 # not a comment", (rule["note"] as ConfigNode.Scalar).value)
        assertEquals("!annotated(com.acme.Reviewed)", (rule["exclude"] as ConfigNode.Scalar).value)
    }

    @Test
    fun `what is not supported is rejected by name with a position`() {
        fun failure(text: String): String = assertThrows(ConfigException::class.java) { KotrailYaml.parse("k.yaml", text) }.message.orEmpty()
        assertTrue(failure("rules:\n  a: 1\n  a: 2").contains("already set on line 2"))
        assertTrue(failure("rules:\n\tx: 1").contains("tabs"))
        assertTrue(failure("exclude: !annotated(x)").contains("tag"))
        assertTrue(failure("base: &b 1").contains("anchors"))
        assertTrue(failure("rules:\n  <<: *b").contains("merge"))
        assertTrue(failure("a: 1\n---\nb: 2").contains("one YAML document"))
        assertTrue(failure("rules:\n  x:1").contains("space after ':'"))
        assertTrue(failure("- a\n- b").contains("mapping"))
        assertTrue(failure("rules:\n    a: 1\n  b: 2").startsWith("k.yaml:3:3"))
    }

    @Test
    fun `block scalars keep or fold their lines and sequences may hold mappings`() {
        val root = KotrailYaml.parse(
            "k.yaml",
            """
            note: >
              Conventions live in CONTRIBUTING.md;
              ask in the channel before disabling a rule.
            rules:
              functionLength:
                note: |-
                  Line one.
                  Line two.
            items:
              - name: first
                value: 1
              - name: second
                value: 2
              - plain
            """.trimIndent(),
        )
        assertEquals("Conventions live in CONTRIBUTING.md; ask in the channel before disabling a rule.\n", (root["note"] as ConfigNode.Scalar).value)
        val functionLength = (root["rules"] as ConfigNode.Mapping)["functionLength"] as ConfigNode.Mapping
        assertEquals("Line one.\nLine two.", (functionLength["note"] as ConfigNode.Scalar).value)
        val items = (root["items"] as ConfigNode.Sequence).items
        assertEquals(3, items.size)
        assertEquals("first", ((items[0] as ConfigNode.Mapping)["name"] as ConfigNode.Scalar).value)
        assertEquals("2", ((items[1] as ConfigNode.Mapping)["value"] as ConfigNode.Scalar).value)
        assertEquals("plain", (items[2] as ConfigNode.Scalar).value)
    }

    @Test
    fun `layers merge key by key and a null takes a key away`() {
        val base = KotrailYaml.parse("a.yaml", "rules:\n  functionLength:\n    maxLines: 60\n    exclude: name(main)\n  noRedundantElse:\n    enabled: false\n")
        val over = KotrailYaml.parse("b.yaml", "rules:\n  functionLength:\n    maxLines: 120\n  noRedundantElse: ~\n")
        val merged = ConfigTree.merge(base, over) as ConfigNode.Mapping
        val rules = merged["rules"] as ConfigNode.Mapping
        val functionLength = rules["functionLength"] as ConfigNode.Mapping
        assertEquals("120", (functionLength["maxLines"] as ConfigNode.Scalar).value)
        assertEquals("name(main)", (functionLength["exclude"] as ConfigNode.Scalar).value)
        assertTrue("noRedundantElse" !in rules.entries)
    }
}

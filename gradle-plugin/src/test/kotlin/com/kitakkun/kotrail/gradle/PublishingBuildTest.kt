package com.kitakkun.kotrail.gradle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishingBuildTest {
    private fun publishesByDefault(name: String): Boolean = PublishingBuild.isPublishingName(name, PublishingBuild.DEFAULT_TASK_NAMES)

    @Test
    fun `the publish lifecycle task and the repository publications count by default`() {
        assertTrue(publishesByDefault("publish"))
        assertTrue(publishesByDefault("publishAllPublicationsToTestRepository"))
        assertTrue(publishesByDefault("publishMavenPublicationToSonatypeRepository"))
        assertTrue(publishesByDefault("publishIvyPublicationToIvyRepository"))
    }

    @Test
    fun `publishing to the local Maven repository and other tasks do not count by default`() {
        assertFalse(publishesByDefault("publishToMavenLocal"))
        assertFalse(publishesByDefault("publishMavenPublicationToMavenLocal"))
        assertFalse(publishesByDefault("publishPlugins"))
        assertFalse(publishesByDefault("republish"))
        assertFalse(publishesByDefault("assemble"))
    }

    @Test
    fun `a glob matches the whole name, with a star for any run of characters and a question mark for one`() {
        assertTrue(PublishingBuild.isPublishingName("publishToMavenLocal", listOf("publish*")))
        assertTrue(PublishingBuild.isPublishingName("uploadToArtifactory", listOf("publish*Repository", "uploadToArtifactory")))
        assertTrue(PublishingBuild.isPublishingName("upload1", listOf("upload?")))
        assertFalse(PublishingBuild.isPublishingName("upload12", listOf("upload?")))
        assertFalse(PublishingBuild.isPublishingName("uploadToArtifactoryLater", listOf("uploadToArtifactory")))
        assertFalse(PublishingBuild.isPublishingName("aXb", listOf("a.b")))
    }

    @Test
    fun `an empty list counts no name`() {
        assertFalse(PublishingBuild.isPublishingName("publish", emptyList()))
        assertFalse(PublishingBuild.isPublishingName("publishAllPublicationsToTestRepository", emptyList()))
    }
}

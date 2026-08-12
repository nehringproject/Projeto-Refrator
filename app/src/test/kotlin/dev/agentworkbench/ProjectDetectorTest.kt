package dev.agentworkbench

import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDetectorTest {
    @Test
    fun detectsMultipleBuildSystemsWithoutExecutingThem() {
        val root = Files.createTempDirectory("awb-project-detector")
        try {
            root.resolve("settings.gradle.kts").createFile()
            root.resolve("package.json").createFile()

            val profiles = ProjectDetector.detect(root.toFile())

            assertEquals(listOf("gradle", "node"), profiles.map { it.kind })
            assertTrue(profiles.all { it.root == "." })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

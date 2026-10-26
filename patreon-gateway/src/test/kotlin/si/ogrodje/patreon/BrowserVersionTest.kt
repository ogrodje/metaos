package si.ogrodje.patreon

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Playwright's client (the gateway's, build.gradle.kts) and its browser server (the browser's container, from Microsoft's
 * image) only talk to each other at the same version, so the pod's has to follow the build's.
 */
class BrowserVersionTest {
    @Test
    fun `the browser container's Playwright is the gateway's`() {
        val build = Files.readString(Path.of("build.gradle.kts"))
        val version = Regex("val playwrightVersion = \"([0-9.]+)\"").find(build)!!.groupValues[1]
        val pod = Files.readString(Path.of("../k8s/base/deployment-patreon-gateway.yaml"))
        assertEquals(version, Regex("mcr.microsoft.com/playwright:v([0-9.]+)-").find(pod)!!.groupValues[1], "the browser's image")
        assertEquals(version, Regex("playwright@([0-9.]+)").find(pod)!!.groupValues[1], "the browser's server")
    }
}

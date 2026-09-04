package dev.harnessprotocol.legacy

import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessesTest {
    @Test
    fun `default factories expose only the common harness port`() {
        Harnesses.create("codex").use { harness ->
            assertEquals(ProviderId("codex"), harness.provider)
        }
        Harnesses.create(ProviderId("gemini-cli")).use { harness ->
            assertEquals(ProviderId("gemini-cli"), harness.provider)
        }
    }
}

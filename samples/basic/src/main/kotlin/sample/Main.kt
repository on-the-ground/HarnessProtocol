package sample

import dev.harnessprotocol.legacy.Harnesses

fun main() {
    Harnesses.create(System.getProperty("agent.provider", "codex")).use { harness ->
        println("Using ${harness.provider.value} through AgentHarness")
    }
}

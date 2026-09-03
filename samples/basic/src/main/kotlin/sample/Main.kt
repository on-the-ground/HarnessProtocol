package sample

import dev.harnessprotocol.Harnesses

fun main() {
    Harnesses.create(System.getProperty("agent.provider", "codex")).use { harness ->
        println("Using ${harness.provider.value} through AgentHarness")
    }
}

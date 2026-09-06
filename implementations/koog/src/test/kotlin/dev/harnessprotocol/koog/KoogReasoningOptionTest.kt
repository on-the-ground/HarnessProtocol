package dev.harnessprotocol.koog

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import dev.harnessprotocol.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KoogReasoningOptionTest {
    @Test
    fun `configured Koog adapter rejects reasoning selection before execution`() {
        runBlocking {
            val harness = KoogHarness(
                executorFactory = { error("executor must not be created for a rejected requirement") },
                model = OpenAIModels.Chat.GPT4o,
                tools = ToolRegistry {},
            )
            harness.use {
                assertIs<Support.Unsupported>(harness.support[Capability.REASONING_OPTION_SELECTION])
                val session = harness.createSession(SessionSpec())
                val request = TaskRequest(
                    TaskInput.Text("reason"),
                    TaskRequirements(reasoning = ReasoningOptionRequirement.Selected(
                        OpenAIModels.Chat.GPT4o.id,
                        ReasoningOptionId("high"),
                    )),
                )
                assertEquals(CompatibilityStatus.INCOMPATIBLE, session.validate(request).status)
                assertFailsWith<IncompatibleRequirementException> { session.startTask(request) }
            }
        }
    }
}

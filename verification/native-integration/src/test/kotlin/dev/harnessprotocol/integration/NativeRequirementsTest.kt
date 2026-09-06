package dev.harnessprotocol.integration

import dev.harnessprotocol.*
import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

abstract class NativeRequirementsTest : HarnessRequirementsConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun requirementFixture(): RuntimeRequirementsFixture = NativeRequirementsFixture(factory, directory)
}

class CodexNativeRequirementsTest : NativeRequirementsTest() { override val factory = CodexNativeFactory }
class GeminiNativeRequirementsTest : NativeRequirementsTest() { override val factory = GeminiNativeFactory }
class KoogNativeRequirementsTest : NativeRequirementsTest() { override val factory = KoogNativeFactory }

/** Expectations describe the pinned runtime configuration, never adapter.support/validate output. */
private class NativeRequirementsFixture(
    private val factory: NativeHarnessFactory,
    parent: Path,
) : RuntimeRequirementsFixture {
    override val provider = factory.provider
    override val observation = ModelBoundary()
    private val root = Files.createTempDirectory(parent, "requirement-")
    private val harnesses = mutableListOf<AgentHarness>()
    private val codex = factory === CodexNativeFactory
    private val koog = factory === KoogNativeFactory
    private val retainedId = when (factory) {
        CodexNativeFactory -> "client-storage"
        GeminiNativeFactory -> "sdk-history"
        else -> "graph-tools"
    }
    private val liveId = if (codex) "client-ephemeral" else "sdk-live"

    override fun profiles(): List<FixtureProfile> = buildList {
        add(profile(retainedId, persistent = !koog, comprehensive = true))
        if (!koog) add(profile(liveId, persistent = false, comprehensive = false))
    }

    override fun createHarness(profileId: String): AgentHarness {
        require(profiles().any { it.id == profileId }) { "Unknown profile: $profileId" }
        return factory.create(observation, Files.createDirectories(root.resolve(profileId)),
            persistent = !koog && profileId == retainedId).also { harnesses += it }
    }

    private fun profile(id: String, persistent: Boolean, comprehensive: Boolean): FixtureProfile {
        val support = SupportReport(mapOf(
            Capability.CALLER_APPROVAL to if (codex) Support.Supported else Support.Unsupported("No caller approval channel configured"),
            Capability.QUESTIONS to Support.Unsupported("No typed question channel configured"),
            Capability.PERSISTENCE to if (persistent) Support.Conditional(SupportScope.SESSION, "Reopen in this application process; no concurrent access") else Support.Unsupported("No storage namespace or durable graph storage configured"),
            Capability.CONTEXT_RETENTION to if (codex || koog) Support.Supported else Support.Unsupported("No provider retention control configured"),
            Capability.USER_HISTORY_VISIBILITY to when {
                koog -> Support.Supported
                codex -> Support.Unknown("Ephemeral materialization is observable; account history visibility is not")
                else -> Support.Unsupported("No user-history visibility control configured")
            },
            Capability.WORKSPACE to if (koog) Support.Unsupported("No workspace loader configured") else Support.Supported,
            Capability.EXECUTION_CONSTRAINT to if (codex) Support.Conditional(SupportScope.SESSION, "Restrictive constraints require DenyAll; network policy requires workspace-write") else Support.Unsupported("No sandbox enforcement configured"),
            Capability.STRUCTURED_OUTPUT to Support.Unsupported("No schema enforcement configured"),
            Capability.DIAGNOSTICS to Support.Supported,
            Capability.REASONING_OPTION_SELECTION to if (codex) {
                Support.Conditional(SupportScope.SESSION, "Available options depend on the selected Codex model")
            } else {
                Support.Unsupported("No reasoning option selection configured")
            },
        ))
        val base = factory.spec()
        fun sessionCase(
            name: String,
            requirements: SessionRequirements = SessionRequirements(),
            status: CompatibilityStatus = CompatibilityStatus.COMPATIBLE,
            capability: Capability? = null,
        ) =
            RequirementCase(name, base.copy(requirements = requirements), TaskRequest(TaskInput.Text("requirement-$id-$name")),
                status,
                CompatibilityStatus.COMPATIBLE, capability = capability)
        val cases = buildList {
            add(sessionCase("plain-text"))
            add(sessionCase("persistent-context", SessionRequirements(persistence = PersistenceRequirement.Required()),
                if (persistent) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.PERSISTENCE))
            if (comprehensive) {
                add(sessionCase("ephemeral-retention", SessionRequirements(retention = ContextRetentionRequirement.Ephemeral),
                    if (codex || koog) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.CONTEXT_RETENTION))
                add(sessionCase("hidden-history", SessionRequirements(historyVisibility = UserHistoryVisibilityRequirement.Hidden),
                    when { koog -> CompatibilityStatus.COMPATIBLE; codex -> CompatibilityStatus.UNCONFIRMED; else -> CompatibilityStatus.INCOMPATIBLE },
                    Capability.USER_HISTORY_VISIBILITY))
                add(sessionCase("process-restart", SessionRequirements(persistence = PersistenceRequirement.Required(acrossProcessRestart = true)), CompatibilityStatus.INCOMPATIBLE, Capability.PERSISTENCE))
                add(sessionCase("concurrent-storage", SessionRequirements(persistence = PersistenceRequirement.Required(concurrentAccess = true)), CompatibilityStatus.INCOMPATIBLE, Capability.PERSISTENCE))
                add(sessionCase("caller-approval", SessionRequirements(approval = ApprovalRequirement.CallerDecides), if (codex) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.CALLER_APPROVAL))
                add(sessionCase("caller-questions", SessionRequirements(questions = QuestionRequirement.CallerAnswers), CompatibilityStatus.INCOMPATIBLE, Capability.QUESTIONS))
                add(sessionCase("diagnostic-channel", SessionRequirements(diagnostics = DiagnosticsRequirement.Required), CompatibilityStatus.COMPATIBLE, Capability.DIAGNOSTICS))
                add(sessionCase("workspace", SessionRequirements(workspace = WorkspaceRequirement.Required(workingDirectory = root.toAbsolutePath().toString())), if (!koog) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.WORKSPACE))
                add(sessionCase("read-only", SessionRequirements(approval = ApprovalRequirement.DenyAll,
                    execution = ExecutionConstraint.Required(filesystem = FilesystemAccess.ReadOnly)), if (codex) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.EXECUTION_CONSTRAINT))
                add(sessionCase("network-without-filesystem", SessionRequirements(execution = ExecutionConstraint.Required(network = NetworkAccess.ALLOWED)), CompatibilityStatus.INCOMPATIBLE, Capability.EXECUTION_CONSTRAINT))
                add(sessionCase("workspace-network", SessionRequirements(approval = ApprovalRequirement.DenyAll,
                    execution = ExecutionConstraint.Required(filesystem = FilesystemAccess.WorkspaceWrite(), network = NetworkAccess.DENIED)), if (codex) CompatibilityStatus.COMPATIBLE else CompatibilityStatus.INCOMPATIBLE, Capability.EXECUTION_CONSTRAINT))
                add(sessionCase("caller-cannot-expand-read-only", SessionRequirements(approval = ApprovalRequirement.CallerDecides,
                    execution = ExecutionConstraint.Required(filesystem = FilesystemAccess.ReadOnly)), CompatibilityStatus.INCOMPATIBLE, Capability.EXECUTION_CONSTRAINT))
                add(sessionCase("reviewer-cannot-expand-workspace", SessionRequirements(approval = ApprovalRequirement.AgentReviewed,
                    execution = ExecutionConstraint.Required(filesystem = FilesystemAccess.WorkspaceWrite())), CompatibilityStatus.INCOMPATIBLE, Capability.EXECUTION_CONSTRAINT))
                listOf(true, false).forEach { validate ->
                    add(RequirementCase("structured-${if (validate) "validated" else "caller-validation"}", base,
                        TaskRequest(TaskInput.Text("requirement-$id-structured"), TaskRequirements(OutputRequirement.Structured("{\"type\":\"object\"}", validate))),
                        CompatibilityStatus.COMPATIBLE, CompatibilityStatus.INCOMPATIBLE, capability = Capability.STRUCTURED_OUTPUT))
                }
                add(RequirementCase(
                    "unknown-reasoning-option",
                    base,
                    TaskRequest(
                        TaskInput.Text("requirement-$id-reasoning"),
                        TaskRequirements(reasoning = ReasoningOptionRequirement.Selected(
                            base.model ?: "provider-default",
                            ReasoningOptionId("ahp-known-unsupported-option"),
                        )),
                    ),
                    CompatibilityStatus.COMPATIBLE,
                    CompatibilityStatus.INCOMPATIBLE,
                    startDecision = CompatibilityStatus.INCOMPATIBLE,
                    capability = Capability.REASONING_OPTION_SELECTION,
                ))
            }
        }
        return FixtureProfile(id, "Pinned ${provider.value} native runtime; explicit storage namespace=$persistent; controlled text model responses", support, cases)
    }

    override fun close() {
        try { harnesses.asReversed().forEach { it.close() } } finally { observation.close() }
    }
}

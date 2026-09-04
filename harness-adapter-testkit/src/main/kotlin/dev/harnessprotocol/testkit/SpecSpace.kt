package dev.harnessprotocol.testkit

import dev.harnessprotocol.legacy.AgentSpec
import dev.harnessprotocol.legacy.ApprovalPolicy
import dev.harnessprotocol.legacy.ExecutionPolicy
import dev.harnessprotocol.legacy.FilesystemAccess
import dev.harnessprotocol.legacy.NetworkAccess
import dev.harnessprotocol.legacy.SkillReference

/**
 * Exhaustive enumeration of [AgentSpec] values used by the intent-projection contract.
 *
 * Add a new axis here in the same change that adds a field to [AgentSpec]; the
 * projection contract only protects fields that are enumerated.
 */
object SpecSpace {
    val instructions: List<String?> = listOf(null, "", "Be precise")
    val models: List<String?> = listOf(null, "model-x")
    val reasoningEfforts: List<String?> = listOf(null, "high")
    val workingDirectories: List<String?> = listOf(null, "/workspace")
    val skills: List<List<SkillReference>> = listOf(emptyList(), listOf(SkillReference("release-check", "/skills/release-check")))
    val filesystems: List<FilesystemAccess> = listOf(
        FilesystemAccess.ProviderDefault,
        FilesystemAccess.ReadOnly,
        FilesystemAccess.WorkspaceWrite(),
        FilesystemAccess.WorkspaceWrite(setOf("/extra")),
        FilesystemAccess.FullAccess,
    )
    val networks: List<NetworkAccess> = NetworkAccess.entries
    val approvals: List<ApprovalPolicy> = ApprovalPolicy.entries

    fun all(): Sequence<AgentSpec> = sequence {
        for (i in instructions) for (m in models) for (r in reasoningEfforts) for (w in workingDirectories) for (s in skills)
            for (f in filesystems) for (n in networks) for (a in approvals) {
                yield(
                    AgentSpec(
                        instructions = i,
                        model = m,
                        reasoningEffort = r,
                        workingDirectory = w,
                        skills = s,
                        executionPolicy = ExecutionPolicy(filesystem = f, network = n, approval = a),
                    ),
                )
            }
    }
}

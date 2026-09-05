package dev.harnessprotocol.testkit

import dev.harnessprotocol.*

/** Independent intent axes, including omitted versus explicitly empty configuration. */
object SpecSpace {
    fun all(): Sequence<SessionSpec> = sequence {
        val workspaces = listOf(WorkspaceRequirement.NotRequired, WorkspaceRequirement.Required(),
            WorkspaceRequirement.Required("/workspace"), WorkspaceRequirement.Required(skills = listOf(
                SkillReference("active", "/skills/active"), SkillReference("available", "/skills/available", false))))
        val filesystems = listOf(null, FilesystemAccess.ReadOnly, FilesystemAccess.WorkspaceWrite(),
            FilesystemAccess.WorkspaceWrite(setOf("/extra")), FilesystemAccess.FullAccess)
        val approvals = listOf(ApprovalRequirement.ProviderDefault, ApprovalRequirement.DenyAll,
            ApprovalRequirement.AgentReviewed, ApprovalRequirement.CallerDecides)
        val retentions = listOf(ContextRetentionRequirement.ProviderDefault, ContextRetentionRequirement.Ephemeral)
        val visibilities = listOf(UserHistoryVisibilityRequirement.ProviderDefault, UserHistoryVisibilityRequirement.Hidden)
        for (instructions in listOf(null, "", "Be precise")) for (model in listOf(null, "model-x"))
            for (workspace in workspaces) for (fs in filesystems) for (network in listOf(null, NetworkAccess.DENIED, NetworkAccess.ALLOWED))
                for (approval in approvals) for (retention in retentions) for (visibility in visibilities) {
                    yield(SessionSpec(instructions, model, SessionRequirements(workspace = workspace,
                        execution = if (fs == null && network == null) ExecutionConstraint.ProviderDefault else ExecutionConstraint.Required(fs, network),
                        approval = approval, retention = retention, historyVisibility = visibility)))
                }
    }
}

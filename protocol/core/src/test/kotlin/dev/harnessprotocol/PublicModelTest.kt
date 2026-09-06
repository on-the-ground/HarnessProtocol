package dev.harnessprotocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 공개 모델에서 소실됐던 의미 구별과 잘못된 합산의 회귀 검사. */
class PublicModelTest {
    @Test
    fun `unconfirmed requirement is neither compatible nor a confirmed rejection`() {
        val report = CompatibilityReport(listOf(
            CompatibilityIssue("requirements.persistence", "storage is unreachable", CompatibilityIssueKind.UNCONFIRMED),
        ))
        assertEquals(CompatibilityStatus.UNCONFIRMED, report.status)
        assertFalse(report.isCompatible)
        val failure = assertFailsWith<RequirementUnconfirmedException> { report.requireCompatible() }
        assertEquals(report.issues, failure.issues)
    }

    @Test
    fun `known incompatibility remains decisive while unknown facts are preserved`() {
        val report = CompatibilityReport(listOf(
            CompatibilityIssue("requirements.questions", "unavailable"),
            CompatibilityIssue("requirements.persistence", "not checked", CompatibilityIssueKind.UNCONFIRMED),
        ))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, report.status)
        assertEquals(report.issues, assertFailsWith<IncompatibleRequirementException> { report.requireCompatible() }.issues)
        val advisory = CompatibilityReport(listOf(
            CompatibilityIssue("model", "using configured default", CompatibilityIssueKind.ADVISORY),
        ))
        advisory.requireCompatible()
        assertTrue(advisory.isCompatible)
    }

    @Test
    fun `completion without acquired output differs from actual empty output`() {
        val noOutput = TaskOutcome.Completed(TaskId("task"), stopReason = StopReason.FINISHED)
        val emptyOutput = noOutput.copy(output = TaskOutput.Text(""))
        assertNull(noOutput.output)
        assertEquals(TaskOutput.Text(""), emptyOutput.output)
        assertEquals(noOutput.stopReason, emptyOutput.stopReason)
    }

    @Test
    fun `unmeasured segment prevents a fabricated known total`() {
        val first = AgentUsage(inputTokens = 10, outputTokens = 3, cacheWriteInputTokens = 4)
        val second = AgentUsage(inputTokens = null, outputTokens = 2, cacheWriteInputTokens = 2)
        val total = first + second
        assertNull(total.inputTokens)
        assertEquals(5L, total.outputTokens)
        assertEquals(6L, total.cacheWriteInputTokens)
        assertNull(total.totalTokens)
        assertEquals(first, AgentUsage.Zero + first)
        assertEquals(AgentUsage.Unknown, AgentUsage.Unknown + first)
    }

    @Test
    fun `counter reset does not yield negative task usage`() {
        val current = AgentUsage(inputTokens = 4, outputTokens = 20, cacheWriteInputTokens = 7)
        val baseline = AgentUsage(inputTokens = 10, outputTokens = 12, cacheWriteInputTokens = 3)
        assertNull((current - baseline).inputTokens)
        assertEquals(8L, (current - baseline).outputTokens)
        assertEquals(4L, (current - baseline).cacheWriteInputTokens)
        assertEquals(AgentUsage.Unknown, current - AgentUsage.Unknown)
    }

    @Test
    fun `network only requirement does not choose a filesystem policy`() {
        val requirement = ExecutionConstraint.Required(network = NetworkAccess.DENIED)
        assertNull(requirement.filesystem)
        assertEquals(NetworkAccess.DENIED, requirement.network)
        assertNull(ExecutionConstraint.Required(filesystem = FilesystemAccess.ReadOnly).network)
        assertFailsWith<IllegalArgumentException> { ExecutionConstraint.Required() }
    }

    @Test
    fun `session approval cannot be offered without an explicit grant`() {
        fun request(grant: SessionApprovalGrant?, decisions: Set<ApprovalDecision>) = InteractionRequest.Approval(
            InteractionId("approval"), WorkId("effect"), "update the report", EffectKind.FILE_CHANGE,
            decisions, sessionGrant = grant,
        )
        val sessionDecision = setOf(ApprovalDecision.APPROVE_FOR_SESSION, ApprovalDecision.DECLINE)
        assertFailsWith<IllegalArgumentException> { request(null, sessionDecision) }
        val grant = SessionApprovalGrant(ApprovalScopeId("reports"), "Write files only in the report directory")
        assertEquals(grant, request(grant, sessionDecision).sessionGrant)
        assertFailsWith<IllegalArgumentException> { request(grant, setOf(ApprovalDecision.APPROVE_ONCE)) }
        assertFailsWith<IllegalArgumentException> { SessionApprovalGrant(ApprovalScopeId("reports"), " ") }
    }

    @Test
    fun `response uncertainty retains both task and request identity`() {
        val reference = UnconfirmedResponse(TaskId("task"), InteractionId("approval"))
        val failure = InteractionResponseUnconfirmedException(reference, "acknowledgement lost")
        assertEquals(reference, failure.reference)
        assertFalse((failure as Any) is HarnessTransportException)
    }

    @Test
    fun `provider diagnostics cannot enter the semantic event family`() {
        val record: DiagnosticEvent = ProviderDiagnostic(TaskId("task"), ProviderId("test"), "trace", "payload")
        assertFalse((record as Any) is TaskEvent)
        assertFailsWith<IllegalArgumentException> { DiagnosticGap(TaskId("task"), 0) }
    }
}

package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.AgentHarness
import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.AgentTask
import dev.harnessprotocol.ApprovalRequirement
import dev.harnessprotocol.Capability
import dev.harnessprotocol.CompatibilityStatus
import dev.harnessprotocol.DiagnosticsRequirement
import dev.harnessprotocol.ExecutionConstraint
import dev.harnessprotocol.NetworkAccess
import dev.harnessprotocol.OutputRequirement
import dev.harnessprotocol.PersistenceRequirement
import dev.harnessprotocol.ProviderId
import dev.harnessprotocol.QuestionRequirement
import dev.harnessprotocol.SessionRequirements
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.Support
import dev.harnessprotocol.SupportReport
import dev.harnessprotocol.SupportScope
import dev.harnessprotocol.TaskInput
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.TaskRequirements
import dev.harnessprotocol.conformance.FixtureProfile
import dev.harnessprotocol.conformance.HarnessFixture
import dev.harnessprotocol.conformance.RequirementCase
import dev.harnessprotocol.conformance.ResponseControl
import dev.harnessprotocol.conformance.RuntimeControl
import dev.harnessprotocol.conformance.SessionControl
import dev.harnessprotocol.conformance.StartControl
import dev.harnessprotocol.conformance.TaskControl

/**
 * 참조 엔진의 [HarnessFixture]. Native SDK/wire를 전혀 모르며, 모든 지원·거절·확인 판정은
 * [profiles]가 선언한 사례에서만 나온다. 이 fixture 자체가 실 adapter의 대체물은 아니고,
 * [dev.harnessprotocol.conformance.HarnessConformanceTest]가 실제로 구동 가능함을 보이기 위한
 * 참조 구현이다.
 */
internal class ReferenceFixture : HarnessFixture {
    override val provider = ProviderId("reference")

    private val allProfiles by lazy {
        listOf(
            baselineProfile(),
            approvalProfile(),
            questionsProfile(),
            persistenceProfile(),
            structuredOutputProfile(),
            diagnosticsProfile(),
        )
    }

    override fun profiles(): List<FixtureProfile> = allProfiles

    override fun createHarness(profileId: String): AgentHarness {
        val profile = allProfiles.first { it.id == profileId }
        val namespace = "ns-$profileId"
        return if (profile.expectedSupport[Capability.PERSISTENCE] == Support.Supported) {
            PersistentReferenceHarness(profile, namespace)
        } else {
            ReferenceHarness(profile, namespace)
        }
    }

    override fun control(task: AgentTask): TaskControl {
        val t = task as ReferenceTask
        return ReferenceTaskControl(t, t.ownerSession, t.scope)
    }

    override fun control(session: AgentSession): SessionControl {
        val s = session as ReferenceSession
        return ReferenceSessionControl(s.harness as? PersistentReferenceHarness)
    }

    override fun control(harness: AgentHarness): RuntimeControl = ReferenceRuntimeControl(harness as ReferenceHarness)

    override fun controlNextStart(session: AgentSession): StartControl = ReferenceStartControl(session as ReferenceSession)

    // ------------------------------------------------------------------------------------ profiles

    private fun baselineProfile(): FixtureProfile {
        val compatible = SessionSpec()
        val request = TaskRequest(TaskInput.Text("hello"))
        val incompatible = SessionSpec(requirements = SessionRequirements(persistence = PersistenceRequirement.Required()))
        val unconfirmed = SessionSpec(requirements = SessionRequirements(execution = ExecutionConstraint.Required(network = NetworkAccess.ALLOWED)))
        return FixtureProfile(
            id = "baseline",
            description = "기본 요구만 쓰는 정상 작업 profile. 영속성은 확정 미지원, network 실행 제약은 확인 불가로 응답한다.",
            expectedSupport = SupportReport(
                mapOf(
                    Capability.CALLER_APPROVAL to Support.Unsupported("baseline profile declines caller approval"),
                    Capability.QUESTIONS to Support.Unsupported("baseline profile declines questions"),
                    Capability.PERSISTENCE to Support.Unsupported("baseline profile has no storage backend"),
                    Capability.WORKSPACE to Support.Supported,
                    Capability.EXECUTION_CONSTRAINT to Support.Conditional(SupportScope.SESSION, "network 허용 여부는 실행 전에는 확인할 수 없다"),
                    Capability.STRUCTURED_OUTPUT to Support.Unsupported("baseline profile only returns text"),
                    Capability.DIAGNOSTICS to Support.Unsupported("baseline profile has no provider diagnostics"),
                ),
            ),
            cases = listOf(
                RequirementCase("compatible", compatible, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE),
                RequirementCase(
                    "incompatible-persistence",
                    incompatible,
                    request,
                    CompatibilityStatus.INCOMPATIBLE,
                    CompatibilityStatus.INCOMPATIBLE,
                    capability = Capability.PERSISTENCE,
                ),
                RequirementCase(
                    "unconfirmed-network",
                    unconfirmed,
                    request,
                    CompatibilityStatus.UNCONFIRMED,
                    CompatibilityStatus.UNCONFIRMED,
                    capability = Capability.EXECUTION_CONSTRAINT,
                ),
            ),
        )
    }

    private fun approvalProfile(): FixtureProfile {
        val spec = SessionSpec(requirements = SessionRequirements(approval = ApprovalRequirement.CallerDecides))
        val request = TaskRequest(TaskInput.Text("do the guarded thing"))
        return FixtureProfile(
            id = "approval",
            description = "CallerDecides 승인을 요구하는 session. 세션 승인 범위(SessionApprovalGrant)를 실제로 집행한다.",
            expectedSupport = SupportReport(mapOf(Capability.CALLER_APPROVAL to Support.Supported)),
            cases = listOf(RequirementCase("compatible", spec, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE)),
        )
    }

    private fun questionsProfile(): FixtureProfile {
        val spec = SessionSpec(requirements = SessionRequirements(questions = QuestionRequirement.CallerAnswers))
        val request = TaskRequest(TaskInput.Text("ask me something first"))
        return FixtureProfile(
            id = "questions",
            description = "질문 흐름을 요구하는 session. typed 답변을 받아 문맥에 반영한다.",
            expectedSupport = SupportReport(mapOf(Capability.QUESTIONS to Support.Supported)),
            cases = listOf(RequirementCase("compatible", spec, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE)),
        )
    }

    private fun persistenceProfile(): FixtureProfile {
        val spec = SessionSpec(requirements = SessionRequirements(persistence = PersistenceRequirement.Required(acrossHarnessRestart = true)))
        val request = TaskRequest(TaskInput.Text("remember this"))
        return FixtureProfile(
            id = "persistence",
            description = "harness 재생성 이후 재개를 지원하는 session. 같은 profile은 같은 namespace를 공유한다.",
            expectedSupport = SupportReport(mapOf(Capability.PERSISTENCE to Support.Supported)),
            cases = listOf(RequirementCase("compatible", spec, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE)),
        )
    }

    private fun structuredOutputProfile(): FixtureProfile {
        val spec = SessionSpec()
        val request = TaskRequest(
            TaskInput.Text("produce structured output"),
            TaskRequirements(output = OutputRequirement.Structured(schema = "{\"type\":\"object\"}", validatedByHarness = true)),
        )
        return FixtureProfile(
            id = "structured-output",
            description = "harness가 직접 schema 검증까지 수행하는 구조화 산출물 요구.",
            expectedSupport = SupportReport(mapOf(Capability.STRUCTURED_OUTPUT to Support.Supported)),
            cases = listOf(RequirementCase("compatible", spec, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE)),
        )
    }

    private fun diagnosticsProfile(): FixtureProfile {
        val spec = SessionSpec(requirements = SessionRequirements(diagnostics = DiagnosticsRequirement.Required))
        val request = TaskRequest(TaskInput.Text("trace this"))
        return FixtureProfile(
            id = "diagnostics",
            description = "공급자 원본 진단을 별도 경로로 전달해야 하는 session.",
            expectedSupport = SupportReport(mapOf(Capability.DIAGNOSTICS to Support.Supported)),
            cases = listOf(RequirementCase("compatible", spec, request, CompatibilityStatus.COMPATIBLE, CompatibilityStatus.COMPATIBLE)),
        )
    }
}

package dev.harnessprotocol

/**
 * Session 수명 동안 적용할 설정과 요구.
 *
 * 요구를 값으로 선언하는 것이 곧 필수 요구다. [AgentHarness.support]를 조회하지 않고도 선언할
 * 수 있으며, 보존할 수 없는 요구는 실제 호출 경계에서 거절된다. 최선 노력으로 충분하면 요구를
 * 선언하지 않는다.
 */
data class SessionSpec(
    /** 지속 system/developer 수준 지시. `null`은 provider 기본, `""`는 명시적 빈 지시다. */
    val instructions: String? = null,
    /** provider가 이해하는 모델 식별자. `null`은 provider 기본. */
    val model: String? = null,
    val requirements: SessionRequirements = SessionRequirements(),
)

/** Session 범위에서 요구하는 선택 계약의 집합. 기본값은 어떤 추가 보장도 요구하지 않는다. */
data class SessionRequirements(
    val approval: ApprovalRequirement = ApprovalRequirement.ProviderDefault,
    val questions: QuestionRequirement = QuestionRequirement.NotRequired,
    val persistence: PersistenceRequirement = PersistenceRequirement.NotRequired,
    val workspace: WorkspaceRequirement = WorkspaceRequirement.NotRequired,
    val execution: ExecutionConstraint = ExecutionConstraint.ProviderDefault,
    val diagnostics: DiagnosticsRequirement = DiagnosticsRequirement.NotRequired,
)

/** 승인이 필요한 효과를 누가 결정하는가. 무엇이 승인 대상인지는 provider 정책이 정한다. */
sealed interface ApprovalRequirement {
    /** provider/runtime에 이미 설정된 기본값을 사용한다. adapter SDK의 convenience default가 아니다. */
    data object ProviderDefault : ApprovalRequirement
    /** provider 정책상 승인이 필요한 효과를 모두 거절한다. */
    data object DenyAll : ApprovalRequirement
    /** provider의 자동 reviewer에게 위임한다. */
    data object AgentReviewed : ApprovalRequirement
    /** 승인 요청마다 [TaskEvent.InteractionRequested]를 발생시키고 caller의 응답을 기다린다. */
    data object CallerDecides : ApprovalRequirement
}

/** 진행에 필요한 정보를 caller에게 묻는 흐름의 지원 요구. */
sealed interface QuestionRequirement {
    data object NotRequired : QuestionRequirement
    /** 질문 요청을 [TaskEvent.InteractionRequested]로 전달하고 typed 답변을 수락해야 한다. */
    data object CallerAnswers : QuestionRequirement
}

/** 문맥을 handle·harness 수명 너머로 보관하고 다시 얻는 요구. */
sealed interface PersistenceRequirement {
    data object NotRequired : PersistenceRequirement

    /**
     * 선언한 보관 범위 안에서 [AgentSession.persistentRef]로 다시 열 수 있어야 한다.
     *
     * @property acrossHarnessRestart harness 재생성 이후의 재개를 요구한다
     * @property acrossProcessRestart process 재시작 이후의 재개를 요구한다
     * @property concurrentAccess 여러 harness·process의 동시 접근 조정을 요구한다
     */
    data class Required(
        val acrossHarnessRestart: Boolean = true,
        val acrossProcessRestart: Boolean = false,
        val concurrentAccess: Boolean = false,
    ) : PersistenceRequirement
}

/** 작업 공간과 자료·지침의 제공 요구. 제공과 활성화를 구별한다. */
sealed interface WorkspaceRequirement {
    data object NotRequired : WorkspaceRequirement

    /**
     * @property workingDirectory 도구가 기준으로 삼을 절대 경로
     * @property skills 제공할 skill. [SkillReference.activate]가 실행별 활성화 여부를 정한다
     */
    data class Required(
        val workingDirectory: String? = null,
        val skills: List<SkillReference> = emptyList(),
    ) : WorkspaceRequirement
}

/**
 * @property name provider에 노출할 non-blank 이름
 * @property path skill 디렉터리의 non-blank 경로. 절대 경로를 권장한다
 * @property activate `true`면 매 작업에서 provider의 활성화 envelope로 활성화한다. 사용자 입력
 * 자체는 바뀌지 않는다
 */
data class SkillReference(
    val name: String,
    val path: String,
    val activate: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "skill name must not be blank" }
        require(path.isNotBlank()) { "skill path must not be blank" }
    }
}

/** 도구 실행 환경에 집행을 요구하는 제약. 승인 gate와 다른 계약이다. */
sealed interface ExecutionConstraint {
    /** provider/runtime의 현재 정책을 바꾸지 않는다. provider 간 동일 권한을 뜻하지 않는다. */
    data object ProviderDefault : ExecutionConstraint

    /**
     * @property filesystem 파일 시스템 경계. `null`이면 현재 정책을 변경하도록 요구하지 않는다
     * @property network outbound network 허용 여부. `null`이면 요구하지 않는다
     */
    data class Required(
        val filesystem: FilesystemAccess? = null,
        val network: NetworkAccess? = null,
    ) : ExecutionConstraint {
        init {
            require(filesystem != null || network != null) { "at least one execution constraint must be required" }
        }
    }
}

sealed interface FilesystemAccess {
    /** 읽기는 허용하되 변경을 막는다. */
    data object ReadOnly : FilesystemAccess
    /** workspace와 [additionalWritableRoots] 안의 쓰기를 허용한다. */
    data class WorkspaceWrite(val additionalWritableRoots: Set<String> = emptySet()) : FilesystemAccess
    /** harness가 정의하는 sandbox 없이 실행한다. */
    data object FullAccess : FilesystemAccess
}

/** `ALLOWED`는 연결·자격 증명·서비스 가용성을 보장하지 않는다. */
enum class NetworkAccess { DENIED, ALLOWED }

/** 공급자 원본 관찰의 요구. 기본 적합성 조건이 아니다. */
sealed interface DiagnosticsRequirement {
    data object NotRequired : DiagnosticsRequirement
    /** 선언한 범위의 [ProviderDiagnostic]을 별도 [TaskDiagnostics.diagnostics] 경로로 전달해야 한다. */
    data object Required : DiagnosticsRequirement
}

/** 한 번의 위임에 대한 입력과 그 작업에만 적용할 요구. */
data class TaskRequest(
    val input: TaskInput,
    val requirements: TaskRequirements = TaskRequirements(),
    /**
     * Provider가 이해하는 작업별 추론 강도 식별자. `null`은 session/provider 기본값을 유지한다.
     *
     * Adapter는 지정된 값을 그대로 보존하거나 작업을 시작하기 전에 호환성 오류로 거절해야 한다.
     */
    val reasoningEffort: String? = null,
)

data class TaskRequirements(
    val output: OutputRequirement = OutputRequirement.Text,
)

/** 산출물의 형태 요구. */
sealed interface OutputRequirement {
    /** 텍스트 산출물을 받는다. schema 보장을 요구하지 않는다. */
    data object Text : OutputRequirement

    /**
     * 요구한 schema를 만족하는 구조화 산출물을 받는다.
     *
     * @property schema provider에 전달할 schema 문서
     * @property validatedByHarness 하네스 제공 경계가 검증까지 수행할 것을 요구한다. `false`면
     * 검증 책임이 호출자에게 있으며 [TaskOutput.Structured.validation]은 `NOT_VALIDATED`일 수 있다
     */
    data class Structured(
        val schema: String,
        val validatedByHarness: Boolean = true,
    ) : OutputRequirement {
        init { require(schema.isNotBlank()) { "schema must not be blank" } }
    }
}

/** 작업에 전달하는 입력. 작업 중 질문에 대한 답변과 구별한다. */
sealed interface TaskInput {
    /** 빈 문자열은 거절한다. 공백만 있는 문자열은 유효하며 adapter가 임의로 trim하지 않는다. */
    data class Text(val text: String) : TaskInput {
        init { require(text.isNotEmpty()) { "input text must not be empty" } }
    }
}

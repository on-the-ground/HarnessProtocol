package dev.harnessprotocol

/**
 * 한 Task 범위의 의미 이벤트.
 *
 * 이벤트는 실시간 관찰 통로이며 영속 업무 이력이 아니다. state, pendingInteractions,
 * awaitOutcome은 이벤트 구독 여부·속도와 독립이다. 이벤트별 소비 목적은
 * docs/event-contract.md 의 목적표를 따른다.
 */
sealed interface TaskEvent {
    val taskId: TaskId

    /** 작업의 진행이 시작됐다. 대응하는 outcome은 없다. */
    data class TaskStarted(override val taskId: TaskId) : TaskEvent

    /**
     * 진행 중 메시지의 조각. 같은 [messageId]의 조각을 이어 붙여 표시한다.
     *
     * @property role 관찰 가능한 역할. 알 수 없으면 [MessageRole.UNKNOWN]을 보존하고 최종
     * 답변으로 꾸미지 않는다
     */
    data class MessageDelta(
        override val taskId: TaskId,
        val messageId: MessageId,
        val text: String,
        val role: MessageRole = MessageRole.UNKNOWN,
    ) : TaskEvent

    /**
     * 한 메시지의 완료 snapshot. 앞선 delta에 이어 붙이는 값이 아니다.
     *
     * 이 이벤트는 표시를 확정할 뿐이며 작업의 [TaskOutput]과 같은 개념이 아니다.
     */
    data class MessageCompleted(
        override val taskId: TaskId,
        val messageId: MessageId,
        val text: String,
        val role: MessageRole = MessageRole.UNKNOWN,
    ) : TaskEvent

    /**
     * 이름 있는 도구 수행의 lifecycle.
     *
     * @property arguments provider 원본 인자. vendor별 schema이며 공통 산출물 schema가 아니다
     */
    data class ToolCallChanged(
        override val taskId: TaskId,
        val workId: WorkId,
        val name: String,
        val status: WorkStatus,
        val arguments: String? = null,
        val result: String? = null,
        val error: String? = null,
    ) : TaskEvent

    /**
     * 관찰한 외부 효과 또는 명시된 효과 시도.
     *
     * 같은 실제 작업이 [ToolCallChanged]와 함께 발생할 수 있고, 그 관계를 입증할 수 있을 때만
     * 같은 [workId]를 사용한다. [WorkStatus.STARTED]가 실제 변경을 뜻하지는 않는다.
     */
    data class EffectChanged(
        override val taskId: TaskId,
        val workId: WorkId,
        val kind: EffectKind,
        val status: WorkStatus,
        val description: String? = null,
        val output: String? = null,
        val exitCode: Int? = null,
        /** provider가 보고한 경로. 빈 목록이 변경 없음의 증명은 아니다. */
        val changedPaths: List<String> = emptyList(),
    ) : TaskEvent

    /** 외부 판단·정보 요청이 열렸다. 응답 대상의 진실은 [AgentTask.pendingInteractions]다. */
    data class InteractionRequested(
        override val taskId: TaskId,
        val request: InteractionRequest,
    ) : TaskEvent

    /** 요청이 응답되거나 응답 없이 정리됐다. */
    data class InteractionResolved(
        override val taskId: TaskId,
        val interactionId: InteractionId,
        val resolution: InteractionResolution,
    ) : TaskEvent

    /**
     * 그 시점까지의 사용량 snapshot. 이전 값에 더하지 않고 새 값으로 갱신한다.
     *
     * @property task 이 Task의 누적. 이전 Task의 사용량을 섞지 않는다
     * @property session provider가 session 누적을 보고할 때만 존재한다
     */
    data class UsageChanged(
        override val taskId: TaskId,
        val task: AgentUsage,
        val session: AgentUsage? = null,
    ) : TaskEvent

    /**
     * 요구가 그대로 유지되는 상태에서 구성이나 사용 방식을 고칠 수 있는 사실.
     *
     * 지원 불가·필수 의미 손실·확인된 실패를 이 이벤트로 낮추지 않는다.
     */
    data class Warning(
        override val taskId: TaskId,
        val kind: WarningKind,
        val message: String,
    ) : TaskEvent

    /**
     * 이 collector에 대해 [droppedEvents]개의 non-terminal 이벤트가 전달되지 못했다.
     *
     * 다른 collector와 state·pending·outcome은 영향을 받지 않으며 terminal은 유실되지 않는다.
     */
    data class ObservationGap(
        override val taskId: TaskId,
        val droppedEvents: Long,
    ) : TaskEvent

    /** 종결 이벤트. [outcome]은 [AgentTask.awaitOutcome]이 회수하는 판정과 같은 의미다. */
    sealed interface Terminal : TaskEvent {
        val outcome: TaskOutcome
    }

    data class TaskCompleted(
        override val taskId: TaskId,
        override val outcome: TaskOutcome.Completed,
    ) : Terminal

    data class TaskFailed(
        override val taskId: TaskId,
        override val outcome: TaskOutcome.Failed,
    ) : Terminal

    data class TaskCancelled(
        override val taskId: TaskId,
        override val outcome: TaskOutcome.Cancelled,
    ) : Terminal

    data class TaskUnresolved(
        override val taskId: TaskId,
        override val outcome: TaskOutcome.Unresolved,
    ) : Terminal
}

/**
 * 메시지의 관찰 가능한 역할.
 *
 * provider가 공개하는 설명·요약은 [EXPLANATION]으로 전달한다. 노출되지 않은 추론을 합성하지
 * 않으며 역할을 알 수 없으면 [UNKNOWN]을 보존한다.
 */
enum class MessageRole { ANSWER, COMMENTARY, EXPLANATION, UNKNOWN }

/** 하위 작업의 상태. 부모 Task의 취소나 미확정을 근거로 전부 [CANCELLED]로 바꾸지 않는다. */
enum class WorkStatus {
    STARTED,
    UPDATED,
    COMPLETED,
    FAILED,
    /** 승인·정책 거절로 대상 행위를 수행하지 않았다. */
    DECLINED,
    /** 해당 작업의 취소에 따른 종료를 확인했다. */
    CANCELLED,
}

/** 외부 효과의 portable 분류. */
enum class EffectKind { COMMAND, FILE_CHANGE, WEB_SEARCH, OTHER }

enum class WarningKind {
    /** context가 한도에 가까워 관리나 실패가 뒤따를 수 있다. */
    CONTEXT_PRESSURE,
    /** 구성 문제. 해당 정책에서 오지 않아야 할 요청이 도착해 거절한 경우를 포함한다. */
    CONFIGURATION,
    /** provider가 스스로 복구를 시도하는 오류. */
    RECOVERABLE,
    OTHER,
}

/** Task handle이 관찰·확정한 상태. */
enum class TaskState {
    /** 작업이 수락됐으나 실제 진행은 아직 확인하지 못했다. */
    STARTING,
    RUNNING,
    /** 유효한 외부 응답을 기다린다. */
    AWAITING_RESPONSE,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** 관찰·제어를 종결하면서 실제 결과를 확인하지 못했다. */
    UNRESOLVED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == UNRESOLVED
}

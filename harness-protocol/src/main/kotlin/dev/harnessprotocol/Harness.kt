package dev.harnessprotocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 비즈니스 애플리케이션이 에이전트 작업을 위임하는 Port.
 *
 * 구현이 앱 내부 라이브러리인지, 별도 process인지, 원격 서비스인지는 이 계약에 나타나지 않는다.
 * 내부 모델 호출, 실행 그래프, 도구 구성, 저장소와 transport는 하네스 제공 경계의 책임이다.
 */
interface AgentHarness : AutoCloseable {
    /** adapter 제공 종류를 구별하는 구성 식별자. */
    val provider: ProviderId

    /**
     * 선택 계약의 지원 정보. 조회하지 않고도 요구를 명시할 수 있으며 이 값은 이후 요청의
     * 이행 보장이 아니다.
     */
    val support: SupportReport

    /** 정리 연산의 공개된 시간 상한. */
    val cleanupBudget: CleanupBudget

    /**
     * session 범위 요구의 호환성을 진단한다.
     *
     * 사전 검증을 생략해도 [createSession]이 같은 검증을 다시 수행한다. 설정 호환성이
     * 인증·네트워크·실행 성공까지 보장하지는 않는다.
     */
    fun validate(spec: SessionSpec): CompatibilityReport

    /**
     * 문맥을 공유할 새 범위를 연다. 영속 conversation 생성이나 process 시작을 뜻하지 않는다.
     *
     * @throws IncompatibleRequirementException 요구를 보존할 수 없을 때
     * @throws HarnessTransportException 제공 경계에 요청을 전달하지 못했을 때
     * @throws RequirementUnconfirmedException 실제 호출 경계에서도 요구 이행 가능 여부를 확인하지 못했을 때
     */
    suspend fun createSession(spec: SessionSpec): AgentSession

    /**
     * 이 harness가 소유한 handle과 자원을 정리한다.
     *
     * 진행 중 작업에 취소를 요청하고 [cleanupBudget] 안에서 종료를 확인한다. 확인한 결과가
     * 있으면 그 판정을, 없으면 [TaskOutcome.Unresolved]를 전달한다. 유예 만료를 취소 성공으로
     * 바꾸지 않는다.
     */
    override fun close()
}

/**
 * 여러 작업이 문맥을 공유하는 논리적 범위.
 *
 * 문맥 연속성은 기본 책임이다. 소비자는 provider별 이력 조립·재전달 없이 앞선 작업의 문맥을
 * 이어 다음 작업을 위임할 수 있다. 수명을 넘는 보관은 [PersistenceRequirement]로 요구하는
 * 별도 목적이다.
 */
interface AgentSession {
    val id: SessionId

    /** 이 session을 열 때 사용한 설정의 snapshot. */
    val spec: SessionSpec

    /**
     * 영속 보관을 요구하고 지원받았을 때의 재개 참조. 요구하지 않았다면 `null`이다.
     *
     * 이 참조는 [PersistentSessions.reopenSession]에 넘긴다. 기본 [id]만으로 재개 가능성을
     * 추론하지 않는다.
     */
    val persistentRef: PersistentSessionRef?

    /** 작업 범위 요구의 호환성을 진단한다. */
    fun validate(request: TaskRequest): CompatibilityReport

    /**
     * 작업을 위임하고 handle을 반환한다. 작업 완료까지 기다리지 않는다.
     *
     * 같은 session의 작업은 순차적으로 시작한다. 진행 중 작업이 있으면 제공 경계에 요청을
     * 보내기 전에 거절한다. 취소를 요청만 한 상태도 여전히 진행 중이다.
     *
     * @throws SessionBlockedException 이전 작업이 [TaskOutcome.Unresolved]이거나 시작 수락이
     * 미확정이어서 문맥의 안전성을 확인하지 못했을 때, 또는 이 session이 이미 해제됐을 때
     * @throws IllegalStateException 이 session에 진행 중 작업이 있을 때
     * @throws IncompatibleRequirementException 요구를 보존할 수 없을 때
     * @throws TaskStartUnconfirmedException 요청은 전달했으나 수락 여부를 확인하지 못했을 때
     * @throws RequirementUnconfirmedException 실제 호출 경계에서도 요구 이행 가능 여부를 확인하지 못했을 때
     * @throws HarnessTransportException 요청을 전달하지 못했음이 확인됐을 때
     */
    suspend fun startTask(request: TaskRequest): AgentTask

    /**
     * 이 session handle과 관련 자원을 해제한다. idempotent다.
     *
     * 진행 중 작업에 취소를 요청하고 [AgentHarness.cleanupBudget] 안에서 종료를 확인한다.
     * 이전 작업의 실제 종료나 차단 해소를 뜻하지 않으며, 영속 보관을 요구하지 않았다면
     * 이후의 문맥 보존도 보장하지 않는다.
     */
    suspend fun release()
}

/**
 * 영속 문맥을 다시 여는 선택 계약.
 *
 * 지원하는 adapter의 [AgentHarness]가 이 interface를 함께 구현한다. 지원하지 않으면 구현하지
 * 않으며, [PersistenceRequirement.Required]를 선언한 요구는 [AgentHarness.validate]가 거절한다.
 */
interface PersistentSessions {
    /**
     * 보관된 문맥의 새 handle을 얻는다.
     *
     * 모르는 참조나 다른 저장 namespace의 참조는 거절하며 새 session으로 바꾸지 않는다.
     * [spec]은 재개 이후 적용할 desired configuration이며 의미를 보존할 수 없는 변경은 거절한다.
     * 중단된 작업의 실행 위치 복구, 진행 중 작업 재접속, 외부 효과의 복원은 포함하지 않는다.
     *
     * 선언한 조정 범위에서는 차단 사실을 이어받는다. 이전 실행이 문맥을 더 이상 변경할 수
     * 없음과 문맥 일관성을 확인하지 못하면 재개를 거절하거나 차단된 handle을 반환한다.
     * reopen 성공 자체를 복구 성공으로 보고하지 않는다.
     *
     * @throws IncompatibleRequirementException 요구나 조정 범위를 보존할 수 없을 때
     * @throws RequirementUnconfirmedException 요구나 조정 범위를 확인하지 못했을 때
     * @throws HarnessTransportException 보관된 문맥에 접근하지 못했을 때
     */
    suspend fun reopenSession(ref: PersistentSessionRef, spec: SessionSpec): AgentSession
}

/**
 * 한 번 위임한 작업의 관찰·개입·종결 handle.
 *
 * provider의 turn, 모델 호출, 내부 graph node와 일대일 대응하지 않는다. 여러 내부 호출이 한
 * 작업을 구성할 수 있다.
 */
interface AgentTask {
    val id: TaskId
    val sessionId: SessionId

    /** 현재 상태. event collector가 없거나 느려도 갱신된다. */
    val state: StateFlow<TaskState>

    /**
     * 진행·도구·효과·interaction·종결의 의미 이벤트.
     *
     * collector마다 bounded queue로 전달한다. 느린 collector는 [TaskEvent.ObservationGap]으로
     * 유실을 통보받으며 terminal 이벤트는 유실되지 않고 마지막에 전달된다. 늦게 구독한
     * collector가 과거 이벤트를 받는다고 가정하지 않는다.
     */
    val events: Flow<TaskEvent>

    /**
     * 지금 응답해야 할 요청의 snapshot. [TaskState.AWAITING_RESPONSE]가 아니면 비어 있다.
     *
     * 이벤트 유실과 독립이므로 요청 이벤트를 놓쳐도 이 값으로 응답 대상을 회수한다.
     */
    val pendingInteractions: StateFlow<List<InteractionRequest>>

    /**
     * 열린 요청에 한 번 응답한다.
     *
     * 성공 반환은 제공 경계의 전달·수락이 확인됐다는 뜻이며 모든 요청의 해결이나 작업 재개
     * 완료까지 뜻하지 않는다. 수락 확인을 잃으면 [InteractionResponseUnconfirmedException]을
     * 전달한다. 그 요청은 pending에서 제외하고 [ClearReason.RESPONSE_UNCONFIRMED]로 정리하며
     * 같은 ID에 대한 재응답을 거절한다. 이 정리는 provider의 미수락이나 Task 종결을 뜻하지 않는다.
     * 이미 provider가 요청을 닫았다면 정리 이벤트를 중복 생성하지 않는다.
     *
     * 호출 coroutine의 취소도 미전달 증거가 아니다. 전달 여부가 불확실해졌다면 caller의
     * coroutine과 독립적으로 같은 재응답 차단을 유지한다.
     *
     * @throws IllegalStateException 모르는 ID이거나 이미 닫힌 요청일 때
     * @throws IllegalArgumentException 요청 종류와 맞지 않는 응답이거나 요청이 제시하지 않은
     * 결정일 때. 이 경우 응답을 제공 경계에 전달하지 않는다
     * @throws HarnessTransportException 응답을 전달하지 못했음이 확인됐을 때
     * @throws InteractionResponseUnconfirmedException 응답 전달 뒤 수락 여부를 확인하지 못했을 때
     */
    suspend fun respond(interactionId: InteractionId, response: InteractionResponse)

    /**
     * 실제 중단을 요청한다. 반환이 중단 완료를 뜻하지 않는다.
     *
     * 열린 요청은 [ClearReason.CANCELLATION_REQUESTED]로 먼저 정리한 뒤 제공 경계에 중단을
     * 요청한다. terminal 이후의 호출은 no-op이며 반복 요청으로 중복 효과를 만들지 않는다.
     *
     * @throws HarnessTransportException 요청을 전달하지 못했을 때
     */
    suspend fun requestCancellation()

    /**
     * 종결 판정을 회수한다. 모든 waiter가 같은 판정을 받는다.
     *
     * 반환 전에 대응 terminal state가 확정되고 pending snapshot이 비워진다. 실패·취소·종료
     * 미확정도 예외가 아니라 [TaskOutcome] 값으로 전달한다. 이 coroutine의 취소는 작업 취소를
     * 뜻하지 않으므로 작업도 멈추려면 [requestCancellation]을 호출한다.
     */
    suspend fun awaitOutcome(): TaskOutcome
}

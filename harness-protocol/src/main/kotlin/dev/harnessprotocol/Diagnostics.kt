package dev.harnessprotocol

import kotlinx.coroutines.flow.Flow

/**
 * 공급자 진단을 관찰하는 선택 계약. [DiagnosticsRequirement.Required]를 수락한 작업은
 * [AgentTask]와 함께 이 interface를 구현한다. 지원 범위는 해당 adapter 구성에 명시한다.
 *
 * 기본 [AgentTask.events]와 별도 queue·유실 정책을 사용한다. 진단의 양이나 observer 지연이
 * 의미 이벤트·state·pending·outcome 진행에 영향을 주지 않는다. Task 종결 시 진단 flow도
 * 유한하게 닫히지만 두 flow의 callback 간 전역 순서는 보장하지 않는다.
 */
interface TaskDiagnostics {
    val diagnostics: Flow<DiagnosticEvent>
}

/** 선택 진단 경로의 관찰. 기본 TaskEvent의 하위 타입이 아니다. */
sealed interface DiagnosticEvent {
    val taskId: TaskId
}

/** 선언한 진단 범위의 공급자 원본. 기본 결과·실패 분류를 대신하지 않는다. */
data class ProviderDiagnostic(
    override val taskId: TaskId,
    val provider: ProviderId,
    val name: String,
    val payload: String,
) : DiagnosticEvent

/** 이 진단 observer의 bounded queue에서 유실된 수. 의미 이벤트 유실과 구별한다. */
data class DiagnosticGap(
    override val taskId: TaskId,
    val droppedRecords: Long,
) : DiagnosticEvent {
    init { require(droppedRecords > 0) { "dropped diagnostic count must be positive" } }
}

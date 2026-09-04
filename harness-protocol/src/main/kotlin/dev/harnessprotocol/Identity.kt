package dev.harnessprotocol

/**
 * AHP의 공개 Port identity. provider 구성, 논리 문맥, 작업과 하위 작업의 식별을 구별한다.
 *
 * 의미의 규범은 docs/semantic-contract.md 의 규범 위치표를 따른다.
 */

/** Adapter 제공 종류를 구별하는 구성 식별자. 계정·저장소·영속 문맥의 namespace가 아니다. */
@JvmInline
value class ProviderId(val value: String) {
    init { require(value.isNotBlank()) { "provider id must not be blank" } }
}

/**
 * 발급한 harness의 논리 session을 구별한다.
 *
 * 같은 문자열이 다른 harness에 있어도 같은 문맥이라는 뜻이 아니다. 재시작 이후의 보관·재개
 * 가능성은 이 ID로 추론하지 않으며 [PersistentSessionRef]가 필요하다.
 */
@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "session id must not be blank" } }
}

/** 한 번의 업무 위임을 소유 harness 안에서 구별한다. native turn ID와 같을 필요가 없다. */
@JvmInline
value class TaskId(val value: String) {
    init { require(value.isNotBlank()) { "task id must not be blank" } }
}

/** 하나의 Task 안에서 도구 수행·외부 효과를 구별한다. Task 밖에서 유일하지 않다. */
@JvmInline
value class WorkId(val value: String) {
    init { require(value.isNotBlank()) { "work id must not be blank" } }
}

/** 하나의 Task 안에서 외부 응답 요청을 구별한다. */
@JvmInline
value class InteractionId(val value: String) {
    init { require(value.isNotBlank()) { "interaction id must not be blank" } }
}

/** 하나의 Task 안에서 메시지를 구별한다. delta와 완료 snapshot을 연결한다. */
@JvmInline
value class MessageId(val value: String) {
    init { require(value.isNotBlank()) { "message id must not be blank" } }
}

/** 같은 논리 session 안에서 반복 승인할 대상·조건의 범위를 구별한다. */
@JvmInline
value class ApprovalScopeId(val value: String) {
    init { require(value.isNotBlank()) { "approval scope id must not be blank" } }
}

/**
 * 영속 문맥이 보관된 저장소 구성을 구별한다. 계정·endpoint·저장소를 구별할 수 있어야 하며
 * 자격 증명 자체를 담지 않는다.
 */
@JvmInline
value class StorageNamespace(val value: String) {
    init { require(value.isNotBlank()) { "storage namespace must not be blank" } }
}

/**
 * 보관된 문맥을 다시 열기 위한 참조.
 *
 * provider 종류와 ID 문자열만으로 전역 동일성을 추정하지 않기 위해 namespace를 함께 보존한다.
 */
data class PersistentSessionRef(
    val provider: ProviderId,
    val namespace: StorageNamespace,
    val id: String,
) {
    init { require(id.isNotBlank()) { "persistent session id must not be blank" } }
}

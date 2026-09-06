package dev.harnessprotocol.codex

import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.AgentTask
import dev.harnessprotocol.CompatibilityReport
import dev.harnessprotocol.TaskRequest

/** Codex `model/list`의 model catalog에서만 해석되는 model 식별자. */
@JvmInline
value class CodexModelId(val value: String) {
    init { require(value.isNotBlank()) { "Codex model id must not be blank" } }
}

/**
 * Codex `model/list`가 확인한 canonical model과 같은 model을 가리키는 alias.
 *
 * [matches]는 현재 catalog snapshot에서 확인한 identity만 비교한다. 목록에 없는 값을
 * 다른 model이라고 단정하지 않는다.
 */
data class CodexModelIdentity(
    val canonicalModel: CodexModelId,
    val aliases: Set<String> = emptySet(),
) {
    init { require(aliases.none(String::isBlank)) { "Codex model aliases must not be blank" } }

    fun matches(value: String): Boolean = value == canonicalModel.value || value in aliases
}

/** Codex model 범위에서만 유효한 opaque reasoning option 식별자. */
@JvmInline
value class CodexReasoningOptionId(val value: String) {
    init { require(value.isNotBlank()) { "Codex reasoning option id must not be blank" } }
}

/** Codex SDK가 한 model에 대해 보고한 reasoning option의 표시 정보. */
data class CodexReasoningOptionDescriptor(
    val id: CodexReasoningOptionId,
    val displayName: String,
    val description: String? = null,
) {
    init {
        require(displayName.isNotBlank()) { "Codex reasoning option display name must not be blank" }
        require(description == null || description.isNotBlank()) {
            "Codex reasoning option description must be null or non-blank"
        }
    }
}

/**
 * 한 Codex model에 대해 현재 `model/list`에서 관찰한 reasoning option snapshot.
 *
 * 목록 조회는 이후 Task 시작 수락을 보장하지 않으며, option은 Task 시작 직전에 다시
 * 확인한다.
 */
data class CodexReasoningOptionCatalog(
    val model: CodexModelIdentity,
    val options: List<CodexReasoningOptionDescriptor>,
    val defaultOptionId: CodexReasoningOptionId? = null,
) {
    init {
        require(options.map { it.id }.distinct().size == options.size) {
            "Codex reasoning option ids must be unique"
        }
        require(defaultOptionId == null || options.any { it.id == defaultOptionId }) {
            "Default Codex reasoning option must belong to the catalog"
        }
    }
}

/** Catalog의 canonical model과 option을 함께 보존하는 Task별 Codex 선택. */
data class CodexReasoningOptionSelection(
    val model: CodexModelId,
    val optionId: CodexReasoningOptionId,
)

/** 하나의 Codex Task 시작에만 적용할 adapter 전용 option. */
data class CodexTaskOptions(
    val reasoning: CodexReasoningOptionSelection? = null,
)

/**
 * Codex reasoning option catalog 조회 결과.
 *
 * [NotFound]는 현재 `model/list`에서 요청 model 또는 provider default model을 찾지 못했음을
 * 뜻한다. 임의의 model alias가 다른 model임을 증명하지는 않는다.
 */
sealed interface CodexReasoningOptionLookup {
    /** 현재 `model/list`에서 확인한 catalog snapshot. */
    data class Available(val catalog: CodexReasoningOptionCatalog) : CodexReasoningOptionLookup

    /**
     * 현재 `model/list`에서 catalog를 찾지 못했다.
     *
     * [requestedModel]이 `null`이면 provider default model을 요청한 조회였다.
     */
    data class NotFound(val requestedModel: String?) : CodexReasoningOptionLookup
}

/**
 * 같은 Codex 문맥에서 Codex 전용 Task option을 원자적으로 적용하는 typed extension.
 *
 * 공용 [TaskRequest]나 native client/RPC를 확장하지 않는다. [validate]와 [startTask]의
 * [CodexTaskOptions] overload를 사용해 해당 Task에만 option을 적용한다.
 */
interface CodexTaskSession : AgentSession {
    /** Task 요구와 Codex 전용 option의 현재 호환성을 진단한다. */
    fun validate(request: TaskRequest, options: CodexTaskOptions): CompatibilityReport

    /**
     * Codex 전용 option을 같은 native Task 시작 요청으로 전달하고 handle을 반환한다.
     *
     * reasoning option이 있으면 최신 catalog를 다시 조회해 model identity와 option을
     * 확인한다. 수락 확인은 provider가 선택 field를 포함한 Task 시작을 받았음만
     * 뜻하며, 실제 reasoning 품질·비용·지연이나 effective option을 독립적으로 보장하지 않는다.
     *
     * @throws IncompatibleRequirementException model 또는 option이 현재 catalog과 확인 가능하게 충돌할 때
     * @throws RequirementUnconfirmedException provider default model이나 model alias 관계를 확인하지 못할 때
     * @throws TaskStartUnconfirmedException 요청은 전달했으나 Task 시작 수락 여부를 확인하지 못할 때
     * @throws HarnessTransportException 요청을 전달하지 못했음이 확인됐을 때
     */
    suspend fun startTask(request: TaskRequest, options: CodexTaskOptions): AgentTask
}

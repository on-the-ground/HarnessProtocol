package dev.harnessprotocol

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 공통 요청 타입에 해석의 여지가 없는 escape 타입이 들어오지 않는지만 확인한다.
 *
 * 규범은 [docs/semantic-contract.md](../../../../../../../docs/semantic-contract.md)의
 * "provider 고유 기능의 출입 경계"이고, 그 기준은 값의 형태가 아니라 목적과 보장의 유무다.
 * 그 의미 규칙은 구조 검사로 증명할 수 없으므로 이 검사는 증명을 시도하지 않는다. 목적이
 * 분명한 타입은 `Map`이든 외부 값 타입이든 통과하고, 목적 없는 값이라도 이름을 바꾸거나
 * protocol package 안에 선언하면 이 검사는 통과한다. 그 판단은 검사가 아니라 리뷰가 한다.
 *
 * 여기서 막는 것은 두 가지뿐이다. `Any`는 어떤 값이든 담을 수 있어 요구의 의미를 지울 수
 * 있고, 원본 JSON 타입은 provider wire를 그대로 통과시킨다. 둘 다 목적을 가진 표현으로
 * 볼 수 있는 경우가 없다.
 */
class PortInboundEscapeTypeTest {

    /** 소비자가 하네스로 전달하는 요청 타입. 여기서 도달 가능한 전 타입이 대상이다. */
    private val inboundRoots = listOf(
        SessionSpec::class.java,
        SessionRequirements::class.java,
        TaskRequest::class.java,
        TaskRequirements::class.java,
    )

    @Test
    fun `inbound request types expose no untyped escape`() {
        val violations = mutableListOf<String>()
        val visited = mutableSetOf<Class<*>>()
        inboundRoots.forEach { walk(it, it.simpleName, visited, violations) }
        assertTrue(
            violations.isEmpty(),
            "공통 요청 타입은 Any나 원본 JSON을 담지 않는다. provider 고유 제어는 adapter가 " +
                "소유하는 구성에 두고 적용 scope를 밝힌다.\n" + violations.joinToString("\n") { "  - $it" },
        )
    }

    private fun walk(type: Class<*>, path: String, visited: MutableSet<Class<*>>, violations: MutableList<String>) {
        if (!visited.add(type)) return
        subtypesOf(type).forEach { walk(it, "$path:${it.simpleName}", visited, violations) }
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .forEach { inspect(it.genericType, "$path.${it.name}", visited, violations) }
    }

    private fun inspect(type: Type, path: String, visited: MutableSet<Class<*>>, violations: MutableList<String>) {
        when (type) {
            is Class<*> -> when {
                type == Any::class.java ->
                    violations += "$path: Any는 요구의 의미를 지운 채 값을 통과시킨다"
                type.name.startsWith(RAW_JSON_PACKAGE) ->
                    violations += "$path: 원본 JSON은 provider wire를 그대로 통과시킨다 (${type.name})"
                // protocol이 선언한 타입만 따라간다. 외부 값 타입은 목적이 있으면 허용한다.
                type.name.startsWith(PROTOCOL_PACKAGE) -> walk(type, path, visited, violations)
                else -> Unit
            }
            is ParameterizedType -> {
                (type.rawType as? Class<*>)?.let { inspect(it, path, visited, violations) }
                type.actualTypeArguments.forEach { inspect(it, "$path<>", visited, violations) }
            }
            is WildcardType -> type.upperBounds.forEach { inspect(it, path, visited, violations) }
            else -> Unit
        }
    }

    /** sealed 계층은 하위 타입이 값을 들고 있으므로 함께 따라간다. */
    private fun subtypesOf(type: Class<*>): List<Class<*>> =
        (type.permittedSubclasses?.toList().orEmpty() + type.declaredClasses.toList())
            .distinct()
            .filter { it != type && type.isAssignableFrom(it) }

    private companion object {
        const val PROTOCOL_PACKAGE = "dev.harnessprotocol"
        const val RAW_JSON_PACKAGE = "kotlinx.serialization.json"
    }
}

package dev.harnessprotocol

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 들어오는 요청 타입에 불투명한 통과 경로가 생기지 않도록 고정하는 회귀 검사.
 *
 * 규범은 [docs/semantic-contract.md](../../../../../../../docs/semantic-contract.md)의
 * "provider 고유 기능의 출입 경계"다. 나가는 방향의 provider 원본은 선택 계약인
 * `ProviderDiagnostic`으로 열려 있지만, 들어오는 방향은 닫혀 있다. 의미를 정하지 않은 값을
 * provider까지 전달하면 adapter는 이행 가능 여부를 판단하지 못한 채 요청을 수락하고 소비자는
 * 적용 여부를 확인할 수 없다. 이전 Port의 `metadata`가 그 사례이며 같은 형태로 되살아나지
 * 않도록 이 검사로 고정한다.
 *
 * provider 고유 제어가 필요하면 공통 요청 타입이 아니라 해당 adapter의 생성 옵션에 둔다.
 */
class PortInboundBoundaryTest {

    /** 소비자가 하네스로 전달하는 요청 타입. 여기서 도달 가능한 모든 타입이 검사 대상이다. */
    private val inboundRoots = listOf(
        SessionSpec::class.java,
        SessionRequirements::class.java,
        TaskRequest::class.java,
        TaskRequirements::class.java,
    )

    /** 불투명한 값 주머니임을 이름만으로 알 수 있는 property. 타입 검사로는 걸리지 않는다. */
    private val opaqueNames = setOf(
        "metadata", "extras", "passthrough", "additionalproperties",
        "rawpayload", "raw", "vendoroptions", "provideroptions",
        "nativeoptions", "customoptions",
    )

    private val boxed = setOf(
        java.lang.Boolean::class.java, java.lang.Byte::class.java, java.lang.Character::class.java,
        java.lang.Short::class.java, java.lang.Integer::class.java, java.lang.Long::class.java,
        java.lang.Float::class.java, java.lang.Double::class.java,
    )

    @Test
    fun `inbound request types carry no opaque container`() {
        val violations = mutableListOf<String>()
        val visited = mutableSetOf<Class<*>>()
        inboundRoots.forEach { walk(it, it.simpleName, visited, violations) }
        assertTrue(
            violations.isEmpty(),
            "공통 요청 타입은 의미가 정해진 타입만 담는다. provider 고유 설정은 adapter 생성 옵션에 둔다.\n" +
                violations.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `inbound request types carry no property named as an opaque bag`() {
        val offenders = mutableListOf<String>()
        val visited = mutableSetOf<Class<*>>()
        collectProperties(inboundRoots, visited).forEach { (owner, name) ->
            if (name.lowercase() in opaqueNames) offenders += "$owner.$name"
        }
        assertTrue(
            offenders.isEmpty(),
            "이름이 불투명한 값 주머니를 가리킨다. 목적이 있는 요구라면 그 목적으로 타입과 이름을 정의한다.\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    // --------------------------------------------------------------------------------- walk

    private fun walk(type: Class<*>, path: String, visited: MutableSet<Class<*>>, violations: MutableList<String>) {
        if (!visited.add(type)) return
        subtypesOf(type).forEach { walk(it, "$path:${it.simpleName}", visited, violations) }
        instanceFields(type).forEach { field ->
            inspect(field.genericType, "$path.${field.name}", visited, violations)
        }
    }

    private fun inspect(type: Type, path: String, visited: MutableSet<Class<*>>, violations: MutableList<String>) {
        when (type) {
            is Class<*> -> inspectClass(type, path, visited, violations)
            is ParameterizedType -> {
                val raw = type.rawType as? Class<*>
                if (raw == null) {
                    violations += "$path: 해석할 수 없는 타입 $type"
                    return
                }
                when {
                    Map::class.java.isAssignableFrom(raw) ->
                        violations += "$path: 임의 옵션 map은 통과 경로가 된다 (${raw.name})"
                    Collection::class.java.isAssignableFrom(raw) ->
                        type.actualTypeArguments.forEach { inspect(it, "$path[]", visited, violations) }
                    else -> {
                        inspectClass(raw, path, visited, violations)
                        type.actualTypeArguments.forEach { inspect(it, "$path<>", visited, violations) }
                    }
                }
            }
            is WildcardType -> type.upperBounds.forEach { inspect(it, path, visited, violations) }
            else -> violations += "$path: 해석할 수 없는 타입 $type"
        }
    }

    private fun inspectClass(type: Class<*>, path: String, visited: MutableSet<Class<*>>, violations: MutableList<String>) {
        when {
            type.isPrimitive || type in boxed || type == String::class.java -> Unit
            type.isEnum -> Unit
            Map::class.java.isAssignableFrom(type) ->
                violations += "$path: 임의 옵션 map은 통과 경로가 된다 (${type.name})"
            type == Any::class.java ->
                violations += "$path: Any는 의미가 정해지지 않은 값을 통과시킨다"
            type.name.startsWith("kotlinx.serialization") ->
                violations += "$path: 원본 JSON은 공통 요청 타입에 담지 않는다 (${type.name})"
            type.name.startsWith(PROTOCOL_PACKAGE) -> walk(type, path, visited, violations)
            else ->
                violations += "$path: 공통 요청 타입이 담을 수 없는 타입이다 (${type.name})"
        }
    }

    // ---------------------------------------------------------------------------- properties

    private fun collectProperties(
        roots: List<Class<*>>,
        visited: MutableSet<Class<*>>,
    ): List<Pair<String, String>> = buildList {
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!type.name.startsWith(PROTOCOL_PACKAGE) || !visited.add(type)) continue
            queue += subtypesOf(type)
            instanceFields(type).forEach { field ->
                add(type.simpleName to field.name)
                reachableClasses(field.genericType).forEach { queue += it }
            }
        }
    }

    private fun reachableClasses(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> listOf(type)
        is ParameterizedType ->
            listOfNotNull(type.rawType as? Class<*>) + type.actualTypeArguments.flatMap { reachableClasses(it) }
        is WildcardType -> type.upperBounds.flatMap { reachableClasses(it) }
        else -> emptyList()
    }

    // -------------------------------------------------------------------------------- shared

    /** sealed 계층은 하위 타입이 값을 들고 있으므로 함께 따라간다. */
    private fun subtypesOf(type: Class<*>): List<Class<*>> =
        (type.permittedSubclasses?.toList().orEmpty() + type.declaredClasses.toList())
            .distinct()
            .filter { it != type && type.isAssignableFrom(it) }

    private fun instanceFields(type: Class<*>) = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }

    private companion object {
        const val PROTOCOL_PACKAGE = "dev.harnessprotocol"
    }
}

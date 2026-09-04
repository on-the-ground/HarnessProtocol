package dev.harnessprotocol.conformance

import dev.harnessprotocol.AgentHarness
import dev.harnessprotocol.AgentSession
import dev.harnessprotocol.AgentTask
import dev.harnessprotocol.SessionSpec
import dev.harnessprotocol.TaskInput
import dev.harnessprotocol.TaskRequest
import dev.harnessprotocol.TaskRequirements
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 재사용할 conformance 시나리오의 배관. 실행 주체는 실제 adapter의 [fixture]다.
 * 임시 참조 엔진과 실행용 subclass는 제거했으며 이 추상 suite만으로 통과를 집계하지 않는다.
 *
 * `baseline` profile은 모든 [HarnessFixture] 구현이 제공해야 하는 정상 기본 작업 profile 이름의
 * 관례다 — [HarnessFixture.profiles]가 실제로 그 id를 선언하지 않으면 이 관례를 쓰는 검사가
 * 곧바로 실패한다.
 */
abstract class ConformanceTestSupport {
    protected abstract fun fixture(): HarnessFixture

    protected fun harnessFor(profileId: String = "baseline"): AgentHarness = fixture().createHarness(profileId)

    protected fun profile(profileId: String = "baseline") = fixture().profiles().first { it.id == profileId }

    protected suspend fun AgentHarness.session(spec: SessionSpec = SessionSpec()): AgentSession = createSession(spec)

    protected fun textRequest(text: String = "hello", requirements: TaskRequirements = TaskRequirements()) =
        TaskRequest(TaskInput.Text(text), requirements)

    protected suspend fun AgentSession.start(text: String = "hello"): AgentTask = startTask(textRequest(text))

    protected fun AgentTask.control(): TaskControl = fixture().control(this)

    protected suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }
}

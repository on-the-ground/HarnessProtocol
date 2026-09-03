# Harness Protocol

Codex와 Gemini CLI를 같은 모양으로 감싸는 라이브러리가 아니라, 두 agent harness가 공통으로 수행하는 **목적**을 Kotlin 포트로 정의하고 각 SDK를 어댑터로 연결하는 프로젝트다.

핵심 모델은 다음과 같다.

```text
AgentHarness ── creates/resumes ──> AgentSession ── release
                                      │
                                      └── executes ──> AgentExecution
                                                           │
                                                           ├── events
                                                           ├── pendingInteractions / respond
                                                           ├── cancel
                                                           └── result (stopReason, usage)
```

포트는 vendor의 `thread`, `turn`, `sendStream` 같은 수단을 노출하지 않는다. 대신 “대화를 이어간다”, “한 번의 agent loop를 수행한다”, “그 과정과 외부 효과를 관찰한다”, “승인에 답한다”, “중단한다”라는 목적을 드러낸다.

## 모듈

- `harness-protocol`: provider-neutral 포트, 값 타입, 이벤트, 호환성 계약
- `harness-process-bridge`: Kotlin과 SDK host process 사이의 NDJSON transport와 공통 실행 lifecycle
- `harness-codex`: `openai-codex` Python SDK 어댑터
- `harness-gemini-cli`: Gemini CLI TypeScript SDK 어댑터
- `harness-bundle`: 소비 프로젝트가 받는 단일 Maven 의존성과 provider factory
- `harness-adapter-testkit`: adapter가 상속하는 공유 contract test (배포하지 않음)
- `bridges`: SDK를 실제로 호출하는 얇은 Python/Node host와 그 테스트

## 문서

처음 사용하는 소비자는 다음 순서로 읽는 것을 권장한다.

1. [Protocol reference](docs/protocol-reference.md): 공개 interface와 모델, 필드, validation 및 오류 계약
2. [Event contract](docs/event-contract.md): 이벤트 순서, 메시지 조립, tool/effect, usage 및 terminal 규칙
3. [Lifecycle and concurrency](docs/lifecycle-and-concurrency.md): 소유권, session 순차성, 취소, close 및 coroutine 패턴

설계 판단과 구현 정보는 다음 문서에서 이어진다.

- [Semantic contract](docs/semantic-contract.md): 공통 목적을 기본 port에 넣는 기준
- [Capability candidates](docs/capability-candidates.md): 포트에 넣지 않은 목적과 재검토 조건
- [Provider mapping](docs/provider-mapping.md): Codex와 Gemini CLI의 의미 대응, 정책·실패 분류
- [Bridge protocol](docs/bridge-protocol.md): Kotlin과 SDK host 사이의 NDJSON 계약
- [Testing](docs/testing.md): contract test 상속과 host test 실행
- [Distribution](docs/distribution.md): Maven 발행과 SDK runtime 경계
- [Port revision plan](docs/port-revision-plan.md): 0.1.0 전 포트 개정 계획과 구현 기록
- [`codex-agent` adoption review](docs/codex-agent-adoption-review.md): Kotlin App Server host의 향후 채택 조건과 재검토 절차

## 다른 프로젝트에서 사용

```kotlin
dependencies {
    implementation("dev.harnessprotocol:harness-bundle:0.1.0-SNAPSHOT")
}
```

소비 코드는 SDK 대신 factory에서 공통 port만 받는다.

```kotlin
val harness: AgentHarness = Harnesses.create(configuration.provider)
```

독립 소비 프로젝트 예제는 [samples/basic](samples/basic)에 있다.

## Kotlin 사용 예

```kotlin
val harness: AgentHarness = Harnesses.codex()

harness.use {
    val spec = AgentSpec(
        instructions = "Work carefully and keep tests green.",
        workingDirectory = Path.of(".").toAbsolutePath().toString(),
        executionPolicy = ExecutionPolicy(
            filesystem = FilesystemAccess.WorkspaceWrite(),
            network = NetworkAccess.DENIED,
            approval = ApprovalPolicy.AGENT_REVIEWED,
        ),
    )

    // 지원하지 않는 의미를 조용히 무시하지 않는다.
    harness.validate(spec).requireCompatible()

    val session = harness.createSession(spec)
    val execution = session.execute(AgentInput.Text("Run the tests and fix failures."))

    val observer = launch { execution.events.collect { event -> println(event) } }
    val result = try {
        execution.awaitResult()
    } finally {
        observer.cancel()
    }
    println("${result.stopReason}: ${result.finalMessage}")
}
```

같은 호출 코드는 `GeminiCliHarness`에도 적용된다. 다만 현재 Gemini CLI SDK가 policy/approval 구성을 노출하지 않으므로 해당 의미를 요청하면 `validate`가 명시적으로 거절한다.

승인을 caller가 직접 결정하려면 `ApprovalPolicy.CALLER_DECIDES`를 요청하고 `execution.pendingInteractions`를 관찰해 `execution.respond(...)`로 답한다. 실행은 그동안 `ExecutionState.WAITING`이다.

## SDK host 준비

Codex bridge:

```powershell
python -m venv .venv
.venv\Scripts\python -m pip install -r bridges\requirements-codex.txt
```

Gemini CLI SDK는 2026-09-03 기준 공식 저장소에는 구현되어 있으나 npm의 공개 안정 패키지로 배포되지 않았다. 공식 저장소를 빌드한 뒤 SDK 진입점 경로를 지정한다.

```kotlin
Harnesses.geminiCli(
    GeminiCliSdkOptions(
        sdkModule = "C:/src/gemini-cli/packages/sdk/dist/index.js",
    ),
)
```

## 검증

```powershell
.\gradlew.bat test                      # Kotlin contract/mapper/lifecycle tests
.\gradlew.bat hostTests                 # Python(pytest) + Node(--test) host tests
.\gradlew.bat check -PstrictHostTests   # 릴리스 검증: interpreter 부재를 실패로
```

host test는 `.venv\Scripts\python -m pip install -r bridges\requirements-codex.txt pytest`가 선행되어야 한다. 자세한 구성은 [docs/testing.md](docs/testing.md)에 있다.

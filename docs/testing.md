# Testing

## 구성

| 위치 | 내용 |
|---|---|
| `harness-adapter-testkit` | 배포하지 않는 test-only 모듈. `RecordingBridge`(fake host), `AgentHarnessContractTest`(공유 contract), `SpecSpace`/`IntentProjection`(의도 투영), `ProviderFixture` |
| `harness-codex/src/test`, `harness-gemini-cli/src/test` | contract test 상속 + provider별 mapper test + (Codex) interaction test |
| `harness-process-bridge/src/test` | 실제 Python host process로 process death/EOF/close를 검증하는 `ProcessLifecycleTest` |
| `bridges/tests` | `test_codex_bridge.py`(pytest; 실제 `CodexClient` + stub App Server), `gemini_bridge.test.mjs`(node --test) |

## 실행

```powershell
.\gradlew.bat test              # Kotlin
.\gradlew.bat hostTests         # Python/Node host tests (interpreter 없으면 경고 후 skip)
.\gradlew.bat check -PstrictHostTests   # CI/release: interpreter 부재를 실패로 처리
```

host test 사전 조건: `pip install -r bridges\requirements-codex.txt pytest`, Node 20+.

## 새 adapter가 지켜야 하는 것

1. `AgentHarnessContractTest`를 상속하고 `harness(bridge, scope)`, `projection()`, `fixture()`를 구현한다.
2. `projection()`은 adapter의 변환 코드를 호출하지 않는 **독립 선언**이어야 한다. `SpecSpace.all()`의 모든 spec에 대해 "validate 통과 → bridge JSON이 의도를 그대로 담음 / 거절 → 요청 0건"이 검사된다.
3. `AgentSpec`에 필드를 추가하면 같은 커밋에서 `SpecSpace`의 축과 각 adapter의 projection 규칙을 추가한다. 열거되지 않은 필드는 보호되지 않는다.
4. lifecycle contract(직렬화, 취소 경쟁, 느린 collector, 이벤트 유실 없이 terminal, process 종료, release)는 상속만으로 적용된다.

## 8b spike 상태

`bridges/tests/test_codex_bridge.py`의 end-to-end 테스트는 실제 `openai_codex.client.CodexClient`를 `bridges/tests/stub_app_server.py`에 붙여 approval round-trip과 "handler unblock → interrupt" 순서를 검증한다. 실제 App Server의 `requestApproval` payload와 결정 문자열은 로그인된 환경에서 캡처해 fixture로 추가해야 한다.

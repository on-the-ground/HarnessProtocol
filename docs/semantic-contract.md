# Semantic contract

## 포트에 넣는 기준

어떤 개념을 기본 포트에 넣으려면 다음 다섯 질문에 모두 "예"여야 한다.

1. **provider-neutral 목적인가.** 호출자가 달성하려는 것이 특정 SDK의 수단이 아니라 "agent를 이렇게 운용하고 싶다"는 의도로 서술되는가.
2. **core lifecycle/control-plane 목적인가.** session을 열고, agent loop를 실행하고, 관찰·개입·종료·재개하는 모든 harness가 본질적으로 다뤄야 하는가.
3. **최소 한 어댑터가 공개·지원 API로 그 의미를 검증 가능하게 보존하는가.** wire protocol이나 제품 UI에만 있고 현재 adapter SDK로 구현하지 못하면 아직 public port를 확정하지 않는다.
4. **결과와 진행을 공통 의미로 관찰할 수 있는가.**
5. **보존하지 못하는 어댑터는 실행 전에 거절할 수 있는가.** `validate`가 ERROR를 낼 수 있어야 한다. 조용한 downgrade가 유일한 선택지라면 포트에 넣지 않는다.

기준 3은 "모든 어댑터"가 아니다. 지원이 비대칭인 core 목적은 포트에 두고 어댑터가 거절한다. 그래야 provider가 기능을 추가했을 때 포트가 아니라 어댑터의 validate만 넓어진다.

### 포트에 넣지 않는 것

- SDK 메서드 모양이 같다는 이유만으로 넣지 않는다.
- provider-neutral이라는 이유만으로 넣지 않는다. host tool 등록, structured output처럼 embedding surface를 넓히는 선택 기능은 capability다.
- 특정 provider의 대화 관리·확장 메커니즘(fork, archive, MCP 관리)은 capability다.

### 기준 적용 기록

| 개념 | provider-neutral | core | 구현 증명 | 관찰 | 거절 | 결정 |
|---|---|---|---|---|---|---|
| `ExecutionPolicy` | 예 | 예 | Codex | 예 | Gemini 거절 | 포트 |
| session runtime release (`AgentSession.release`) | 예 | 예 | 양쪽 host | 예 | 해당 없음 | 포트 |
| `StopReason`, `FailureKind` | 예 | 예 | 양쪽 | 예 | 해당 없음 | 포트 |
| caller approval (`CALLER_DECIDES`, interaction) | 예 | 예 | Codex 저수준 client spike 필요 | 예 | Gemini 거절 | spike 성공 시 포트 |
| caller-selected context token ceiling | 예 | 예 | **없음** | — | — | 제외. [capability-candidates.md](capability-candidates.md) |
| host-defined custom tool | 예 | 아니오 (embedding 확장) | Gemini | 예 | Codex 거절 가능 | capability |
| structured output | 예 | 경계선 | Codex | 예 | Gemini 거절 가능 | capability, 다음 minor 재검토 |
| steering | 예 | 필수 lifecycle 아님 | Codex | 예 | Gemini 거절 가능 | capability |
| session history 조회 | 예 | 필수 lifecycle 아님 | 확인 필요 | 예 | 거절 가능 | capability 후보 |

## 기본 포트가 보장하는 목적

### AgentHarness

- agent의 지속적인 실행 환경을 연다.
- 원하는 구성 의미를 어댑터가 보존할 수 있는지 `validate`로 확인한다.
- 새 대화를 시작하거나 기존 대화를 다시 연다.

### AgentSession

- 여러 실행 사이에서 대화 문맥을 유지한다.
- 하나의 입력으로 완결된 agent loop를 시작한다. 이전 loop가 끝나기 전의 시작은 거절한다.
- 같은 세션에서 다음 입력을 실행해 이전 결과를 이어간다.
- runtime handle을 해제한다. durable conversation은 남는다.

### AgentExecution

- 실행 상태를 관찰한다.
- 텍스트, 추론 요약, 도구 작업, 명령/파일/검색 효과, 문맥 관리, 사용량을 event로 관찰한다.
- caller의 결정이 필요할 때 멈추고(`WAITING`) 응답을 받는다.
- 실행을 중단한다.
- 최종 메시지, 종료 사유, 사용량을 결과로 받는다. 실패는 분류된 종류와 함께 받는다.

### AgentSpec

- instructions, model, working directory, skills를 선언한다.
- filesystem/network/approval의 **의도**를 선언한다.
- 어댑터가 그 의도를 구현할 수 없으면 `CompatibilityReport`로 거절한다. provider 기본값으로 몰래 낮추는 것은 계약 위반이다. `PROVIDER_DEFAULT`는 provider/runtime에 이미 설정된 기본값이지 adapter SDK의 convenience default가 아니다.

## 이벤트의 의미

`ToolCallChanged`는 agent가 수행하는 이름 있는 작업의 lifecycle이다. `EffectChanged`는 호출자가 특별히 관심을 갖는 외부 효과(command, file change, web search 등)다. 하나의 provider 이벤트가 둘 중 하나 또는 둘 모두가 될 수 있다.

공통 이벤트로 손실 없이 번역할 수 없는 새 vendor 이벤트는 `ProviderEventObserved`에도 실어 보낸다. 이것은 관찰과 진단을 위한 탈출구이지 portable business logic을 작성하는 API가 아니다.

이벤트 mapper는 vendor의 delta를 가능한 그대로 전달하고, 완료 이벤트를 최종 상태로 취급한다. 실행은 `ExecutionCompleted`, `ExecutionFailed`, `ExecutionCancelled` 중 하나로 끝난다. 이벤트 전달은 관찰 통로이고 lifecycle의 전제가 아니다. 느린 collector는 `ObservationGap`을 받지만 `state`와 `awaitResult()`는 영향을 받지 않는다.

## capability와의 경계

다음은 기본 포트의 목적이 아니다. 근거와 재검토 조건은 [capability-candidates.md](capability-candidates.md)에 있다.

- Codex 전용 thread list/archive/fork/name/goal/review/steer
- 구조화 출력, vendor 고유 입력 및 UI 요청
- 특정 provider의 MCP/app/extension 관리
- SDK가 한쪽에만 제공하는 host callback 도구 등록
- 사용자 질문 interaction, caller-selected context token ceiling

이들은 향후 별도 capability 인터페이스로 추가할 수 있다. capability는 `AgentHarness`를 쪼갠 대체물이 아니라, 공통 계약 위에 선택적으로 얹는 확장이다.

중요한 구분은 “도구를 사용해 일을 수행한다”와 “Kotlin 함수를 SDK의 custom tool로 등록한다”가 같지 않다는 점이다. 전자는 두 agent의 기본 목적이며 `ToolCallChanged`/`EffectChanged`로 포트화했다. 후자는 embedding 확장 메커니즘이고, 현재 공개 SDK에서는 Gemini CLI만 직접 지원하므로 기본 계약에 포함하지 않았다. Codex App Server의 실험적 `dynamicTools`를 사용하는 별도 capability가 안정화되면 두 구현을 같은 확장 포트 아래 둘 수 있다.

## 호환성 원칙

- `validate`는 의미 보존 가능성을 실행 전에 판정한다.
- `createSession`과 `resumeSession`은 내부적으로 다시 검증한다.
- 거절은 기능 부재를 숨기는 fallback보다 낫다.
- provider가 새 기능을 지원하게 되면 포트를 바꾸기보다 어댑터의 검증과 번역을 넓힌다.

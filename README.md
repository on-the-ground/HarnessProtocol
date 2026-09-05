# Agent Harness Protocol

AHP는 비즈니스 애플리케이션이 에이전트 하네스에 **작업을 위임하고, 필요한 조건을 요구하고, 진행 중 판단에 참여하며, 결과와 종료 상태를 받아 후속 행동을 결정하는 Port**다.

하네스는 adapter를 통해 비즈니스 경계 밖에 둔다. 로컬 라이브러리, 임베딩한 agent process, 외부 서버, 클라우드 서비스는 같은 목적을 제공하는 서로 다른 구현 방식이다. 내부 모델 호출, 실행 그래프, 도구 구성, 저장소와 transport는 하네스 제공 경계가 책임진다.

최상위 기준은 [AHP 설계 선언](AHP_CHARTER.md)이다. 기존 구현과 API를 이 기준에 맞춘다. 구현의 편의나 SDK 기능의 교집합이 추상의 기준이 되지 않는다.

## 작업과 문맥, 결과

```text
비즈니스 애플리케이션
    │ 작업 위임과 요구 조건
    ▼
AgentHarness                   하네스 제공 경계
    └─ AgentSession            여러 작업이 문맥을 공유하는 범위
          └─ startTask → AgentTask
                            ├─ state / events
                            ├─ pendingInteractions / respond
                            ├─ requestCancellation
                            └─ awaitOutcome → TaskOutcome
                                               └─ TaskOutput: 전달된 산출물
```

- `AgentTask`는 한 번 위임한 작업이다. provider의 turn, 모델 호출, graph node와 일대일 대응하지 않는다.
- `AgentSession`은 문맥 연속성을 제공한다. 영속 보관과 `reopenSession`은 별도로 요구하는 선택 계약이며 checkpoint 복구와 구별한다.
- 승인과 질문은 외부 응답을 기다리는 interaction이다. 승인 결정과 정보 답변의 의미를 각각 유지한다.
- `TaskOutcome`은 `Completed`, `Failed`, `Cancelled`, `Unresolved`라는 종결 판정을 구별한다. 실행 완료는 업무 목표 달성을 증명하지 않는다.
- `TaskOutput`은 산출물이다. 텍스트 전달과 구조화된 산출물의 schema 보장은 구별한다.
- 모든 outcome에서 그 시점까지 확보한 산출물과 사용량을 회수한다. 부분 결과와 unknown을 보존하며 이벤트 구독 여부에 의존하지 않는다.
- 취소 요청이나 handle 정리만으로 실제 중단을 확정하지 않는다. 종료 결과를 확인하지 못하면 `Unresolved`를 전달한다.

## 문서와 구현 상태

**Codex·Gemini CLI·Koog adapter와 `Harnesses` factory는 새 `dev.harnessprotocol` Port를 사용한다.** 임시 참조 하네스와 legacy 실행 경로는 제거했다. 기본 연결 뒤 요구 수락·확인 유실·상호작용 경쟁·문맥 공유·부분 산출물·정리·관찰 부하·사용량·저장 장애를 실제 runtime에서 검증했다. 문맥을 다시 연 핸들의 자원 수명과 설정 보존 결함도 수정했다.

현재 진행과 적용 한계는 [G03–G12 계약 경계 검증](docs/contract-boundary-validation.md)을 따른다. skill 활성화는 실제 본문 적용 보장으로, `ExecutionConstraint.Required`는 승인이 넓힐 수 없는 상한으로 확정했다. Codex·Gemini는 active skill을 적용하고, Codex는 집행 가능한 제한 조합만 수락하며, 나머지 구성은 이행할 수 없는 요구를 작업 전에 거절한다. [기본 adapter 검증](docs/native-port-validation.md), [요구 사례 검증](docs/requirement-admission-validation.md), [수락 확인 유실](docs/acceptance-loss-validation.md)은 각 단계의 실행 기록이다. 공개 의미는 [공개 모델](docs/public-model.md)을 따른다. 통제된 모델을 사용한 실제 runtime 검증과 외부 실모델 검증은 구별한다.

| 읽는 순서       | 문서                                                                                                                                                        |
|-------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Quickstart  | [FAQ for newcommers](docs/FAQ.md)                                                                                                                         |
| 목적과 판단 기준   | [설계 선언](AHP_CHARTER.md), [Semantic contract](docs/semantic-contract.md)                                                                                   |
| 개념과 이름      | [추상 개정과 용어](docs/abstraction-and-terminology.md)                                                                                                          |
| 동작 계약       | [Protocol reference](docs/protocol-reference.md), [Lifecycle](docs/lifecycle-and-concurrency.md), [Event contract](docs/event-contract.md)                |
| 추가로 요구할 보장  | [선택 계약과 검토 항목](docs/capability-candidates.md)                                                                                                             |
| 구현 전환       | [공개 모델](docs/public-model.md), [Port revision plan](docs/port-revision-plan.md), [Provider mapping](docs/provider-mapping.md), [Testing](docs/testing.md) |
| 배포와 구현 세부   | [Distribution](docs/distribution.md), [Bridge protocol](docs/bridge-protocol.md)                                                                          |
| 실증 근거       | [Koog 검증 계획](docs/koog-abstraction-validation-plan.md), [검증 결과](docs/koog-abstraction-validation-results.md)                                              |
| 회귀 검토       | [문서 개편 후 회귀 검토](docs/regression-review.md): 복원한 보장, 새 설계의 경계 조건, 기존 suite 재실행 결과                                                                          |
| 총평 반영       | [채택·수정·보류 판단](docs/review-disposition.md): 종결 증거, 문맥 조정, 지원 탐색, 이벤트 존치와 남은 검증                                                                             |
| Codex 기반 선택 | [codex-agent 검토](docs/codex-agent-adoption-review.md), [저수준 client 조사](docs/spikes/2026-09-03-codex-low-level-client.md)                                  |

공개 Port와 현재 세 adapter의 구성별 계약 검증은 G12까지 연결돼 있다. 현재 구성에서 제공하지 않는 선택 기능의 성공 경로와 외부 실모델·artifact 발행은 별도 범위이며, 기존 설계 이력은 Git에서 확인한다.

## 구현 구성

| 위치 | 역할과 전환 상태 |
|---|---|
| `harness-protocol` | `dev.harnessprotocol`에 확정된 공개 Port. 모든 adapter·회귀 검사·독립 실험이 이 Port를 사용하며 이전 패키지는 제거했다. |
| `harness-codex`, `harness-gemini-cli` | 새 Port를 실제 native SDK에 연결한다. 기존 회귀도 현재 구현을 검증하며 별도 구 구현은 없다. |
| `harness-koog` | 실제 Koog graph·ToolRegistry를 직접 연결하는 새 Port adapter. 모델 executor는 구성 경계에서 제공한다. |
| `harness-runtime` | adapter가 선택하여 사용하는 Task 수명·관찰 구현. 공개 Port의 필수 기반이 아니다. |
| `harness-process-bridge`, `bridges` | 두 process adapter의 내부 transport와 host. 모든 하네스의 필수 기반이 아니다. |
| `harness-adapter-testkit` | 현재 process adapter의 공통 회귀, 독립적인 설정 투영, SDK 이벤트 매핑 검사. |
| `harness-conformance` | 목적별 공통 계약 판정과 작은 fixture seam. 원래 48개 C/K 목록은 실행 코드가 아니라 처리 근거를 보존한 역사적 inventory다. [시나리오 대응표](docs/conformance-scenarios.md)를 따른다. |
| `harness-bundle` | Codex·Gemini·Koog adapter 구성 편의. Koog의 executor·model을 명시적으로 받는다. |
| `harness-native-integration` | 세 실제 runtime과 통제된 모델 경계로 공개 동작을 검증한다. `-PnativeHarnessTests`로 실행한다. |
| `experiments/koog-validation` | 현재 Port를 사용하는 별도 Koog 승인·질문·파일 보관 구성의 격리 실험. production 기본 구성과 구별한다. |

Koog 실험 18개와 기존 회귀 71개가 통과한 [기록](experiments/koog-validation/evidence/verification.json)은 기존 계약을 대상으로 한 증거다. 현재 실행 결과는 [native 검증](docs/native-port-validation.md)과 [legacy 이전 검토](docs/legacy-port-migration.md)에 별도로 기록한다. 실모델 검증을 뜻하지 않는다.

## 현재 구현을 빌드·검증하기

저장소의 JDK 설정에 맞춰 `JAVA_HOME`을 지정한 뒤 실행한다. 다음 명령은 현재 구현용이다.

```powershell
./gradlew.bat test
./gradlew.bat hostTests
./gradlew.bat check -PstrictHostTests
./gradlew.bat test hostTests -PnativeHarnessTests -PstrictHostTests
```

Host 준비와 검증 범위는 [Testing](docs/testing.md), native runtime 준비·결과는 [실제 adapter 검증](docs/native-port-validation.md), artifact 구성은 [Distribution](docs/distribution.md)에 있다. [samples/basic](samples/basic)은 새 factory의 소비 예제다. 이번 작업에서는 artifact를 발행하지 않았다.

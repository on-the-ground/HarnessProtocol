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

Codex·Gemini CLI·Koog adapter와 `Harnesses` factory는 `dev.harnessprotocol` Port를 사용한다. skill 활성화는 실제 본문 적용 보장이고 `ExecutionConstraint.Required`는 승인이 넓힐 수 없는 상한이다. 각 adapter는 이행할 수 없는 요구를 작업 전에 거절한다.

| 목적 | 문서 |
|---|---|
| 시작 | [FAQ](docs/FAQ.md) |
| 판단 기준 | [설계 선언](AHP_CHARTER.md), [Semantic contract](docs/semantic-contract.md), [추상과 용어](docs/abstraction-and-terminology.md) |
| 공개 계약 | [Protocol reference](docs/protocol-reference.md), [Lifecycle](docs/lifecycle-and-concurrency.md), [Event contract](docs/event-contract.md) |
| 공개 모델 | [공개 모델](docs/public-model.md), [선택 계약](docs/capability-candidates.md) |
| 구현 | [Provider mapping](docs/provider-mapping.md), [Bridge protocol](docs/bridge-protocol.md), [Distribution](docs/distribution.md) |
| 검증 | [Testing](docs/testing.md), [G01–G12 결과](docs/contract-boundary-validation.md), [시나리오 대응표](docs/conformance-scenarios.md) |

최종 통합 회귀에서 JVM 292개(세 native runtime 검사 210개 포함)와 host 21개가 실패·오류·건너뜀 없이 통과했다. [최종 집계](verification/native-integration/evidence/final-regression-summary.json)와 [native 실행 기록](verification/native-integration/evidence/final-g01-g12.json)을 보존한다. 외부 실모델 호출, 현재 구성이 거절하는 선택 기능의 성공 경로와 artifact 발행은 이 검증 범위에 포함하지 않는다.

## 구현 구성

| 위치 | 역할 |
|---|---|
| `protocol/core` | provider와 구현 방식에 독립적인 공개 Port |
| `protocol/conformance` | 목적별 공통 계약 판정과 fixture seam |
| `implementations/codex` | Codex adapter와 Python host |
| `implementations/gemini-cli` | Gemini CLI adapter와 Node host |
| `implementations/koog` | Koog graph adapter |
| `implementations/shared` | adapter가 선택해 쓰는 Task runtime과 process transport |
| `implementations/bundle` | Codex·Gemini·Koog adapter 구성 편의. Koog의 executor·model을 명시적으로 받는다. |
| `verification` | adapter SDK 회귀와 세 실제 runtime의 공통 계약 검증 |
| `samples` | 공개 artifact 소비 예제 |

## 현재 구현을 빌드·검증하기

저장소의 JDK 설정에 맞춰 `JAVA_HOME`을 지정한 뒤 실행한다. 다음 명령은 현재 구현용이다.

```powershell
./gradlew.bat test
./gradlew.bat hostTests
./gradlew.bat check -PstrictHostTests
./gradlew.bat test hostTests -PnativeHarnessTests -PstrictHostTests
```

Host 준비와 검증 범위는 [Testing](docs/testing.md), 결과는 [G01–G12 검증](docs/contract-boundary-validation.md), artifact 구성은 [Distribution](docs/distribution.md)에 있다. [samples/basic](samples/basic)은 factory 소비 예제다.

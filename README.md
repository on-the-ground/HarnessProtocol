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
- 취소 요청이나 handle 정리만으로 실제 중단을 확정하지 않는다. 종료 결과를 확인하지 못하면 `Unresolved`를 전달한다.

## 문서와 구현 상태

**README와 `docs/`의 계약 설명은 개정된 설계 기준이다. 코드와 KDoc은 아직 기존 API를 사용하며 후속 구현에서 함께 전환한다.** 새 명칭을 사용하는 예시는 설계 예시다. 현재 artifact에서 그대로 컴파일되는 사용 예로 제시하지 않는다.

| 읽는 순서 | 문서 |
|---|---|
| 목적과 판단 기준 | [설계 선언](AHP_CHARTER.md), [Semantic contract](docs/semantic-contract.md) |
| 개념과 이름 | [추상 개정과 용어](docs/abstraction-and-terminology.md) |
| 동작 계약 | [Protocol reference](docs/protocol-reference.md), [Lifecycle](docs/lifecycle-and-concurrency.md), [Event contract](docs/event-contract.md) |
| 추가로 요구할 보장 | [선택 계약과 검토 항목](docs/capability-candidates.md) |
| 구현 전환 | [Port revision plan](docs/port-revision-plan.md), [Provider mapping](docs/provider-mapping.md), [Testing](docs/testing.md) |
| 배포와 구현 세부 | [Distribution](docs/distribution.md), [Bridge protocol](docs/bridge-protocol.md) |
| 실증 근거 | [Koog 검증 계획](docs/koog-abstraction-validation-plan.md), [검증 결과](docs/koog-abstraction-validation-results.md) |
| 회귀 검토 | [문서 개편 후 회귀 검토](docs/regression-review.md): 복원한 보장, 새 설계의 경계 조건, 기존 suite 재실행 결과 |
| Codex 기반 선택 | [codex-agent 검토](docs/codex-agent-adoption-review.md), [저수준 client 조사](docs/spikes/2026-09-03-codex-low-level-client.md) |

확정된 의미와 아직 정해야 할 공개 필드·선택 계약 API는 문서에서 구분한다. 기존 버전의 설계 이력은 Git에서 확인한다.

## 구현 구성

| 위치 | 역할과 전환 상태 |
|---|---|
| `harness-protocol` | 공개 Port와 값 타입. 기존 이름과 결과 모델을 개정해야 한다. |
| `harness-codex`, `harness-gemini-cli` | 기존 provider adapter. 새 필수 계약과 지원하는 선택 계약에 맞춰 수정한다. |
| `harness-process-bridge`, `bridges` | 두 process adapter의 내부 transport와 host. 모든 하네스의 필수 기반이 아니다. |
| `harness-adapter-testkit` | 기존 bridge 기반 검사. 공개 Port의 행동 검사와 구현별 투영 검사를 분리한다. |
| `harness-bundle` | 현재 adapter 선택·배포 편의 모듈. Koog 실험은 아직 포함하지 않는다. |
| `experiments/koog-validation` | 실제 Koog 런타임과 통제된 모델 응답을 사용한 격리 실험. 새 계약에 적합한 production adapter는 아니다. |

Koog 실험 18개와 기존 회귀 71개가 통과한 [기록](experiments/koog-validation/evidence/verification.json)은 기존 계약을 대상으로 한 증거다. 개정 계약의 세 adapter 통합 통과나 실모델 검증을 뜻하지 않는다.

## 현재 구현을 빌드·검증하기

저장소의 JDK 설정에 맞춰 `JAVA_HOME`을 지정한 뒤 실행한다. 다음 명령은 현재 구현용이다.

```powershell
./gradlew.bat test
./gradlew.bat hostTests
./gradlew.bat check -PstrictHostTests
```

Host 준비와 검증 범위는 [Testing](docs/testing.md), 현재 artifact와 실행 환경 요구는 [Distribution](docs/distribution.md)에 있다. [samples/basic](samples/basic)은 기존 API의 소비 예제이며 구현 전환 때 함께 갱신한다.

# Koog 추상 검증 결과와 설계 반영

검증일: 2026-09-04. 실험 대상: AHP 0.1.0, revision `d5733031a2533dfd0d56821d912442a08552b0fd`. 설계 판단은 [설계 선언](../AHP_CHARTER.md), [Semantic contract](semantic-contract.md), [추상과 용어](abstraction-and-terminology.md)를 따른다.

**이 문서의 관찰 기록은 당시 실행한 사실이고, 설계 결론은 후속 합의한 개정 방향이다.** 현재 코드에서 구현할 수 있다는 사실과 모든 하네스에 기본 계약으로 요구해야 한다는 판단을 구별한다.

## 결론

실제 Koog graph runtime으로 작업 위임·개입·문맥 연속성·결과·취소 목적을 검증했다. 공통 목적을 내부 graph node나 SDK 표면에 맞출 필요는 없었다. 동시에 기존 계약에 함께 묶인 책임을 분리할 근거를 얻었다.

- 작업은 provider turn보다 큰 업무 위임 단위이며 새 이름은 AgentTask다.
- 기본 문맥 연속성과 영속 보관은 다른 요구다. adapter에 저장소를 붙일 수 있다는 사실로 영속성을 core에 강제하지 않는다.
- 승인과 질문은 공통 interaction lifecycle을 가지지만 답변 의미가 다르다.
- close 유예 초과는 실제 중단의 증거가 아니다. 종료 미확정을 표현하고 handle을 bounded하게 정리해야 한다.
- 실행 종결 판정과 업무 산출물은 TaskOutcome/TaskOutput으로 구별한다.
- 공통 행동은 SdkBridge 없이 검증할 수 있으며 원본 진단은 별도 경로로 둔다.

이 결론을 반영한 공개 Port·KDoc과 세 adapter 연결은 완료했다. 아래 표는 이전 실험의 관찰을 보존한 것이며 새 코드의 결과는 [native 검증 기록](native-port-validation.md)에서 확인한다.

## 환경과 실제 검증

| 항목 | 기록 |
|---|---|
| Koog artifact | agents-core, agents-features-event-handler, agents-features-snapshot 1.2.0 |
| 빌드 환경 | Kotlin 2.3.10, Gradle wrapper 9.1.0, Corretto 25.0.3, JVM target 21 |
| 의존성 | [독립 build](../experiments/koog-validation/build.gradle.kts), [lockfile](../experiments/koog-validation/gradle.lockfile) |
| 실제 runtime | graph, singleRunStrategy, tool registry·호출, event hooks, coroutine 취소, 파일 checkpoint |
| 통제한 경계 | ScriptedExecutor의 모델 응답. 실제 외부 모델 호출 0회, executor당 fixture 요청 최대 12회 |
| 업무 효과 | 합성 요청 R-1, 조회와 상태 변경 카운터. 외부 업무 시스템 변경 없음 |
| 실험 검사 | native 4개 + Port 시나리오 14개 = 18개, 실패·오류·skip 0 |
| 기존 회귀 | 71개, 실패·오류·skip 0 |

실행한 테스트 이름·관찰 로그·소스 hash는 [verification.json](../experiments/koog-validation/evidence/verification.json), 당시 소스는 기록의 Git revision에서 확인한다. 실행 가능한 실험은 이후 현재 Port로 이전했다. [실험 README](../experiments/koog-validation/README.md)와 [현재 결과](../experiments/koog-validation/evidence/current-port-migration.json)는 새 단계의 증거이며 위 과거 기록을 덮어쓰지 않는다.

공식 배포본은 [Maven Central 1.2.0](https://repo.maven.apache.org/maven2/ai/koog/agents-core-jvm/1.2.0/)이며 실제 source JAR와 동작을 대조했다. 참고 자료는 [graph agents](https://docs.koog.ai/agents/graph-based-agents/), [persistence](https://docs.koog.ai/features/agent-persistence/), [event handlers](https://docs.koog.ai/features/agent-event-handlers/)다. 판정은 문서의 가능성보다 실제 관찰을 우선한다.

## Native Koog 관찰

근거: [NativeKoogTest](../experiments/koog-validation/src/test/kotlin/experiment/NativeKoogTest.kt), [KoogRuntime](../experiments/koog-validation/src/main/kotlin/experiment/KoogRuntime.kt).

| 실험 | 실제 결과 | 해석의 범위 |
|---|---|---|
| 조회·결과 | 모델→등록된 tool→모델→완료. 실제 조회 1회, 모델 경계 호출 2회 | 여러 내부 호출이 한 번의 작업을 구성 |
| 동일 native session의 반복 run | 두 번째 prompt에 첫 입력 marker·첫 결과가 없었음 | 선택한 구성의 run-session이 대화 연속성을 자동 제공하지 않음 |
| 파일 checkpoint | 조회 후 다음 모델 요청에서 중단. 새 agent/storage와 같은 ID로 복원하면 원래 입력·조회 이후 위치를 유지. 새 입력 marker는 prompt에 없고 조회 횟수는 1 | 중단 실행의 복원과 후속 대화 입력은 다름 |
| 협조적 취소 | tool suspend 중 취소하면 후속 모델 호출·상태 변경 없음 | 취소 요청 후 실제 종료를 관찰 가능 |

이 결과는 Koog 1.2.0의 해당 구성에 한정한다. Koog 전체에 대화 기능이 없다는 주장이 아니다. checkpoint 검사는 객체 재생성이며 JVM crash/restart·외부 효과 exactly-once를 증명하지 않는다.

## 업무 시나리오와 기존 Port 실험

근거: [PortScenariosTest](../experiments/koog-validation/src/test/kotlin/experiment/PortScenariosTest.kt). 여기서 기존 이름·상태는 검증 당시 코드의 사실을 가리킨다.

| 시나리오 | 관찰한 사실 | 개정에 주는 근거 |
|---|---|---|
| S1 검토·결과 | 실제 tool 조회 1, 결과와 COMPLETED, observer 없이 완료·늦은 terminal 수신 | AgentTask의 진행과 결과는 observer에 독립 |
| S1 schema | 유효 JSON은 판독되지만 잘못된 JSON도 기존 FINISHED로 반환 | 실행 완료와 산출물 검증 분리. native schema 집행은 미검증 |
| S2 승인 | 승인 전 변경 0, 승인 후 1. 잘못된 decision·중복 응답 거절. Tool/Effect가 같은 work ID | 승인 중재·효과 관찰 목적 유지 |
| S2 거절 | 실제 변경 0. tool 거절 결과를 모델에 전달하고 계속 | 행위 거절은 작업 전체 실패와 다름 |
| S2 질문 | native callback은 문자열 답변 가능. 기존 sealed Approval 응답으로는 표현 불가하여 tool 오류 전달 | Question/Answer를 승인과 구별하는 계약 필요 |
| S3 연속성 | 완료 이력을 저장하면 후속 입력·release/resume·새 harness/store에서 marker 유지. instructions override, 모르는 ID 거절 | adapter가 연속성·선택 영속성을 이행할 수 있음. 기본 영속성 필수의 증거는 아님 |
| S4 취소 | 중첩 거절, 협조적·즉시 취소, pending 취소, 완료 후 cancel, close 중 pending 정리 확인 | Task의 요청·정리·종결 규칙을 분리하여 검증 가능 |
| S4 비협조적 작업 | 30ms close 유예 뒤에도 작업 RUNNING, gate를 연 뒤 변경 1건 발생. tool 반환 후 CANCELLED | 유예 만료를 실제 취소로 확정할 수 없음 |
| 관찰·실패 | 느린 observer에도 graph 완료·gap·terminal. usage는 null. 일반 오류는 기존 FAILED(PROVIDER), 반복 한도 예외는 COMPLETED + TURN_LIMIT | 알려진 의미 번역과 unknown 보존. provider 내부 한도 단위를 보편화하지 않음 |
| 요구 거절 | 명시적 FS/network 요구를 모델·업무 도구 호출 전에 거절 | 승인 gate와 OS 권한 집행은 다른 계약 |

실험 adapter는 종료 미확정 상황에 오류를 내고 실제 도구 종료까지 RUNNING을 남겼다. 이는 반례를 관찰하기 위한 처리이며 새 계약의 최종 구현이 아니다. 개정 계약은 `Unresolved`로 handle의 outcome도 유한하게 회수하도록 요구한다.

## 기존 두 adapter와 증거 수준

| 목적 | Codex 기존 경로 | Gemini CLI 기존 경로 |
|---|---|---|
| 작업·결과·도구·관찰 | 기존 contract/mapper fixture 통과 | 기존 contract/mapper fixture 통과 |
| 승인 | round-trip·중복/만료·대기 중 취소 fixture 통과 | 현 adapter의 CALLER_DECIDES 사전 거절 검사 통과 |
| 문맥·재개 | thread/resume 대응과 기존 lifecycle 회귀. 실제 대화 복원은 이번에 미검증 | SDK session/resume 대응과 기존 lifecycle 회귀. 실제 대화 복원은 이번에 미검증 |
| 취소·close | 기존 기대값의 검사 통과. 실제 중단 의미는 개정 후 재검증 필요 | 기존 기대값의 검사 통과. 실제 중단 의미는 개정 후 재검증 필요 |
| 동일 R-1 실제 업무 | 미수행 | 미수행 |

기존 회귀 구성은 Codex contract 20·mapper 9·interaction 6, Gemini contract 20·mapper 8·policy 1, bridge 6, bundle 1이다. process lifecycle 5개를 포함한다. Python/Node host suite는 이 실험 작업에서 별도로 실행하지 않았다.

Koog는 실제 runtime에 통제된 모델을 연결한 증거이고, Codex/Gemini는 주로 기존 fake bridge 기반 회귀다. 두 provider에 같은 업무 도구를 실제 연결하거나 새 계약의 같은 suite를 실행하지 않았다. 모델 인증과 Gemini SDK build 환경이 준비되지 않은 실연동은 미수행이다.

## 추상별 설계 판정

| 목적 / 개념 | 현재 설계 판단 |
|---|---|
| 하네스 위임 경계 | AgentHarness 유지. SDK/process 소유를 정의의 중심에서 제거 |
| 작업 위임 단위 | AgentExecution을 AgentTask로 전환. 내부 graph/turn을 직접 노출하지 않음 |
| 문맥 연속성 | AgentSession 유지. adapter의 보관 책임은 요구한 범위에 따라 결정 |
| 영속 보관 | 기본 Session에서 분리하여 선택 계약으로 제공 |
| 승인·질문 | 공통 interaction lifecycle과 구체적 응답 의미를 함께 제공 |
| 취소·정리 | requestCancellation, 실제 종료와 Unresolved, bounded outcome 회수로 개정 |
| 결과·산출물 | TaskOutcome/TaskOutput으로 분리. schema는 추가 요구·검증 계약 |
| Tool/effect | 목적 유지. 모든 도구에 효과를 추정하거나 내부 context 관리를 중복 분류하지 않음 |
| 요구·정책 | validate 목적 유지. 문맥·작업·자원·승인·권한의 scope를 분리 |
| 진단·테스트 | ProviderDiagnostic 분리, bridge 없는 공통 행동 검사 |
| usage/context, skills/자원 | 측정 부재의 unknown 외에 추가 보장·대체 모델은 미검증. 구체적 실증 없이 범용 타입을 만들지 않음 |

업무 기능은 fixture 애플리케이션이 소유하고 adapter는 도구 노출·승인·전달을 맡았다. 이 역할 분리는 비즈니스 판단이 graph·SDK 이벤트를 알지 않아도 된다는 근거다. 공개 custom tool 등록 계약이나 모든 하네스의 임의 callback 지원을 증명한 것은 아니다.

## 현재 구현과 남은 검증

[전환 현황](port-revision-plan.md)의 새 Port·세 adapter와 공통 native 검사 25개가 현재 기준이다. 독립 실험은 현재 Port로 이전했고 승인·질문·저장소를 구성한다. 이 선택 기능을 production 기본 구현이 모두 제공하는 것은 아니며, core 필수성도 구현 가능성과 구별한다.

실모델 호출, 세 provider의 새 계약 전체 적합성, 원격 단절·재접속, production 저장소·crash recovery·다중 writer, native structured output 집행, 전체 오류·진단·usage 관찰은 완료 범위가 아니다. 해당 검증 없이 보편적 보장을 주장하지 않는다.

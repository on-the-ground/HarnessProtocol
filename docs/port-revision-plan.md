# Port 개정과 구현 전환 계획

기준일: 2026-09-06. [설계 선언](../AHP_CHARTER.md) → [Semantic contract](semantic-contract.md) → [추상과 용어](abstraction-and-terminology.md) 및 상세 계약을 구현의 기준으로 삼는다.

공개 Port·KDoc·fixture 선언에 이어 실제 세 adapter와 factory를 새 Port에 연결했다. 기존 회귀와 독립 Koog 실험도 현재 Port로 이전하고 legacy를 제거했다. G03–G12의 실제 계약 경계 검사를 추가했고 문맥 핸들의 자원 수명·설정 보존·skill 적용·실행 상한 결함을 수정했다. 원래 미연결 시나리오도 목적별 증거와 폐기 근거를 남기고 종결했다. [현재 단계별 증거](contract-boundary-validation.md), [기본 연결 기록](native-port-validation.md)을 구별하며 이전 개정 이력은 Git에서 확인한다.

## 목표

비즈니스가 요구하는 작업 위임·문맥 연속성·개입·산출물·종결 판정을 제공 방식과 분리한다. 기존 API의 유지나 일괄 rename 자체를 목표로 삼지 않는다. 불필요한 추상을 제거하고 함께 묶인 책임을 분리하며 기존 adapter를 이에 맞춘다.

| 상태 | 근거 / 범위 |
|---|---|
| 완료 | 새 공개 Port·KDoc, Codex/Gemini/Koog 연결, factory·bundle·소비 예제 컴파일, G01–G12의 구성별 실제 경계 검사 |
| 이전 실증 | Koog native·부분 adapter 실험 18개. [당시 기록](../experiments/koog-validation/evidence/verification.json) |
| 문서 기준 | README와 docs를 새 추상의 규범으로 갱신. 실험 사실과 현 구현 상태는 구분해 기록 |
| 정리 완료 | 임시 ReferenceHarness 계열, 만능 HarnessFixture, 미연결 Core/Cleanup 본문 제거. 원래 48개 identity는 처리 근거와 함께 역사적 catalog로 보존 |
| 검증 완료 | 전체 JVM 292개(native 210개 포함)와 host 21개를 한 번에 실행해 실패·오류·건너뜀 0개를 기록 |
| 별도 후속 범위 | 미지원 선택 기능의 구현 확장, 외부 실모델 연동, artifact 발행. G12까지의 현재 구성 검증과 구별 |

## 1. 확정한 공개 모델

**상태: 공개 선언·리뷰 수정·세 adapter의 G01–G12 구성별 검증 완료.** 형태와 근거는 [공개 모델](public-model.md), 선언은 dev.harnessprotocol, 목적별 검증 seam은 harness-conformance에 있다. legacy 타입·실행 경로와 미연결 만능 fixture를 제거했다.

목적과 의미는 아래 연결 문서를 따른다.

| 결정 항목 | 공개 모델의 결정 | 현재 검증과 남은 범위 |
|---|---|---|
| Task lifecycle | AgentTask·TaskRequest, 시작·응답 수락 미확정의 별도 예외와 identity | G02·G03 수락 유실·중복·경쟁, 기본 runtime의 다중 waiter 검증. 없는 native 채널의 오류 주입은 미실증 |
| 상태 / outcome | 네 outcome과 공통 nullable output·usage | G05·G11에서 실제 종결별 부분 산출물과 마지막 usage 보존 |
| Session | SessionSpec, PersistentSessionRef·PersistentSessions | G04·G12 문맥 공유·차단·저장 장애·설정 보존. 재시작/동시 writer 요구는 G01 거절 |
| Interaction | typed 승인·질문, SessionApprovalGrant, RESPONSE_UNCONFIRMED | G02·G03·G08에서 Codex 일회 승인·효과·경쟁 검증. session grant·질문 성공 경로는 현재 미지원 |
| 구성 | SessionRequirements·TaskRequirements, 실제 skill 적용, 독립된 filesystem/network 상한 | G09에서 집행 가능한 조합과 정확한 사전 거절 검증 |
| 선택 계약 | SupportReport, CompatibilityStatus 세 판정 | G01 독립 profile별 수락/거절 검증. 실제 미확인 요구 사례는 미확보 |
| 산출물 | TaskOutput.Text/Structured, complete·SchemaValidation | G05 실제 텍스트/부재 보존, G10 구조화 요구 거절. schema 실행은 현재 미지원 |
| 관찰 | MessageId·MessageRole, TaskDiagnostics와 별도 DiagnosticEvent | G07 진단 queue 격리·gap, G11 사용량·일반 메시지 ID/역할·선택 native 도구/효과 상관관계 검증 |
| 정리 | close/release와 outcome 회수의 bounded 보장 | G06 다중 자원 전체 상한, 호출자 취소, 비협조 도구의 실제 후속 효과 검증 |

`Unresolved`를 기존 TRANSPORT 실패로 감춰서는 안 된다. 통신 실패가 확인된 작업 실패인지, 종료를 모르는 상황인지를 판정할 근거가 필요하다. 현재 구현의 강제 취소를 문서상 이름만 바꾸어 유지하지 않는다.

시작 수락·응답 수락 확인 유실의 공개 예외와 identity는 G02의 실제 전달 경계에서 검증했다. public handle이 없다는 이유로 작업 미실행을 단정하지 않으며, 응답 미확정 요청을 재전송하지 않는다. 제공하지 않는 별도 복구 수단까지 검증한 것으로 확장하지 않는다.

현재 구성에서 선언하거나 수락하는 조건은 실제 adapter 경계에서 검증했다. 문맥 연속성과 영속성의 목적 차이, 모든 outcome의 부분 결과 보존을 구현 편의로 낮추지 않는다. 새 선택 기능을 지원할 때는 해당 성공 경로의 검사를 같은 변경에 추가한다.

## 2. 공개 Port와 KDoc 전환

새 공개 선언과 세 adapter의 기본 연결에 반영한 책임 분리는 다음과 같다. 이 목록을 구현체 연결 전의 대기 작업으로 취급하지 않는다.

- [용어 전환표](abstraction-and-terminology.md)에 따라 Task 식별자·상태·이벤트·입력·연산 이름을 일치시킨다.
- 성공 전용 AgentResult와 실행 실패/취소 예외의 역할을 재구성한다. 변경된 의미를 alias로 감추지 않는다.
- 기본 session에서 필수 영속 재개를 분리한다. 재개 요구가 있는 소비자의 보장은 새 선택 계약으로 유지한다.
- ProviderDiagnostic과 구현별 transport를 기본 의미 이벤트·적합성 검사에서 분리한다.
- ContextManaged의 내부 관찰은 선택 진단으로 옮기고, 공개 설명은 Message 계약에 보존한다. ReasoningDelta를 이름만 바꿔 별도 기본 이벤트로 유지하지 않는다.
- 무의미한 default close 구현, 모호한 오류 상속, provider turn 기반 사유·한도 이름을 새 책임에 맞춰 검토한다.
- KDoc은 실제 시그니처·구현과 같은 변경에서 갱신한다. 문서만 새 API이고 타입 의미는 이전 상태인 구현을 완료로 취급하지 않는다.

## 3. 세 adapter 전환

| 대상 | 연결·검증한 범위 | 남은 구현·검증 |
|---|---|---|
| Codex | 기본 Task·문맥, 승인 효과·확인 유실·경쟁·별칭·저장 장애·usage·관찰 부하·skill·제한 실행 검증 | 외부 실모델과 현재 구성에서 거절하는 선택 기능은 별도 범위 |
| Gemini CLI | native SDK 지시, 실제 문맥·취소·재개·별칭 설정 보존·부분 결과·usage·관찰 부하·skill 검증 | 명시적 실행 제약은 사전 거절. 다른 SDK 버전은 별도 범위 |
| Koog | 실제 graph 문맥·다중 정리·비협조적 후속 효과·네 outcome·누락 구간 usage·관찰 부하 검증 | workspace·실행 제약 등 미지원 요구는 사전 거절 |

독립 Koog 실험도 현재 Port로 이전됐지만 production 기본 구성과 구별한다. 과거 로그는 당시 증거이며 실험의 선택 기능 통과를 production 구성의 통과로 가져오지 않는다.

## 4. 공통 적합성과 회귀

시나리오 통합 단계에서 순수 lifecycle과 native 공통/선택 판정을 conformance로 모으고 SDK 경계 검사를 분리했다. [전체 대응표](conformance-scenarios.md)는 원래 정의의 중복 목적·잘못된 고정 가정과 실제 binding을 구별한다. 이후 독립 요구 수락, 시작·응답 확인 유실, interaction 경쟁, 문맥 공유, 네 outcome, 다중 정리, 관찰 부하, 승인 범위, 회계, 저장 장애, skill 적용과 실행 제약을 실제 경계에 연결했다. 검증 범위와 제공하지 않는 선택 기능은 [현재 기록](contract-boundary-validation.md)을 따른다.

공통 판정은 세 adapter의 실제 경계에 기능별로 연결했다. 기존 48개 C/K identity는 새 검사의 목적 대응, 현재 기능 거절, 폐기한 구현 종속 가정으로 분류했다. 준비·입력 유도·효과 관찰은 provider별 fixture가 맡으며 공통 판정은 provider wire·Koog node ID를 알지 않는다. [Testing](testing.md)과 [시나리오 종결표](conformance-scenarios.md)를 따른다.

필수 계약은 세 구현이 모두 통과해야 한다. 선택 계약은 지원하면 해당 의미를 검증하고 미지원이면 사전 거절을 검증한다. 기존 spec→SDK 투영, bridge EOF, mapper 같은 검사는 구현별 회귀로 보존한다. close 강제 취소 등 잘못된 기존 기대값은 변경한다.

실제 SDK/runtime과 모델 연동은 fixture 검증과 별도로 기록한다. 인증·빌드 환경이 없으면 부분 완료로 표시하고 세 실제 provider가 검증됐다고 표현하지 않는다.

## 5. 소비자와 배포 전환

samples/basic과 새 factory·bundle은 세 adapter의 새 Port 경로를 가리키며 source composite build 컴파일이 통과했다. 필요한 영속성·승인·권한·출력 계약을 소비자가 명시하는 migration 예제와 실제 artifact 발행 검증이 남아 있다.

지원 표, 실제 runtime 준비, KDoc, README의 구현 상태를 최종 코드와 대조한다. source compatibility와 의미 변경을 따로 설명하고 같은 버전의 사용법으로 섞지 않는다. 배포·발행은 별도 실행 작업이다.

## 후속 검증에서 유지할 구체적 점검 항목

| 영역 | 확인할 위험 |
|---|---|
| 메시지·산출물 | delta/completed 중복, commentary가 최종 산출물을 덮어씀, 관찰 불가능한 phase를 추정 |
| 도구·효과 | ID fallback 충돌, tool 이름·인자 key 휴리스틱, 거절/실패/취소 혼동, 내부 context 관리의 외부 효과 중복 |
| 요구 보존 | null/빈 지시 구별, active skill 본문 적용과 inactive skill 제공, sandbox별 network 상한, desired configuration 적용 |
| 저장·문맥 | 저장 실패, 재생성, 모르는 ID, crash/다중 writer, 미확정 작업과 다음 입력의 충돌 |
| 통신·정리 | terminal 이전 EOF, 시작·응답 acknowledgement 유실, close/자연 완료 경쟁, 응답 reader 교착, waiter 반환 전 state/pending 확정, 종료 미확정의 bounded 회수 |
| 사용량 | 공통 누적 snapshot 규칙, provider 증분/누적 번역, Task/Session scope 혼합, unknown을 0으로 합성 |
| 운영 | provider 등록과 bundle 선택, 임시 runtime 추출 수명, 지원 OS/환경, 실제 SDK와 fixture 차이 |

기존 기록에 있었던 모든 의심을 현재 결함이라고 단정하지 않는다. 새 구현 전환에서 위험을 확인하는 항목으로 사용하고 실제 결과를 남긴다.

문서 개편 후의 [회귀 검토](regression-review.md)는 기존 보장의 복원과 새 설계의 불확실성 처리 점검을 기록한다. 기존 suite 통과를 새 계약의 구현 완료로 해석하지 않는다.

## 완료 기준

1. 확정된 새 계약이 코드·KDoc·문서에 같은 의미와 용어로 반영된다.
2. 세 adapter가 필수 공통 검사를 모두 통과하고 선택 계약의 지원·거절이 지원표와 일치한다.
3. 기존 소비자가 요구하던 보장을 새 계약으로 표현하고 이행할 수 있다. 삭제·변경되는 의미는 명시한다.
4. 실연동과 미검증 범위가 provider별로 기록된다.
5. 예제·배포 정보가 실제 구현과 일치한다.

다섯 기준은 현재 고정 구성에서 충족했다. 최종 근거는 [계약 경계 검증](contract-boundary-validation.md), [시나리오 종결표](conformance-scenarios.md), [통합 회귀 집계](../harness-native-integration/evidence/final-regression-summary.json)에 기록한다. 별도 후속 범위가 추가되면 그 구성과 보장을 새 검증 단위로 다룬다.

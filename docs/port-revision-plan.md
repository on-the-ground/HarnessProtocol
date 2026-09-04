# Port 개정과 구현 전환 계획

기준일: 2026-09-04. [설계 선언](../AHP_CHARTER.md) → [Semantic contract](semantic-contract.md) → [추상과 용어](abstraction-and-terminology.md) 및 상세 계약을 구현의 기준으로 삼는다.

공개 Port·KDoc·fixture 선언에 이어 실제 세 adapter와 factory를 새 Port에 연결했다. native 실행 검증과 기존 회귀를 병행하며, 기존 회귀와 독립 Koog 실험까지 현재 Port로 이전하고 legacy를 제거했다. 전체 적합성 gate와 발행은 남아 있다. 현재 증거는 [실제 adapter 검증](native-port-validation.md), 이전 개정 이력은 Git에서 확인한다.

## 목표

비즈니스가 요구하는 작업 위임·문맥 연속성·개입·산출물·종결 판정을 제공 방식과 분리한다. 기존 API의 유지나 일괄 rename 자체를 목표로 삼지 않는다. 불필요한 추상을 제거하고 함께 묶인 책임을 분리하며 기존 adapter를 이에 맞춘다.

| 상태 | 근거 / 범위 |
|---|---|
| 완료 | 새 공개 Port·KDoc, Codex/Gemini/Koog 연결, factory·bundle·소비 예제 컴파일, 실제 runtime 검사 25개 |
| 이전 실증 | Koog native·부분 adapter 실험 18개. [당시 기록](../experiments/koog-validation/evidence/verification.json) |
| 문서 기준 | README와 docs를 새 추상의 규범으로 갱신. 실험 사실과 현 구현 상태는 구분해 기록 |
| 정리 완료 | 임시 ReferenceHarness 계열과 실행 subclass 제거. 48개 시나리오 정의는 testFixtures로 보존, 실행·통과 수에서 제외 |
| 남은 작업 | 현재 native 검사에서 빠진 계약·경쟁 시나리오 추가, 선택 계약 구현·검증, 실모델 연동, artifact 발행 |

## 1. 확정할 공개 모델

**상태: 공개 선언과 리뷰 수정 완료, 세 adapter의 기본 native 검증 완료.** 형태와 근거는 [공개 모델](public-model.md), 선언은 dev.harnessprotocol, 검증 seam과 재사용 시나리오는 harness-conformance에 있다. legacy 타입·실행 경로 제거와 회귀 검사 이전을 완료했다. 아래 표의 의미를 바꾸지 않고 미검증 보장을 추가 검증한다.

목적과 의미는 아래 연결 문서를 따른다.

| 결정 항목 | 공개 모델의 결정 | 남은 구현·실증 |
|---|---|---|
| Task lifecycle | AgentTask·TaskRequest, 시작·응답 수락 미확정의 별도 예외와 identity | 실제 수락/종결 경계, 전달 확인 유실·중복 방지·다중 waiter와 정리 경쟁 |
| 상태 / outcome | 네 outcome과 공통 nullable output·usage | 세 adapter의 Task 범위와 종결 증거, 부분 결과 회수 |
| Session | SessionSpec, PersistentSessionRef·PersistentSessions | 저장 namespace·영속 조정 범위·차단 전달·복구와 접근 소유권 |
| Interaction | typed 승인·질문, SessionApprovalGrant, RESPONSE_UNCONFIRMED | grant 범위 안/밖 실제 효과, 수락 유실·철회·중복 처리 |
| 구성 | SessionRequirements·TaskRequirements, 독립된 filesystem/network 요구 | adapter별 지시·모델·문맥·정책 투영과 조건별 거절 |
| 선택 계약 | SupportReport, CompatibilityStatus 세 판정 | profile별 실제 지원·미지원·미확인 및 수락 시 추가 확인 |
| 산출물 | TaskOutput.Text/Structured, complete·SchemaValidation | provider가 주지 않은 결과를 합성하지 않고 네 outcome에서 회수 |
| 관찰 | MessageId·MessageRole, TaskDiagnostics와 별도 DiagnosticEvent | 메시지 변환, 누적 usage·unknown, 진단 queue 격리 |
| 정리 | close/release와 outcome 회수의 bounded 보장 | 전체 시간 상한·적용 범위·설정 노출, 다중 자원 정리와 확인 절차의 예산 배분 |

`Unresolved`를 기존 TRANSPORT 실패로 감춰서는 안 된다. 통신 실패가 확인된 작업 실패인지, 종료를 모르는 상황인지를 판정할 근거가 필요하다. 현재 구현의 강제 취소를 문서상 이름만 바꾸어 유지하지 않는다.

시작 수락·응답 수락 확인 유실의 공개 예외와 identity는 선언됐다. public handle이 없다는 이유로 작업 미실행을 단정하지 않으며, 응답 미확정 요청을 재전송하지 않는다. 실제 native 경계의 이행과 별도의 복구 수단은 후속 검증 대상이다.

다음 작업은 이미 연결된 실제 adapter에서 미검증 조건을 유도하고 계약 판정을 확장하는 것이다. 공통 7개 시나리오와 구현별 검사의 실증은 [현재 결과](native-port-validation.md)에 있다. 문맥 연속성과 영속성의 목적 차이, 모든 outcome의 부분 결과 보존을 구현 편의로 낮추지 않는다.

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
| Codex | 새 Task/outcome, 실제 지시·문맥·취소·정리·재개, 설정 변경 불가의 사전 거절 | 실제 승인 효과·수락 확인 유실, 권한·skills 집행, 사용량·관찰 경계 |
| Gemini CLI | 새 Task/outcome, native SDK 지시 보완, 실제 문맥·취소·재개와 desired 지시 반영 | SDK 버전 호환, skills·사용량·관찰 경계. 미지원 선택 요구는 계속 사전 거절 |
| Koog | production 모듈·bundle 연결, 실제 graph 문맥·취소·비협조적 효과·실패 후 부분 결과 | 선택 저장소·승인·질문·출력 등 구성 확장과 전체 계약 검증 |

실험용 Koog 코드와 검증 로그는 당시 증거다. 현재 production 코드와 native 검사는 별도 모듈에 있으며, 이전 실험의 선택 기능 통과를 새 구현의 통과로 가져오지 않는다.

## 4. 공통 적합성과 회귀

공통 7개 시나리오는 이미 세 adapter에 적용했고 구현별 4개를 더해 25개가 통과했다. [Testing](testing.md)의 나머지 조건을 이 실제 경계에 추가한다. testFixtures에 보존한 48개 정의는 profile의 고정 가정 등을 보완하여 재사용한다. 준비·입력 유도·효과 관찰은 provider별 fixture가 맡으며 공통 판정은 provider wire·Koog node ID를 알지 않는다.

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
| 요구 보존 | null/빈 지시 구별, skill 제공/활성화 envelope, sandbox별 network 의도 누락, desired configuration 적용 |
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

현재 문서 정리를 위 구현 완료로 세지 않는다. 작업은 단계별 결과를 보고하며 진행한다.

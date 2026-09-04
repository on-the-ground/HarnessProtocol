# Port 개정과 구현 전환 계획

기준일: 2026-09-04. [설계 선언](../AHP_CHARTER.md) → [Semantic contract](semantic-contract.md) → [추상과 용어](abstraction-and-terminology.md) 및 상세 계약을 구현의 기준으로 삼는다.

현재 범위는 README와 docs의 기준 정리다. 코드·KDoc·배포 artifact의 전환은 후속 작업이며 아직 완료되지 않았다. 이전 개정 이력은 Git에서 확인한다.

## 목표

비즈니스가 요구하는 작업 위임·문맥 연속성·개입·산출물·종결 판정을 제공 방식과 분리한다. 기존 API의 유지나 일괄 rename 자체를 목표로 삼지 않는다. 불필요한 추상을 제거하고 함께 묶인 책임을 분리하며 기존 adapter를 이에 맞춘다.

| 상태 | 근거 / 범위 |
|---|---|
| 완료된 실증 | Koog native·부분 adapter 실험 18개, 기존 회귀 71개. [기록](../experiments/koog-validation/evidence/verification.json) |
| 문서 기준 | README와 docs를 새 추상의 규범으로 갱신. 실험 사실과 현 구현 상태는 구분해 기록 |
| 미완료 | 공개 타입·KDoc 전환, 세 adapter 개편, 새 공통 테스트, 실모델 연동, 소비 예제·배포 전환 |

## 1. 확정할 공개 모델

목적과 의미는 아래 연결 문서를 따른다. 실제 Kotlin 선언을 만들기 전에 다음 사항을 구체화한다.

| 결정 항목 | 지켜야 할 방향 | 남은 구체화 |
|---|---|---|
| Task lifecycle | `AgentTask`, `startTask`, `requestCancellation`, `awaitOutcome` | 호출 실패와 outcome의 경계, 시작 요청 수락 미확정의 표현·identity 회수, 다중 waiter와 정리 경쟁 |
| 상태 / outcome | `Completed/Failed/Cancelled/Unresolved`와 대응 상태 | sealed 타입 필드, 종료 사유, 이미 확보한 부분 산출물·사용량의 위치 |
| Session | 기본 문맥 연속성, 영속성 분리 | 설정 scope, 보관 성공·식별·수명, `reopenSession`, 미확정 작업이 남은 문맥의 차단·복구 |
| Interaction | 공통 요청 lifecycle, 승인/질문 의미 구별 | 요청·응답 타입, 지원 요구, decision 범위, 모든 outcome의 pending 정리, 응답 수락 확인 유실·중복 처리 |
| 구성 | 요구를 실제 적용 범위에 배치 | 기존 AgentSpec/ExecutionPolicy 분해, 지시·모델·작업·문맥 설정의 소유와 override |
| 선택 계약 | 지원 선언 시 강한 보장 | 지원 탐색·요구 전달·실제 경계 검증, 영속성·자원·정책·출력·진단 interface |
| 산출물 | `TaskOutput`과 종결 판정 분리 | 텍스트·구조화 결과, schema 검증 실패와 부분 결과의 표현 |
| 관찰 | state/pending/outcome 독립, 의미 이벤트와 진단 분리 | message identity/phase, Task 누적 usage snapshot의 필드·nullable 병합, TaskUnresolved, provider 진단과의 상관관계 |

`Unresolved`를 기존 TRANSPORT 실패로 감춰서는 안 된다. 통신 실패가 확인된 작업 실패인지, 종료를 모르는 상황인지를 판정할 근거가 필요하다. 현재 구현의 강제 취소를 문서상 이름만 바꾸어 유지하지 않는다.

수락 여부를 모르는 시작 호출과 응답 acknowledgement 유실도 미확정 정보다. public handle이 없다는 이유로 작업 미실행을 단정하지 않는다. 요청 identity·전달 상태·안전한 재시도 가능성을 표현하는 구체적 API를 구현 전에 정한다. 이 항목이 남아 있는 동안 새 원격 호출 계약이 완성됐다고 보지 않는다.

## 2. 공개 Port와 KDoc 전환

- [용어 전환표](abstraction-and-terminology.md)에 따라 Task 식별자·상태·이벤트·입력·연산 이름을 일치시킨다.
- 성공 전용 AgentResult와 실행 실패/취소 예외의 역할을 재구성한다. 변경된 의미를 alias로 감추지 않는다.
- 기본 session에서 필수 영속 재개를 분리한다. 재개 요구가 있는 소비자의 보장은 새 선택 계약으로 유지한다.
- ProviderDiagnostic과 구현별 transport를 기본 의미 이벤트·적합성 검사에서 분리한다.
- 무의미한 default close 구현, 모호한 오류 상속, provider turn 기반 사유·한도 이름을 새 책임에 맞춰 검토한다.
- KDoc은 실제 시그니처·구현과 같은 변경에서 갱신한다. 문서만 새 API이고 타입 의미는 이전 상태인 구현을 완료로 취급하지 않는다.

## 3. 세 adapter 전환

| 대상 | 주요 작업 |
|---|---|
| Codex | 새 Task/outcome 변환, 승인 계약 보존, 영속 thread를 선택 계약으로 제공, 정책·자원 요구의 분리, 종료 확인 근거 점검 |
| Gemini CLI | 새 Task/outcome 변환, SDK의 실제 지원·거절, content·usage·tool/effect 매핑, abort와 실제 종료의 구별, session 보관 범위 확인 |
| Koog | 실험을 production 구현으로 전환, session 설정과 선택 저장소, interaction 중재, 비협조적 작업의 정리·미확정 처리, 등록·배포 구성 |

실험용 Koog 코드와 검증 로그는 당시 증거다. 이름을 소급 치환하여 새 계약이 이미 검증된 것처럼 만들지 않는다. production 구현에 재사용할 부분을 검토하고 새 계약으로 다시 검증한다.

## 4. 공통 적합성과 회귀

[Testing](testing.md)의 공개 Port 시나리오를 세 adapter에 적용한다. 준비·입력 유도·모의 효과 관찰은 provider별 fixture가 맡는다. 공통 판정은 SdkBridge, provider JSON, Koog node ID를 알지 않는다.

필수 계약은 세 구현이 모두 통과해야 한다. 선택 계약은 지원하면 해당 의미를 검증하고 미지원이면 사전 거절을 검증한다. 기존 spec→SDK 투영, bridge EOF, mapper 같은 검사는 구현별 회귀로 보존한다. close 강제 취소 등 잘못된 기존 기대값은 변경한다.

실제 SDK/runtime과 모델 연동은 fixture 검증과 별도로 기록한다. 인증·빌드 환경이 없으면 부분 완료로 표시하고 세 실제 provider가 검증됐다고 표현하지 않는다.

## 5. 소비자와 배포 전환

현재 samples/basic, factory, publication metadata와 bundle은 기존 구현을 가리킨다. 새 공개 타입에 맞춰 컴파일 예제를 바꾸고, 필요한 영속성·승인·권한·출력 계약을 소비자가 명시하는 migration 예제를 제공한다.

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

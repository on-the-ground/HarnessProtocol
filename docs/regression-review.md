# 문서 개편 후 회귀 검토

검토일: 2026-09-04. 기준은 [설계 선언](../AHP_CHARTER.md), [추상과 용어](abstraction-and-terminology.md), 개편 전 Git revision `d5733031a2533dfd0d56821d912442a08552b0fd`의 문서·코드·테스트다.

## 결과와 범위

기존 실행 코드는 변경하지 않았고 기존 Kotlin 검사 71개와 Koog 실험 18개를 모두 재실행하여 통과했다. 문서 검토에서는 일부 유효한 보장이 압축 과정에서 약해진 점을 발견해 복원했다. 새 종료 미확정 개념이 시작·응답 전달 경계에서도 일관되도록 설계 요구와 후속 검증을 보강했다.

이는 현재 문서와 기존 실행 코드에 대한 검토다. 새 공개 타입·세 adapter의 구현은 아직 전환 전이므로 **새 로직의 실행 회귀가 없다고 판정한 것은 아니다.** KDoc과 구현은 변경하지 않았다.

## 발견하여 수정한 항목

| 항목 | 문제 / 위험 | 수정한 기준 |
|---|---|---|
| 종결 동기화 | outcome과 state의 일치만 설명하여 waiter 반환 순서와 정상 완료·실패의 pending 정리가 덜 명확해짐 | 모든 outcome에서 waiter 반환 전에 terminal state·빈 pending을 확정. 남은 요청 정리와 늦은 응답 거절. collector callback 순서는 별개 |
| 사용량 | 공통 누적 snapshot이었던 규칙을 누적/증분 및 합산 규칙의 미확정 사항으로 열어둠. consumer의 provider별 분기·이중 합산 위험 | UsageChanged는 Task 누적 snapshot으로 고정. provider 증분/누적 차이는 adapter가 번역, Session은 별도 범위, reset·unknown 보존 |
| 입력·식별 | 입력 공백 보존, Task/Work/Interaction ID 범위와 reopen 응답의 정규화 ID 사용이 빠짐 | opaque·범위별 식별, Task 간 하위 ID 격리, 빈 텍스트 거절·공백 보존, 재개 응답 ID 사용을 명시 |
| 실패 분류 | 유용한 분류를 유지한다는 설명만 남고 근거 요건이 빠짐. 오류 문자열 추측이 업무 재시도·권한 판단에 영향을 줄 수 있음 | 구조화 provider 정보·의미가 확인된 native 예외·runtime 사실로 분류. 자연어 추측만으로 구체적 실패 종류 확정 금지 |
| 시작 수락 미확정 | handle 생성 전 오류를 모두 호출 실패로만 설명. 시작 요청 수락 뒤 응답 유실을 미실행으로 오해할 수 있음 | 사전 거절과 수락 여부 미확정을 구별. 확인 전 같은 문맥의 새 작업·무조건 재시도 차단. 구체적 표현과 identity 회수는 구현 전 결정 |
| 응답 전달 미확정 | respond의 일회 응답만 정의하고 성공 반환·acknowledgement 유실의 의미가 부족 | 성공은 전달·수락 확인이며 작업 재개 완료와 구별. 잘못된 응답은 전달 전 거절, 확인 유실을 미전달로 바꾸어 이중 응답하지 않음 |

구체적 규칙은 [Protocol reference](protocol-reference.md), [Lifecycle](lifecycle-and-concurrency.md), [Event contract](event-contract.md)에 반영했다. [Provider mapping](provider-mapping.md)과 [Bridge protocol](bridge-protocol.md)에 전달·매핑 책임을 연결하고, [Testing](testing.md)과 [전환 계획](port-revision-plan.md)에 판정·구현 항목을 추가했다.

기존 [공통 테스트](../harness-adapter-testkit/src/main/kotlin/dev/harnessprotocol/testkit/AgentHarnessContractTest.kt)의 `state is terminal before awaitResult returns`, 정규화된 resume ID, observer 독립성, terminal 유일성 등을 대조했다. 기존 usage·interaction 문서와 ID/입력 모델도 기준선과 비교했다. 기존 내부 타입을 새 계약의 필수 구현 방식으로 복원하지 않았다.

## 의도한 변경과 유지할 보장의 구별

| 기존 전제 | 새 설계 | 회귀로 되돌리지 않을 이유 |
|---|---|---|
| 모든 Session은 durable conversation | 기본 문맥 연속성 + 명시적인 영속성 선택 계약 | 문맥 공유와 영속 저장은 다른 목적. 영속 요구를 선택한 소비자의 보장은 계속 유지 |
| close 유예 후 CANCELLED | 실제 결과 확인 또는 Unresolved | 시간 경과는 중단 증거가 아님. bounded 정리와 outcome 회수는 유지 |
| host death는 작업 TRANSPORT 실패 | 확인한 실패와 종료 미확정 구별 | 관찰·제어 상실을 외부 작업 종료로 오인하지 않음 |
| 성공 결과 + 실패/취소 예외 | TaskOutcome + TaskOutput | 종료 판정·산출물·업무 목표 달성을 구별. 오류 정보·부분 산출물은 보존 |
| 승인만 public interaction | 공통 요청 lifecycle + 승인·질문 응답 | typed 판단을 확장하되 승인 효과·일회 응답 보장은 유지 |
| core 원본 이벤트 / bridge 공통 검사 | 선택 진단 / 공개 Port 행동 검사 | 진단 기능과 구현별 검사는 보존하되 특정 전달 방식을 기본 계약에 강제하지 않음 |

추상과 이름은 개정 방향을 유지했다. 이를 문자열 입출력만의 계약으로 축소하거나 모든 필수 기능을 거절 가능한 선택 기능으로 옮기지 않았다. 선언한 선택 계약을 정확히 이행해야 한다는 조건도 유지했다.

## 실행 회귀 확인

JDK는 기존 검증과 같은 Corretto 25.0.3을 사용했다. 다음 명령으로 캐시된 test 결과를 재사용하지 않고 실행했다.

```powershell
./gradlew.bat test --rerun-tasks --console=plain
./gradlew.bat -p experiments/koog-validation test --rerun-tasks --console=plain
```

| 검사 | 실제 실행 수 | 실패 | 오류 | skip |
|---|---:|---:|---:|---:|
| 기존 Kotlin suite | 71 | 0 | 0 | 0 |
| Koog native / Port 실험 | 18 | 0 | 0 | 0 |

JUnit XML의 suite별 개수와 실행 시각을 확인했다. 기존 suite에는 process lifecycle 5개를 포함한다. 별도 Python/Node host suite, 실모델, 새 AgentTask 계약의 공통 suite는 이번 회귀 검토에서 실행하지 않았다.

Git diff에서 tracked Kotlin·KDoc·host 코드·빌드 설정의 변경이 없음을 확인했고, [기존 실험 기록](../experiments/koog-validation/evidence/verification.json)의 소스·빌드 파일 SHA-256 11개와 현재 파일이 일치했다. 기존 실험 기록은 덮어쓰지 않았다. 재실행 결과는 각각 임시 build 경로의 test-results/test에 생성된다.

## 후속 구현에서 남은 검증

TaskOutcome의 필드와 호출 실패 모델, 수락 미확정·응답 확인 유실의 전달 상태, schema 검증 실패와 부분 산출물, 영속 문맥의 미확정 작업 차단·복구는 아직 구체화·구현해야 한다. 이들은 문서상의 판정 기준과 실행 검증을 구분해 관리한다.

문서에서 복원·보강한 시나리오를 새 Port와 세 adapter에 실제 적용해야 최종 통합 회귀를 판정할 수 있다. 기존 89개 검사가 통과했다는 사실로 이 후속 검증을 대체하지 않는다.

## 외부 총평 반영 후 추가 검토

같은 날 `7a26361`에 대한 총평을 반영했다. [처리 기록](review-disposition.md)에 수용·수정·보류 이유를 남겼다. 이 단계에서는 런타임 테스트를 재실행하지 않았고 위 71개·18개 수치는 직전 실행의 증거다.

| 대조한 위험 | 문서상 결과 / 후속 실행 검증 |
|---|---|
| 거짓 취소를 피하다 모든 결과를 Unresolved로 낮춤 | 충분한 종결 근거 사용 의무와 반대 방향의 적합성 시나리오 추가. provider별 증거의 실제 범위는 구현 단계에서 검증 |
| 차단 범위 축소로 같은 문맥의 중첩 실행을 허용 | 소유 harness의 같은 논리 문맥과 선언한 영속 조정 범위에서 차단 유지. 재생성·다중 접근은 지원 범위를 명시하고 검사 |
| 복구 불가가 모든 후속 작업을 막음 | release·독립된 새 문맥·기존 문맥 복구 구별. 새 문맥이 이전 외부 효과를 없앤다는 보장은 추가하지 않음 |
| 실패·취소·미확정에서 이미 확보한 데이터 유실 | 모든 outcome의 산출물·사용량 회수를 의미 계약으로 확정. observer·필드 배치와 독립, 부분·unknown·검증 상태 보존 |
| 선택 계약과 조회가 실행 경계의 보장을 약화 | 조회 없이도 요구 가능, 실제 수락 시 검증, 조건 변화의 실패·미확정 처리와 테스트 계획 추가 |
| 이벤트 제거가 공개 설명을 버리거나 final output으로 오인 | 공개 설명은 메시지의 역할·phase로 보존하고 ContextManaged만 내부 진단으로 이동. 같은 호출의 WorkId 근거와 이벤트 목적을 명시 |
| 기존 핵심 보장 누락 | terminal 유일성, waiter 이전 state·pending 정리, observer 독립성·gap, 누적 usage, 입력 공백·지시, 승인 전 효과 차단·일회 응답, 정규화된 재개 ID를 유지 |

문서의 로컬 링크·절 anchor·fence와 Git diff 공백 검사를 수행했다. 실험 소스·빌드 파일 SHA-256 11개는 기존 verification.json과 모두 일치했다. 실험 파일은 Git 누락을 정리하는 대상이며 그 코드나 검증 기록의 내용은 변경하지 않았다. 기존 tracked 구현·KDoc·host·빌드 설정의 수정은 없었다. 이번에 보강한 의미의 세 adapter 실행 적합성은 여전히 후속 작업이다.

# 문서 개편 후 회귀 검토

검토일: 2026-09-04. 기준은 [설계 선언](../AHP_CHARTER.md), [추상과 용어](abstraction-and-terminology.md), 개편 전 Git revision `d5733031a2533dfd0d56821d912442a08552b0fd`의 문서·코드·테스트다.

이 문서는 당시 문서 개편의 검토 기록이다. 이후 수행한 세 adapter의 새 Port 연결·실제 runtime 검증과 현재 회귀 결과는 [native 검증 기록](native-port-validation.md)을 따른다.

## 결과와 범위

기존 실행 코드는 변경하지 않았고 기존 Kotlin 검사 71개와 Koog 실험 18개를 모두 재실행하여 통과했다. 문서 검토에서는 일부 유효한 보장이 압축 과정에서 약해진 점을 발견해 복원했다. 새 종료 미확정 개념이 시작·응답 전달 경계에서도 일관되도록 설계 요구와 후속 검증을 보강했다.

이는 문서 개편 당시의 코드에 대한 검토였다. 그 시점에는 새 공개 타입·adapter 전환을 수행하지 않았으며 KDoc과 구현도 변경하지 않았다. 현재는 새 Port·세 adapter 연결과 native 검사가 존재하므로 최신 상태는 [native 검증 기록](native-port-validation.md)을 따른다.

## 발견하여 수정한 항목

| 항목 | 문제 / 위험 | 수정한 기준 |
|---|---|---|
| 종결 동기화 | outcome과 state의 일치만 설명하여 waiter 반환 순서와 정상 완료·실패의 pending 정리가 덜 명확해짐 | 모든 outcome에서 waiter 반환 전에 terminal state·빈 pending을 확정. 남은 요청 정리와 늦은 응답 거절. collector callback 순서는 별개 |
| 사용량 | 공통 누적 snapshot이었던 규칙을 누적/증분 및 합산 규칙의 미확정 사항으로 열어둠. consumer의 provider별 분기·이중 합산 위험 | UsageChanged는 Task 누적 snapshot으로 고정. provider 증분/누적 차이는 adapter가 번역, Session은 별도 범위, reset·unknown 보존 |
| 입력·식별 | 입력 공백 보존, Task/Work/Interaction ID 범위와 reopen 응답의 정규화 ID 사용이 빠짐 | opaque·범위별 식별, Task 간 하위 ID 격리, 빈 텍스트 거절·공백 보존, 재개 응답 ID 사용을 명시 |
| 실패 분류 | 유용한 분류를 유지한다는 설명만 남고 근거 요건이 빠짐. 오류 문자열 추측이 업무 재시도·권한 판단에 영향을 줄 수 있음 | 구조화 provider 정보·의미가 확인된 native 예외·runtime 사실로 분류. 자연어 추측만으로 구체적 실패 종류 확정 금지 |
| 시작 수락 미확정 | handle 생성 전 오류를 모두 호출 실패로만 설명. 시작 요청 수락 뒤 응답 유실을 미실행으로 오해할 수 있음 | 사전 거절과 수락 여부 미확정을 구별. TaskStartUnconfirmedException과 요청 identity를 선언하고 미확정 문맥의 새 작업·자동 재시도를 차단 |
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

## 현재 남은 검증

TaskOutcome의 필드·호출 실패 모델·수락 미확정 예외는 이미 선언했고 실제 adapter가 사용한다. 부분 산출물과 기본 문맥·취소·정리는 native 경계에서 검증했다. 남은 것은 시작·응답 확인 유실의 실제 주입, schema 집행, 영속 문맥의 경쟁·복구, 다중 자원·정확한 정리 상한 등의 조건이다.

새 Port에 적용한 native 25개를 바탕으로 미검증 시나리오를 확대한다. 이전 실험·legacy 회귀의 통과 수나 실행하지 않는 시나리오 정의 수로 전체 적합성을 대체하지 않는다.

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

당시 문서의 로컬 링크·절 anchor·fence와 Git diff 공백 검사를 수행했다. 실험 소스·빌드 파일 SHA-256 11개는 기존 verification.json과 일치했다. 실험 파일의 Git 누락을 정리했으며 당시 코드·KDoc·host·빌드 설정은 수정하지 않았다. 이후 수행한 새 구현·실행 검증과 구분한 기록이다.

## 공개 Port 리뷰 수정의 후속 검증

위 내용은 문서 개편 당시 기록이다. 이후 공개 Port를 선언한 d3f66d0을 리뷰하고 값 타입·KDoc·fixture를 수정한 결과는 [공개 모델과 실행 결과](public-model.md)에 있다. 이 후속 단계에서는 기존 Kotlin 71개와 새 공개 모델 검사 9개, Koog 실험 18개를 실행해 모두 통과했다.

패키지 이동 후 Koog 실험의 컴파일 실패를 재현하고 현재 소스 의존을 고정 revision의 소스 추출로 바꿨다. 실험 Kotlin 소스·기존 verification.json은 유지했고 build.gradle.kts 변경 및 실행 기록은 [별도 재현 증거](../experiments/koog-validation/evidence/reproduction-after-port-revision.json)에 남겼다. ‘해시 11개 일치’와 빌드 파일을 제외한 10개 일치는 각 과거 단계의 기록이다. 이후 실험 코드를 현재 Port로 이전했으므로 현재 소스 해시는 [이전 기록](../experiments/koog-validation/evidence/current-port-migration.json)에서 확인한다.

수락 미확정과 미지원, 산출물 부재와 빈 텍스트, 미측정 구간의 합산, 독립적인 network 요구, 명시적 세션 승인 범위, 별도 진단 경로를 공개 계약에서 구별했다. 값 타입 검사만으로 실제 응답 중복 방지·권한 집행·문맥 연속성·진단 queue 격리까지 검증됐다고 주장하지 않는다. 그 행동은 새 공통 suite와 실제 adapter fixture에서 검증해야 한다.

## Legacy 의존 제거의 후속 검토

기존 회귀와 독립 Koog 실험까지 현재 Port로 이전하고 구 타입·실행 경로·Git 소스 추출을 제거했다. 유효한 보장과 바꾼 판정, 실행 결과의 경계는 [legacy 이전 검토](legacy-port-migration.md)를 따른다. 위 0.1.0 검사 결과와 해시 일치는 과거 기록이다.

# Testing

검증 기준은 [Semantic contract](semantic-contract.md)와 [개정 계약](protocol-reference.md)이다. 테스트는 기존 구현 형태의 보존보다 비즈니스가 관찰하는 보장을 확인한다. 현재 suite는 전환 전이며 아래 공통 검사 구조는 후속 구현의 기준이다.

## 공통 검사와 구현별 검사

| 검사 | 공통 판정이 알아야 할 것 | 구현별 fixture의 책임 |
|---|---|---|
| Port 적합성 | AgentHarness, AgentSession, AgentTask, 요구·interaction·outcome·output | provider 구성, 통제된 진행·실패·취소 유도, 합성 업무 효과 관찰 |
| 선택 계약 | 선언한 지원, 의미 이행, 정확한 사전 거절 | 영속 저장소, 승인·질문 경로, 정책 집행, schema 검증 등 |
| SDK 변환 | 해당 adapter의 의도 투영·event 매핑 | provider JSON, SDK 모델, 원본 이벤트 |
| Transport | 해당 process adapter의 전달·EOF·중복·정리 | RecordingBridge, 실제 host process, stub server |
| 실제 연동 | 실제 provider에서 관찰한 계약 범위 | 고정한 SDK/runtime/model, 인증·설정·호출 한도 |

공통 suite의 factory에 SdkBridge나 RecordingBridge를 요구하지 않는다. 기존 의도 투영 검사는 독립적인 선언으로 유지하고 adapter의 변환 코드 자체를 호출해 기대값을 만들지 않는다. Koog는 직접 연결하는 fixture로 같은 업무 판정을 검증한다.

## 필수 공통 시나리오

- 작업을 수락하고 handle을 반환한다. 여러 내부 도구·모델 호출이 한 Task에 포함될 수 있다.
- 완료·확인된 실패·확인된 취소·종료 미확정의 outcome과 state가 일치하고 한 번만 확정된다.
- 모든 outcome에서 waiter 반환 전에 terminal state·빈 pending snapshot이 확정되고, 남은 요청의 정리와 늦은 응답 거절이 이뤄진다. 완료·실패 경로도 취소·close와 별도로 검사한다.
- observer가 없거나 늦거나 느려도 진행·pending·outcome이 막히지 않는다. 구독 중 유실은 gap으로 알려지고 terminal은 잃지 않는다.
- 같은 session의 문맥이 다음 작업에 이어지고 다른 session과 섞이지 않는다. 진행 중 중첩 시작은 실제 요청 전에 거절한다.
- 취소 요청이 완료를 미리 확정하지 않는다. 자연 완료 경쟁, 즉시 취소, 정리 중 경쟁을 검사한다.
- close/release는 bounded하게 handle을 정리한다. 실제 종료를 확인하지 못하면 Unresolved를 회수한다.
- Unresolved 뒤에도 실제 효과가 발생할 수 있는 fixture로 거짓 취소를 검출한다. 같은 문맥의 새 작업은 안전성을 확인할 때까지 차단한다.
- 잘못된 값·지원 불가 요구·호출 실패와 handle을 얻은 이후 outcome을 구별한다.
- 시작 요청 수락 뒤 응답을 잃은 경우를 요청 전 거절과 구별한다. handle 반환 실패만 보고 재시도하거나 같은 문맥에 새 작업을 시작하지 않는다.
- 같은 하위 ID를 가진 서로 다른 Task의 이벤트·응답이 섞이지 않는다. 입력의 공백·지시·활성화 envelope 의미와 reopen의 정규화된 응답 ID를 보존한다.
- 완료가 업무 성공이나 산출물 schema 검증을 뜻하지 않음을 확인한다.

사용량을 관찰하는 경로에서는 provider 증분과 누적 입력을 각각 사용해 공통 누적 snapshot이 같은 의미가 되는지 검사한다. 반복 snapshot, 누락·reset, Task/Session 분리와 최종 관찰을 확인한다. 실패 분류는 구조화 정보·확인된 native 예외와 자연어 문구만 있는 경우를 구별한다.

필수 검사는 모든 적합 구현이 통과해야 한다. 전부 거절하거나 skip하여 통과시키지 않는다. 종료 미확정을 정상 지원 불가의 대체 결과로 쓰지 않는다.

## 선택 계약 검사

| 선택 계약 | 지원 구현 | 미지원 구현 |
|---|---|---|
| Caller 승인 | 승인 전 효과 0, 허용 범위 내 효과, 거절 시 효과 없음, 중복·만료·취소 처리. 응답 수락 뒤 acknowledgement 유실 시 이중 응답 방지 | 필수 승인 요구를 작업 전에 거절 |
| 질문 | 현재 요청, typed 답변, 같은 작업 계속, 응답·철회 경쟁. 전달 확인 유실과 미전달을 구별 | 필수 질문 요구를 거절; 승인 enum으로 위장하지 않음 |
| 영속성 | 약속한 보관 범위, release/재생성 후 reopen, desired configuration, 모르는 ID, 저장 실패 | 영속 요구를 거절; 기본 session ID로 재개 보장하지 않음 |
| 권한·작업 자원 | 요청 scope의 실제 집행·자료 해석·활성화 | 요청을 default로 낮추지 않고 거절 |
| 구조화 산출물 | schema 요구·검증 성공·실패·부분 산출물의 구별 | JSON 문자열 전달만으로 지원 선언하지 않음 |
| 진단 | 선언한 범위·유실·상관관계 | 원본 이벤트 부재가 기본 Task 적합성을 막지 않음 |

한 선택 기능의 지원이 다른 보장을 의미하지 않는다. checkpoint 복원이 이력 조회나 외부 효과의 exactly-once를 자동 보장하는지 검사하지 말고, 그런 별도 계약을 제공할 때 별도 시나리오로 검증한다.

## 현재 검증 위치와 실행 명령

다음은 아직 이전 API를 사용하는 구현별 검증 자산이다.

| 위치 | 현재 내용 / 전환 |
|---|---|
| harness-adapter-testkit | RecordingBridge, AgentHarnessContractTest, SpecSpace/IntentProjection. 공통 행동과 bridge 투영을 분리할 대상 |
| harness-codex/src/test, harness-gemini-cli/src/test | 기존 공통 suite와 mapper·정책·interaction 검사 |
| harness-process-bridge/src/test | BridgeProtocolTest, ProcessLifecycleTest |
| bridges/tests | Python CodexClient + stub App Server, Node host 검사 |
| experiments/koog-validation | Koog native/Port 실험. [재현 안내](../experiments/koog-validation/README.md) |

```powershell
./gradlew.bat test
./gradlew.bat hostTests
./gradlew.bat check -PstrictHostTests
./gradlew.bat -p experiments/koog-validation test
```

Host 검사의 현재 준비 사항은 Python 환경의 `bridges/requirements-codex.txt` 및 pytest 설치, Node 20+다. hostTests는 interpreter가 없으면 skip할 수 있으므로 전체 검증을 요구할 때 strictHostTests gate를 사용하고 실제 실행된 수를 확인한다.

## 증거 기록

문서/API 조사, 실제 runtime에 통제된 모델 응답을 연결한 재현, 실모델 통합을 구분한다. SDK·artifact·source revision·환경·실제 실행한 검사 수와 skip·미검증 범위를 기록한다.

Koog 실험 18개와 기존 회귀 71개의 [검증 기록](../experiments/koog-validation/evidence/verification.json)은 이전 계약의 baseline이다. 새 계약의 적합성 통과로 재사용하지 않는다. Python/Node host suite와 실모델은 해당 실험 작업에서 별도로 실행하지 않았다.

실제 App Server 승인 payload 검증과 Gemini SDK build의 제약 확인은 남아 있다. 통합 결과는 세 구현별로 보고하며 일부 환경이 준비되지 않으면 부분 완료로 표시한다.

문서 개편 후 기존·계획된 보장의 누락을 검토하고 기존 suite를 재실행한 결과는 [회귀 검토](regression-review.md)에 기록한다. 위 새 시나리오를 문서에 추가한 사실과 그 시나리오를 구현·실행하여 통과한 사실은 구별한다.

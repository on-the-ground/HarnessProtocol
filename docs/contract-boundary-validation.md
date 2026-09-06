# G01–G12 실제 계약 경계 검증

실제 SDK/runtime에 통제된 모델 응답을 공급하며, 전달 지연·유실은 명시한 경계에만 주입한다. 검증 수는 JUnit 실행 결과이며 미지원 기능이나 실행되지 않은 공통 정의를 통과로 세지 않는다.

G01–G12의 현재 구성 검증은 모두 닫혔다. 각 단계의 통과는 아래 명시한 구성과 경계의 증거이며, 제공하지 않는 선택 기능의 성공 경로나 외부 실모델 검증까지 뜻하지 않는다. 원래 Core/Cleanup 목록의 처리 근거는 [시나리오 목록](conformance-scenarios.md)을 따른다.

## G01 — 요구 수락과 사전 거절

`ProfileFixture`의 고정된 provider 구성과 요구 사례를 `HarnessRequirementsConformanceTest`가 판정한다. 기대값은 adapter의 `support`·`validate` 결과에서 만들지 않는다. 각 사례는 사전 검증을 거치는 경로와 곧바로 호출하는 경로에서 실행하며, 수락된 작업은 실제 모델 경계까지 도달하고 거절된 요구는 모델 호출 전에 끝나야 한다.

현재 행렬은 Codex 두 구성, Gemini CLI 두 구성, Koog 한 구성의 요구 55개를 다룬다. preflight/direct 실행 110개와 profile 지원표 5개, 총 115개가 최종 native 집계에 포함된다. 영속성·승인·질문·진단·workspace·실행 제약·구조화 산출물의 지원과 사전 거절을 구별한다. 현재 구성에 정직한 `UNCONFIRMED` 요구 사례가 없으므로 해당 분기를 통과로 세지 않는다.

## G02 — 시작·응답 수락 확인 유실

Codex·Gemini의 실제 `JsonLineProcessBridge` 경계에서 요청 전 미전달과 host 수락 뒤 acknowledgement 유실을 구별한다. 미전달 뒤에는 안전하게 다시 시도할 수 있다. 시작 수락을 확인하지 못하면 `TaskStartUnconfirmedException`과 요청 identity를 회수하고 같은 문맥의 재제출을 차단한다. Codex 승인 응답에서는 `InteractionResponseUnconfirmedException`, pending 정리, 중복 제출 방지와 실제 파일 효과를 함께 확인한다.

공통 정의 7개가 실제 binding에서 12회 실행되고 Koog의 로컬 비중단 handoff 검사 1개가 추가된다. Koog에는 별도 수락 통신 단계를 합성하지 않으며 Gemini·Koog의 미지원 승인 경로도 가짜 요청으로 대체하지 않는다. G02의 13개는 최종 native 집계에 포함된다.

## G03 — 상호작용 경쟁

실제 Codex 명령 승인을 발생시키고 격리된 파일의 효과를 관찰했다. 공통 `HarnessInteractionConformanceTest`의 5개 정의를 연결했다.

- 잘못된 응답 종류·제공되지 않은 선택은 native 전달 전에 거절된다. 이후 올바른 응답은 정상 처리된다.
- 응답 전달을 보류한 동안 호출자 coroutine을 취소해도 단일 제출 잠금이 유지된다. 실제 효과는 한 번이다.
- 승인 대기 중 취소하면 pending이 비워지고 늦은 승인이 거절된다. 미승인 파일 효과는 없다.
- 응답 전달 전 취소·종결이 확정된 뒤 늦은 응답을 전달해도 종결·pending·효과가 바뀌지 않는다. resolution과 terminal은 각각 한 번이다.
- 정상 완료 뒤 중복 응답·취소는 효과나 outcome을 바꾸지 않는다.

5개 통과. 실행 증거. Port·production 구현 수정은 필요하지 않았다.

적용 범위: 현재 Gemini·Koog 구성은 호출자 승인/질문 요구를 사전 거절한다(G01). Codex host가 실제 발생시키는 clear는 응답·취소·종결이다. 독립적인 질문·provider withdrawal·supersession을 발생시키는 native 경로는 이 구성에서 제공되지 않으며, mapper가 해당 어휘를 해석할 수 있다는 사실을 native 실증으로 세지 않는다. 지원 경로가 추가되면 해당 경쟁 검증도 필요하다.

## G04 — 문맥 공유와 차단

영속 저장을 지원하는 Codex·Gemini에 공통 3개 정의를 연결해 6개를 실행했다. 같은 문맥을 다시 연 핸들에서 중첩 시작·실행 중 reopen이 제공 경계 전에 거절되며, 시작 수락 미확정은 모든 별칭과 같은 애플리케이션 프로세스의 하네스 재생성 뒤에도 차단된다. 독립 문맥의 실행은 계속 가능하다.

첫 실행에서 두 adapter 모두 원래 핸들의 `release`가 아직 살아 있는 별칭의 native 세션을 지우는 결함이 드러났다. 공통 process runtime이 문맥의 살아 있는 핸들 수를 관리하고 마지막 핸들 해제에만 native 자원을 반납하도록 수정했다. 이미 해제된 핸들은 계속 거절하며, 다른 핸들의 정상 시작은 보존한다.

최초 실패 2개, 수정 후 6개 통과. Koog 기본 구성은 영속 재개를 지원하지 않아 G01 거절 검증이 적용된다. 애플리케이션 프로세스 재시작·동시 writer까지 보장 범위를 넓히지 않았다.

## G05 — 네 outcome의 산출물 보존

공통 6개 정의를 세 runtime에 연결해 18개 통과. 모델이 보낸 부분 텍스트를 실제 runtime이 공개한 다음 완료·실패·취소·종료 미확정을 유도하고 동일 텍스트를 outcome에서 회수한다. 실패·취소·미확정의 부분 산출물은 `complete=false`다. Process adapter의 미확정은 실제 stream 관찰의 차단, Koog는 실제 graph 도구가 취소에 협조하지 않는 상태에서 bounded release로 유도했다.

빈 응답은 native API가 받는 것과 adapter가 확보하는 것을 구별한다. Codex는 산출물 없는 Completed와 실제 빈 텍스트 Completed를 각각 제공한다. Koog는 빈 텍스트를 제공하지만 조각이 전혀 없는 응답은 graph에서 실패한다. Gemini SDK는 빈/무내용 모델 응답을 실패로 처리하고 empty content 이벤트를 제공하지 않는다. 이 경우 Failed와 null output을 보존하며 빈 텍스트를 합성하지 않는다.

최초 실행의 5개 실패 중 2개는 native 재시도마다 fixture가 같은 텍스트를 다시 보냈기 때문이었다. 재시도에는 출력 전 HTTP 400을 반환하도록 유도 경계를 수정했다. 나머지 3개는 모든 runtime이 빈 응답을 정상 완료로 받는다는 잘못된 fixture 가정이었다. Port/production 구현은 바꾸지 않았다. 수치 사용량의 구간 합산·누락·snapshot 검증은 G11에서 별도로 다룬다.

## G06 — 다중 자원 정리와 늦은 효과

공통 3개 정의를 세 runtime에 연결한 9개와 Koog 도구 검사 3개가 모두 통과했다. 12개 중 기존 Koog 검사 2개는 회귀 재실행이며 신규 검사 수에 더하지 않는다.

네 session의 실제 모델 호출을 보류한 채 close해 전체 상한 안의 종결 회수·닫힌 핸들 거절·terminal 보존을 확인했다. release 호출자의 coroutine 취소에도 정리가 진행되고, 다른 작업의 정리가 이미 Completed인 outcome을 바꾸지 않는다. process 정리에는 전체 상한에 500ms의 관측 허용치를 적용하며 자원 수에 따라 늘리지 않는다.

Koog에서는 네 실제 도구를 NonCancellable 상태로 보류하고 200ms per-task / 300ms total 설정에서 close가 450ms 안에 끝나는지 검증했다. 네 outcome 모두 Unresolved이고 아직 파일 효과가 없으며, 도구를 풀면 네 파일에 실제 효과가 발생해도 기존 outcome은 유지된다. 관찰만 끊어졌다는 이유로 Cancelled를 합성하지 않는다. 이 실행에서 production 수정은 필요하지 않았다.

## G07 — 의미 이벤트와 진단의 독립 과부하

공통 1개 정의를 세 runtime에 연결해 3개 통과. Codex·Gemini는 실제 모델 스트림 700조각, Koog는 실제 graph 도구 400회를 수행한다. 의미/진단의 빠른 구독자와 첫 이벤트에서 멈춘 구독자를 동시에 연결한다. 느린 두 구독자가 모두 멈춰 있어도 작업은 Completed에 도달하고, 각각의 전달 건수와 gap 건수 합은 빠른 구독자의 관측과 일치한다. 의미 terminal은 마지막에 정확히 한 번 남는다.

최초 실행은 Gemini의 반복 감지와 Koog graph의 텍스트 종결 우선 규칙에 의해 부하 생성이 조기 종료됐다. Gemini 조각을 구별 가능한 값으로 만들고, Koog 공식 singleRunStrategy의 중간 응답을 tool-only로 구성했다. 그래프의 반복 상한도 충분히 설정했다. runtime의 정상 종료 규칙을 바꾸거나 Port에 인위적인 이벤트를 주입하지 않았다.

## G08 — 승인 범위

현재 Codex 연결은 native acceptForSession의 허용 범위를 공통 필드로 설명·집행할 수 없으므로 세션 승인 선택지를 제공하지 않는다. Gemini·Koog는 승인 채널 자체를 지원하지 않는다(G01). 세션 grant의 범위 안/밖 허용 실증은 이 구성에 존재하지 않는 기능이며 통과로 세지 않는다.

Codex에 연결한 공통 3개 검사가 통과했다. sessionGrant와 APPROVE_FOR_SESSION이 노출되지 않으며 억지로 제출해도 native 전달 전에 거절된다. APPROVE_ONCE로 실제 파일 효과를 한 번 허용한 뒤 같은 session의 다음 Task와 독립 session에서 같은 명령이 다시 승인을 요구한다. 두 번째 요청을 거절하면 파일 효과 수는 한 번으로 유지되고 Task 자체는 정상 완료할 수 있다. `APPROVE_FOR_SESSION`의 공개 계약을 삭제하거나 일회 승인을 세션 권한으로 확대하지 않았다.

## G09 — 활성화와 실행 제약

첫 실행 5개 중 4개 실패는 두 모호성을 드러냈다. `$name` 같은 activation envelope만 전달해서는 active skill 본문이 모델 문맥에 적용됐음을 증명하지 못했고, 여러 파일·네트워크 효과를 한 명령에 묶으면 명령 전체의 거절을 sandbox 집행 증거로 오판할 수 있었다.

계약을 다음과 같이 확정했다.

1. `SkillReference.activate=true`는 모든 Task의 실제 native 지시에 skill 본문을 적용한다는 보장이다. 이름이나 경로만 제공하는 것으로 충족하지 않는다. `activate=false`인 skill은 사용할 수 있게 제공하되 본문을 강제로 적용하지 않는다.
2. `ExecutionConstraint.Required`는 승인이 넓힐 수 없는 hard upper bound다. 승인 정책은 그 경계 안의 효과를 더 거절할 수 있다. adapter가 임의 명령의 실제 접근 범위를 판별하지 못하면 제한 제약과 확장 가능한 승인 정책의 조합을 작업 전에 거절한다.

Codex·Gemini process adapter는 작업 폴더와 각 skill directory/`SKILL.md`를 실제 시작 전에 검증하고, active 본문을 native 지속 지시에 포함한다. 원래 Task 입력은 skill 명령을 붙이지 않고 그대로 보낸다. 두 adapter의 workspace 검사 4개는 두 Task 모두에서 active 본문·작업 위치·active/inactive 이름과 경로를 확인하고 inactive 본문의 부재 및 잘못된 artifact의 사전 거절을 확인한다. Koog 기본 구성은 workspace/skill 요구를 거절한다.

Codex의 제한 실행은 `DenyAll`과 결합한 세 실제 효과 검사로 확인했다. ReadOnly는 쓰기를 노출하지 않고, WorkspaceWrite는 허용 root 밖 쓰기와 network를 허용하지 않으며, network 허용이 filesystem 상한을 넓히지 않는다. `DenyAll`이 상한 안 효과도 거절할 수 있음은 승인과 실행 제약이 독립임을 보여 준다. Codex의 `CallerDecides`·`AgentReviewed`와 제한 실행 제약 조합, Gemini·Koog의 명시적 실행 제약은 집행할 수 없어 요구 판정에서 사전 거절한다.

G09 workspace/실행 7개와 갱신된 요구 판정 115개를 함께 실행한 122개 결과는 모두 통과했다. 복합 명령의 “아무 효과도 없음”을 개별 상한의 증거로 재사용하지 않았다.

## G10 — 구조화 산출물 요구의 적용 범위

현재 세 production 구성은 schema를 집행하는 실행 경로를 제공하지 않는다. 따라서 이 단계의 적합성 조건은 JSON처럼 보이는 텍스트를 Structured로 포장하는 것이 아니라, 구조화 산출물 요구를 작업 시작 전에 거절하는 것이다. `validatedByHarness=false`도 산출물 형태 요구를 제거하지 않는다.

갱신된 요구 판정 증거의 세 native 구성 × 검증 책임 두 종류 × preflight/direct = 12개 요구 검사가 이 조건을 검증한다. 다섯 profile의 지원 표 검사도 미지원을 확인한다. 115개 요구 판정 실행에 포함된 검사이며 신규 12개로 더하지 않는다. 선택 기능을 새로 구현하거나, 미지원 선택 보장을 기본 계약으로 강제하지 않았다. 향후 schema 실행 경로를 추가하면 VALID/INVALID/NOT_VALIDATED와 부분 산출물의 실제 의미를 별도 실증해야 한다.

## G11 — 사용량의 측정 범위와 보존

공통 사용량 검사 2개를 세 runtime에 연결한 6개, Koog의 여러 모델 호출 구간 검사 1개, G05 산출물·사용량 회귀 18개가 모두 통과했다. 신규 실행은 7개이며 G05의 18개를 다시 더하지 않는다.

같은 session의 세 Task에서 서로 다른 측정값과 실제 측정된 0을 제공해 Task 합계의 초기화와 마지막 UsageChanged/outcome 일치를 확인했다. Codex의 session 누적값은 Task 값과 구별되며, 같은 native 사용량 알림을 재전달해도 중복 합산하지 않는다. Gemini·Koog가 제공하지 않는 session 합계는 null이다. 첫 측정 전에 취소하면 Unknown을 유지한다.

Koog의 실제 두 도구 호출을 포함한 세 모델 호출에서는 일부 구간의 output/total 측정이 누락되면 뒤의 측정된 0으로 합계를 복원하지 않는다. 네 종결 outcome에서 마지막 공개 사용량 snapshot을 보존하며, Koog에는 독립적으로 정한 부분 측정값 10/5/15도 대조했다. Process adapter의 실패·취소·미확정에는 수치 부분 측정을 새로 합성하지 않고 마지막 공개 snapshot과의 일치를 확인했다. 모든 provider의 모든 누락 구간을 같은 방식으로 주입했다고 주장하지 않는다. Port·production 수정은 필요하지 않았다.

통합 회귀 뒤 기존 검사에 관찰 identity 판정을 보강해 12개를 다시 실행, 모두 통과했다. 세 runtime의 일반 텍스트 응답에서 관찰 가능한 역할(Codex ANSWER, Gemini UNKNOWN, Koog UNKNOWN/ANSWER)을 독립 기대값으로 대조하고, 같은 메시지의 delta와 완료 snapshot이 같은 ID·텍스트를 유지하는지 확인했다. Koog의 실제 두 도구 호출은 서로 다른 ID를 쓰고 각각 시작/완료가 대응한다. Codex의 실제 승인 대상과 완료된 파일 효과도 같은 workId를 갖는다. Gemini의 실제 도구/효과 상관관계나 모든 설명·commentary 변형까지 새로 실증한 것은 아니며 해당 mapper 회귀와 구별한다. 기존 G11 7개·G03 5개를 보강한 재실행이라 고유 검사 수는 늘지 않았다.

## G12 — 영속 저장 장애와 설정 보존

공통 3개 정의 × 두 영속 runtime = 6개와 G04 회귀 6개가 모두 통과했다. 모르는 참조를 새 session으로 바꾸지 않고 거절한다. 실제 임시 저장소의 이력 파일을 잠시 다른 이름으로 옮기면 reopen은 명시적으로 실패하며, 파일을 복원하면 원래 입력 문맥과 같은 영속 참조를 회수한다. fixture 밖의 파일은 변경하지 않는다.

최초 실패에서 Gemini의 reopen이 살아 있는 다른 핸들의 선언된 설정을 조용히 덮어쓰는 결함을 발견했다. 공통 process runtime은 살아 있는 핸들의 spec과 다른 설정의 reopen을 거절한다. 기존 핸들을 해제하면 Gemini는 새 설정을 적용할 수 있고 Codex의 구성 변경 미지원은 유지된다. 다른 실패는 Gemini 저장 형식이 `.jsonl`인데 fixture가 `.json`만 찾은 것이어서 실제 저장 파일 선택을 수정했다.

범위는 명시된 동일 애플리케이션 프로세스다. G04에서 미확정 문맥 차단의 하네스 재생성 후 보존, G01에서 프로세스 재시작·동시 writer 요구의 사전 거절을 검증한다. 영속 지원을 외부 저장소의 항상 가용함으로 해석하지 않으며, 저장 접근 실패를 정상 재개로 숨기지 않는다. 별도의 동적 capability 변경 통로가 없는 구성에 지원 철회 알림을 합성하지 않았다.

## 최종 통합 회귀 checkpoint

`./gradlew.bat --offline test :harness-conformance:testFixturesClasses hostTests -PnativeHarnessTests -PstrictHostTests --continue --console=plain`을 실행했다. [최종 JVM·host 집계](../verification/native-integration/evidence/final-regression-summary.json)는 JVM 320개와 host 25개가 실패 없이 통과했음을 기록한다. 오류와 건너뜀도 0개다. JVM 집계에는 Codex·Gemini CLI·Koog의 실제 SDK/runtime에 통제된 모델 경계를 연결한 [native 검사 223개](../verification/native-integration/evidence/final-g01-g12.json)가 포함된다. Python Codex host 20개와 Node Gemini host 5개도 strict 조건으로 통과했다.

이전 실패 checkpoint의 JVM 4개 실패는 G09의 active skill 본문 부재와 복합 명령 유도 오류를 드러냈다. 계약·구현·검사를 수정한 뒤 G09 집중 실행 110개와 최종 통합 회귀가 모두 통과했다. G04·G12의 production 문맥 수정, G03·G11의 identity 보강, 원래 44개 미연결 본문의 [목적별 종결](conformance-scenarios.md)도 최종 소스 상태에 포함된다. Kotlin 소스·sample·독립 실험에는 legacy Port 및 제거한 참조 하네스/`AgentHarnessContractTest` 참조가 없다.

이 checkpoint는 현재 저장소에 고정된 세 adapter 구성과 통제된 모델 경계의 증거다. 외부 실모델 호출, 현재 구성이 거절하는 선택 기능의 성공 경로, artifact 발행과 독립 Koog 실험은 집계에 포함하지 않았다. 단계별 재실행은 최종 고유 검사 수에 더하지 않았다.

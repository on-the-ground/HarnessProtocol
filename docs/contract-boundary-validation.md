# G03–G12 실제 계약 경계 검증

이 기록은 [기존 계획](port-revision-plan.md)의 후속 단계다. 실제 SDK/runtime에 통제된 모델 응답을 공급하며, 전달 지연·유실은 명시한 경계에만 주입한다. 검증 수는 JUnit 실행 결과이며 미지원 기능이나 실행되지 않은 공통 정의를 통과로 세지 않는다. 전체 회귀 수는 최종 전체 실행에서 다시 산정한다.

## G03 — 상호작용 경쟁

실제 Codex 명령 승인을 발생시키고 격리된 파일의 효과를 관찰했다. 공통 `HarnessInteractionConformanceTest`의 5개 정의를 연결했다.

- 잘못된 응답 종류·제공되지 않은 선택은 native 전달 전에 거절된다. 이후 올바른 응답은 정상 처리된다.
- 응답 전달을 보류한 동안 호출자 coroutine을 취소해도 단일 제출 잠금이 유지된다. 실제 효과는 한 번이다.
- 승인 대기 중 취소하면 pending이 비워지고 늦은 승인이 거절된다. 미승인 파일 효과는 없다.
- 응답 전달 전 취소·종결이 확정된 뒤 늦은 응답을 전달해도 종결·pending·효과가 바뀌지 않는다. resolution과 terminal은 각각 한 번이다.
- 정상 완료 뒤 중복 응답·취소는 효과나 outcome을 바꾸지 않는다.

5개 통과. [실행 증거](../harness-native-integration/evidence/g03-interaction.json). Port·production 구현 수정은 필요하지 않았다.

적용 범위: 현재 Gemini·Koog 구성은 호출자 승인/질문 요구를 사전 거절한다(G01). Codex host가 실제 발생시키는 clear는 응답·취소·종결이다. 독립적인 질문·provider withdrawal·supersession을 발생시키는 native 경로는 이 구성에서 제공되지 않으며, mapper가 해당 어휘를 해석할 수 있다는 사실을 native 실증으로 세지 않는다. 지원 경로가 추가되면 해당 경쟁 검증도 필요하다.

## G04 — 문맥 공유와 차단

영속 저장을 지원하는 Codex·Gemini에 공통 3개 정의를 연결해 6개를 실행했다. 같은 문맥을 다시 연 핸들에서 중첩 시작·실행 중 reopen이 제공 경계 전에 거절되며, 시작 수락 미확정은 모든 별칭과 같은 애플리케이션 프로세스의 하네스 재생성 뒤에도 차단된다. 독립 문맥의 실행은 계속 가능하다.

첫 실행에서 두 adapter 모두 원래 핸들의 `release`가 아직 살아 있는 별칭의 native 세션을 지우는 결함이 드러났다. 공통 process runtime이 문맥의 살아 있는 핸들 수를 관리하고 마지막 핸들 해제에만 native 자원을 반납하도록 수정했다. 이미 해제된 핸들은 계속 거절하며, 다른 핸들의 정상 시작은 보존한다.

[최초 실패 2개](../harness-native-integration/evidence/g04-context-first-run.json), [수정 후 6개 통과](../harness-native-integration/evidence/g04-context.json). Koog 기본 구성은 영속 재개를 지원하지 않아 G01 거절 검증이 적용된다. 애플리케이션 프로세스 재시작·동시 writer까지 보장 범위를 넓히지 않았다.

## G05 — 네 outcome의 산출물 보존

공통 6개 정의를 세 runtime에 연결해 [18개 통과](../harness-native-integration/evidence/g05-outcome.json). 모델이 보낸 부분 텍스트를 실제 runtime이 공개한 다음 완료·실패·취소·종료 미확정을 유도하고 동일 텍스트를 outcome에서 회수한다. 실패·취소·미확정의 부분 산출물은 `complete=false`다. Process adapter의 미확정은 실제 stream 관찰의 차단, Koog는 실제 graph 도구가 취소에 협조하지 않는 상태에서 bounded release로 유도했다.

빈 응답은 native API가 받는 것과 adapter가 확보하는 것을 구별한다. Codex는 산출물 없는 Completed와 실제 빈 텍스트 Completed를 각각 제공한다. Koog는 빈 텍스트를 제공하지만 조각이 전혀 없는 응답은 graph에서 실패한다. Gemini SDK는 빈/무내용 모델 응답을 실패로 처리하고 empty content 이벤트를 제공하지 않는다. 이 경우 Failed와 null output을 보존하며 빈 텍스트를 합성하지 않는다.

[최초 실행](../harness-native-integration/evidence/g05-outcome-first-run.json)의 5개 실패 중 2개는 native 재시도마다 fixture가 같은 텍스트를 다시 보냈기 때문이었다. 재시도에는 출력 전 HTTP 400을 반환하도록 유도 경계를 수정했다. 나머지 3개는 모든 runtime이 빈 응답을 정상 완료로 받는다는 잘못된 fixture 가정이었다. Port/production 구현은 바꾸지 않았다. 수치 사용량의 구간 합산·누락·snapshot 검증은 G11에서 별도로 다룬다.

## G06 — 다중 자원 정리와 늦은 효과

공통 3개 정의를 세 runtime에 연결한 9개와 Koog 도구 검사 3개가 [모두 통과](../harness-native-integration/evidence/g06-cleanup.json)했다. 12개 중 기존 Koog 검사 2개는 회귀 재실행이며 신규 검사 수에 더하지 않는다.

네 session의 실제 모델 호출을 보류한 채 close해 전체 상한 안의 종결 회수·닫힌 핸들 거절·terminal 보존을 확인했다. release 호출자의 coroutine 취소에도 정리가 진행되고, 다른 작업의 정리가 이미 Completed인 outcome을 바꾸지 않는다. process 정리에는 전체 상한에 500ms의 관측 허용치를 적용하며 자원 수에 따라 늘리지 않는다.

Koog에서는 네 실제 도구를 NonCancellable 상태로 보류하고 200ms per-task / 300ms total 설정에서 close가 450ms 안에 끝나는지 검증했다. 네 outcome 모두 Unresolved이고 아직 파일 효과가 없으며, 도구를 풀면 네 파일에 실제 효과가 발생해도 기존 outcome은 유지된다. 관찰만 끊어졌다는 이유로 Cancelled를 합성하지 않는다. 이 실행에서 production 수정은 필요하지 않았다.

## G07 — 의미 이벤트와 진단의 독립 과부하

공통 1개 정의를 세 runtime에 연결해 [3개 통과](../harness-native-integration/evidence/g07-observation.json). Codex·Gemini는 실제 모델 스트림 700조각, Koog는 실제 graph 도구 400회를 수행한다. 의미/진단의 빠른 구독자와 첫 이벤트에서 멈춘 구독자를 동시에 연결한다. 느린 두 구독자가 모두 멈춰 있어도 작업은 Completed에 도달하고, 각각의 전달 건수와 gap 건수 합은 빠른 구독자의 관측과 일치한다. 의미 terminal은 마지막에 정확히 한 번 남는다.

[최초 실행](../harness-native-integration/evidence/g07-observation-first-run.json)은 Gemini의 반복 감지와 Koog graph의 텍스트 종결 우선 규칙에 의해 부하 생성이 조기 종료됐다. Gemini 조각을 구별 가능한 값으로 만들고, Koog 공식 singleRunStrategy의 중간 응답을 tool-only로 구성했다. 그래프의 반복 상한도 충분히 설정했다. runtime의 정상 종료 규칙을 바꾸거나 Port에 인위적인 이벤트를 주입하지 않았다.

## G08 — 승인 범위

현재 Codex 연결은 native acceptForSession의 허용 범위를 공통 필드로 설명·집행할 수 없으므로 세션 승인 선택지를 제공하지 않는다. Gemini·Koog는 승인 채널 자체를 지원하지 않는다(G01). 세션 grant의 범위 안/밖 허용 실증은 이 구성에 존재하지 않는 기능이며 통과로 세지 않는다.

Codex에 연결한 공통 [3개 검사](../harness-native-integration/evidence/g08-approval-scope.json)가 통과했다. sessionGrant와 APPROVE_FOR_SESSION이 노출되지 않으며 억지로 제출해도 native 전달 전에 거절된다. APPROVE_ONCE로 실제 파일 효과를 한 번 허용한 뒤 같은 session의 다음 Task와 독립 session에서 같은 명령이 다시 승인을 요구한다. 두 번째 요청을 거절하면 파일 효과 수는 한 번으로 유지되고 Task 자체는 정상 완료할 수 있다. `APPROVE_FOR_SESSION`의 공개 계약을 삭제하거나 일회 승인을 세션 권한으로 확대하지 않았다.

## G10 — 구조화 산출물 요구의 적용 범위

현재 세 production 구성은 schema를 집행하는 실행 경로를 제공하지 않는다. 따라서 이 단계의 적합성 조건은 JSON처럼 보이는 텍스트를 Structured로 포장하는 것이 아니라, 구조화 산출물 요구를 작업 시작 전에 거절하는 것이다. `validatedByHarness=false`도 산출물 형태 요구를 제거하지 않는다.

[G01 실행 증거](../harness-native-integration/evidence/requirement-admission.json)의 세 native 구성 × 검증 책임 두 종류 × preflight/direct = 12개 요구 검사가 이 조건을 검증한다. 다섯 profile의 지원 표 검사도 미지원을 확인한다. 기존 91개 실행에 포함된 검사이며 신규 12개로 더하지 않는다. 선택 기능을 새로 구현하거나, 미지원 선택 보장을 기본 계약으로 강제하지 않았다. 향후 schema 실행 경로를 추가하면 VALID/INVALID/NOT_VALIDATED와 부분 산출물의 실제 의미를 별도 실증해야 한다.

## G12 — 영속 저장 장애와 설정 보존

공통 3개 정의 × 두 영속 runtime = 6개와 G04 회귀 6개가 [모두 통과](../harness-native-integration/evidence/g12-persistence.json)했다. 모르는 참조를 새 session으로 바꾸지 않고 거절한다. 실제 임시 저장소의 이력 파일을 잠시 다른 이름으로 옮기면 reopen은 명시적으로 실패하며, 파일을 복원하면 원래 입력 문맥과 같은 영속 참조를 회수한다. fixture 밖의 파일은 변경하지 않는다.

[최초 실패](../harness-native-integration/evidence/g12-persistence-first-run.json)에서 Gemini의 reopen이 살아 있는 다른 핸들의 선언된 설정을 조용히 덮어쓰는 결함을 발견했다. 공통 process runtime은 살아 있는 핸들의 spec과 다른 설정의 reopen을 거절한다. 기존 핸들을 해제하면 Gemini는 새 설정을 적용할 수 있고 Codex의 구성 변경 미지원은 유지된다. 다른 실패는 Gemini 저장 형식이 `.jsonl`인데 fixture가 `.json`만 찾은 것이어서 실제 저장 파일 선택을 수정했다.

범위는 명시된 동일 애플리케이션 프로세스다. G04에서 미확정 문맥 차단의 하네스 재생성 후 보존, G01에서 프로세스 재시작·동시 writer 요구의 사전 거절을 검증한다. 영속 지원을 외부 저장소의 항상 가용함으로 해석하지 않으며, 저장 접근 실패를 정상 재개로 숨기지 않는다. 별도의 동적 capability 변경 통로가 없는 구성에 지원 철회 알림을 합성하지 않았다.

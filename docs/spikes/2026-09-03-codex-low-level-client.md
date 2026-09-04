# Codex 저수준 client 조사 근거

조사일: 2026-09-03. 대상: 설치본 openai-codex 0.147.0. 결과는 **코드 수준의 연결 가능성 확인**이며 실제 App Server 승인 payload fixture는 해당 조사에서 확보하지 못했다.

이 문서는 현재 Codex host의 구현 선택 근거다. 새 AHP의 목적·필수 기능을 결정하는 기준은 [Semantic contract](../semantic-contract.md)이며, SDK별 수단은 [Provider mapping](../provider-mapping.md)에 둔다. 이번 문서 정리에서 SDK 조사를 다시 실행하지 않았다.

## 조사 질문과 관찰

공개 client 경로로 지시·정책을 정확히 전달하고 작업 관찰·승인 응답·중단을 구현할 수 있는지 조사했다.

| 항목 | 당시 확인한 사실 |
|---|---|
| 고수준 Codex/AsyncCodex | 내부에서 handler 없이 CodexClient를 생성. caller handler 주입 경로 없음 |
| thread_start 기본 approval | auto_review 기본값이 지정되어 필드 생략 의도를 그대로 전달하기 어려움 |
| openai_codex.client.CodexClient | 공개 모듈과 생성자에 approval_handler가 있으나 패키지 최상위로 재수출되지 않음 |
| thread_start(params) | dict의 approval 관련 field를 생략하여 wire에 보내지 않을 수 있음 |
| thread_resume | 기존 thread를 여는 공개 client 경로 |
| turn_start / next_turn_notification | 작업 시작과 turn별 notification queue 수신 |
| turn_interrupt | 공개 중단 요청 |
| handler 실행 위치 | reader thread에서 동기 호출 |
| 기본 handler | command/file approval에 accept 반환 |
| approval generated 모델 | server 요청/응답 타입 미확인. raw dict와 App Server 문서 기반 결정 값 사용 |
| requestUserInput | 조사한 generated 타입에서 확인되지 않음 |
| 바이너리 | openai-codex-cli-bin 의존성에 포함 |

이 사실은 당시 고정 버전에 한정한다. provider native의 thread/turn은 내부 용어이며 공개 AgentSession/AgentTask와 동일 개념이라고 가정하지 않는다.

## 현 host가 선택한 방식

- CodexClient를 사용하고 기본 정책을 요청한 경우 approval wire 필드를 생략한다.
- caller 판단이 필요한 경로에서는 자체 handler로 요청을 전달하며 SDK의 자동 accept를 그대로 사용하지 않는다.
- reader를 막고 있는 handler의 pending 결정 대기를 먼저 풀고, 반환을 확인한 뒤 interrupt/close한다. 실제 wire write와 interrupt의 순서는 SDK 특성과 함께 검증한다.
- 고정 버전의 client 경계를 host 테스트로 검사한다.

이 중재 순서는 해당 SDK의 구현 책임이다. 모든 하네스가 reader thread·queue·process를 가져야 한다는 공통 계약이 아니다. interrupt 호출 성공이나 host 종료만으로 Task의 실제 취소를 확정하지 않는다.

## 증거의 범위와 남은 작업

기존 bridges/tests/test_codex_bridge.py는 실제 CodexClient와 stub App Server를 연결하여 승인 왕복과 handler 해제 순서를 검사한다. 실제 App Server payload·허용 결정·종료 확인 근거는 실제 연동에서 확인해야 한다. 조사 당시 문서 기준 decision 네 종류를 모든 환경의 보장으로 간주하지 않는다.

질문 경로를 이 SDK에서 찾지 못했다는 사실은 AHP에서 질문 목적을 배제할 이유가 아니다. 새 InteractionRequest.Question/InteractionResponse.Answer 요구를 해당 adapter가 제공할 수 있는지 별도로 조사하고 미지원이면 명시적으로 거절한다.

새 TaskOutcome·진단 분리·영속성 선택 계약으로 전환할 때 이 host의 구현과 KDoc을 함께 갱신한다. [Testing](../testing.md)의 공통 적합성, SDK 변환, 실제 연동을 구분하여 기록한다.

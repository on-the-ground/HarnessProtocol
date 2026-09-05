# Bridge protocol

이 문서는 **현재 Codex/Gemini process adapter의 내부 NDJSON 형식**과 개정 시 필요한 변경을 설명한다. 공개 AHP 계약은 [Protocol reference](protocol-reference.md)를 따른다. 로컬 라이브러리·서버·클라우드 adapter가 이 bridge를 구현할 의무는 없다.

## 현재 내부 transport와 메시지

Host는 stdin 요청과 stdout 응답·이벤트를 한 줄에 하나의 JSON 객체로 교환한다. stderr는 진단용이고 현재 Kotlin bridge가 앞부분 최대 32KB를 보관한다. Gemini는 SDK import 전에 Console 출력을 stderr로 연결해 native 로그가 NDJSON을 오염하지 않게 한다. host는 lazy start하며 현재 bridge는 예기치 않은 종료 뒤 자동 재시작하지 않는다.

다음 wire 이름은 현 구현 그대로다. 공개 Task 이름 전환이 내부 wire의 동시 rename을 강제하지는 않는다.

| method | params | result |
|---|---|---|
| create_session | spec envelope | sessionId, 관측 가능한 retention/historyVisibility |
| resume_session | sessionId, spec | provider가 정규화한 sessionId, 관측 가능한 retention/historyVisibility |
| release_session | sessionId | 빈 객체. Codex ephemeral session은 provider-native thread 삭제 확인 뒤 반환 |
| discard_session | 생성 요구 위반으로 반환된 sessionId | provider-native thread 삭제 확인 또는 error |
| start_execution | sessionId, input(type=text, text) | executionId |
| cancel_execution | executionId | 빈 객체 |
| respond_interaction | executionId, interactionId, response(decision) | 빈 객체 또는 error |

현재 envelope는 instructions/model/workingDirectory, skills(name/path/activate), filesystem/additionalWritableRoots/network/approval과 선택적 retention을 사용한다. Codex의 `retention=ephemeral`은 `thread/start.ephemeral=true`로 전달하고 응답의 `thread.ephemeral`을 `ephemeral` 또는 `materialized`로 반환한다. user-history visibility는 SDK 응답에 독립 관측이 없어 `unknown`이다. 관측 필드를 제공하지 않는 host는 adapter에서 UNKNOWN으로 보존한다. 생략과 빈 문자열의 차이를 보존하며 Gemini 경로는 앞선 validation에서 지원하지 않는 policy를 거절한다. 이전 host decision에는 approve_for_session이 남아 있지만 새 Port mapper는 명시적인 승인 범위를 받지 못한 요청에 그 선택지를 제공하지 않는다.

새 process adapter는 요청 전 미전달과 전달 후 수락 미확정을 구별하는 `ConfirmedSdkBridge` 경로를 사용한다. start 수락 확인 유실은 공개 `TaskStartUnconfirmedException`과 문맥 차단으로 연결한다. 작업 중 EOF는 기본적으로 관찰 유실이며 이미 확보한 종결 근거 없이 실패·취소를 합성하지 않는다. close는 stdin EOF로 host의 정상 정리를 유도한 뒤 소유한 자식 process까지 정리한다. 실제 연동에서 확인한 변경 근거는 [검증 기록](native-port-validation.md)에 있다.

```json
{"kind":"request","id":1,"method":"release_session","params":{"sessionId":"session-1"}}
{"kind":"response","id":1,"result":{}}
{"kind":"response","id":2,"error":{"type":"ValueError","message":"invalid request"}}
{"kind":"event","executionId":"turn-1","payload":{}}
```

Payload는 Codex method/payload, Gemini type/value 등의 원본과 host 합성 알림을 담는다. Codex interaction_requested/resolved는 handler 중재를, Gemini execution_started/completed/failed/cancelled는 현 host의 실행 관찰을 전달한다. 합성 알림을 실제 종료 확인의 증거로 쓸 수 있는지는 별도로 검증해야 한다.

## 유지할 전달 책임

1. 작업 시작 응답보다 먼저 온 이벤트도 식별자별로 보관·라우팅한다.
2. 중복·늦은 terminal로 public outcome을 덮어쓰지 않는다. 내부 전송 오류와 작업 결과를 구별한다.
3. 응답이 전달되기 전에 pending entry를 성공 처리하지 않는다. 중복·만료·잘못된 응답은 거절한다.
4. Codex reader thread를 막는 승인 handler는 결정을 전달해 대기를 푼 뒤 interrupt/close해야 한다. 이 순서는 해당 SDK의 구현 책임이다.
5. provider 기본 handler의 자동 accept로 caller 승인 요구를 우회하지 않는다.

시작·응답 요청을 host가 받았지만 Kotlin이 acknowledgement를 잃을 수 있다. 이때 호출 실패를 확정적인 미수행·미응답으로 바꾸거나 새 요청으로 자동 재전송하지 않는다. 새 경로는 전달 상태와 요청 identity를 미확정 예외로 보존하며, 실제 native 경계에서 확인 유실을 유도하는 검증은 남아 있다.

## 현재 의미 경계

새 Port 경로는 host death와 정리 유예 만료를 그 자체로 Failed·Cancelled로 바꾸지 않고, 종결 근거가 없으면 `Unresolved`로 처리한다. 강제 실패·취소를 만들던 구 실행 경로는 제거했다. 실제 native 완료·취소·정리의 검증 범위는 [현재 결과](native-port-validation.md)를 따른다.

판정은 [종결 증거 규칙](lifecycle-and-concurrency.md#종결-확인의-근거)과 [adapter별 근거 검증](provider-mapping.md#상태결과-매핑-원칙)을 따른다. host의 합성 종결 알림은 실제 Task 범위의 종료를 입증할 때에만 충분하다. 충분한 근거를 이미 받았다면 transport 정리 오류 때문에 그 결과를 Unresolved로 낮추지 않는다.

- 기존 executionId를 public TaskId로 변환할 수 있지만 둘의 수명과 식별 범위를 일치시켜 검증한다.
- 내부 resume_session은 영속성 선택 계약의 reopen에 대응할 수 있다. 모든 기본 Session의 필수 연산으로 남기지 않는다.
- 새 Question/Answer와 요구 계약을 전달하려면 내부 envelope도 의미에 맞게 확장·버전 관리한다.
- TaskOutcome과 TaskOutput의 분리를 내부 완료 알림이 지원해야 한다. 문자열 하나와 예외만으로 새 의미를 잃지 않는다.
- 실패·취소·미확정에서도 이미 확보한 업무 산출물·사용량을 보존한다. 기존 성공 알림에만 결과를 담던 구조를 그대로 유지하지 않는다.
- 원본 payload의 내부 수신과 public ProviderDiagnostic 노출은 별개다. 모든 원본을 TaskEvent에 실을 필요는 없다.
- 두 언어 host와 Kotlin decoder는 새 Port 경로에 연결돼 있다. 독립적으로 배포 가능한 runtime 버전 조합의 호환성 검증을 확대한다.

Bridge의 EOF·교착·중복·라우팅 검사는 [구현별 테스트](testing.md)로 유지한다. 공통 Port 적합성의 전제는 이 wire 형식이 아니라 작업·interaction·outcome의 의미다.

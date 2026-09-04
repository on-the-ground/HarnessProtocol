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

# Capability 후보

기본 포트에 넣지 않기로 한 목적과 그 이유, 재검토 조건을 기록한다. 기준은 [semantic-contract.md](semantic-contract.md)의 다섯 질문이다.

| 후보 | 제외 이유 | 재검토 조건 |
|---|---|---|
| caller-selected context token ceiling (`ContextPolicy.KeepWithinTokens`) | 어느 어댑터도 공개 SDK로 상한을 보장하지 못한다 (기준 3). `ProviderManaged` 하나만 남는 sealed type은 소비자 의미가 없어 `ContextPolicy` 필드 자체를 제거했다. | provider가 caller-selected ceiling을 보장하는 API를 공개하면 두 번째 실제 정책과 함께 재도입 |
| 사용자 질문 interaction (`InteractionRequest.Questions`) | `openai-codex` 0.147.0 generated 타입에 `requestUserInput` 계열이 없고 Gemini SDK도 노출하지 않는다 (기준 3). | 어느 한 SDK가 공개 API로 question request를 노출하면 `InteractionRequest` sealed 확장으로 추가 |
| host-defined custom tool 등록 | embedding 확장이며 core lifecycle이 아니다 (기준 2). | 별도 capability 인터페이스 |
| structured output | 결과 shape 확장으로 경계선. | 다음 minor에서 재검토 |
| active execution steering | 필수 lifecycle 아님. Codex만 제공. | 별도 capability |
| session history 조회 | 필수 lifecycle 아님. 두 SDK 지원 여부 미확인. | 지원 확인 후 capability |
| execution/session metadata (correlation) | 두 host 어디에도 전달되지 않는 opaque map이었다. trace 의미가 세 가지(로컬 로그 / provider 전달 / event 반환)로 갈리므로 하나의 map으로 재도입하지 않는다. | 구체적 요구가 생기면 세 의미 중 하나를 명시한 설계로 |

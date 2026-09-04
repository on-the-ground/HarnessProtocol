# Koog abstraction validation

[검증 계획](../../docs/koog-abstraction-validation-plan.md)의 단계 1–5에서 출발한 격리 실험이다. 현재는 **최신 public Port와 production ManagedTask**를 사용한다. 기존 Port를 Git에서 추출하던 빌드와 별도 ObservationStream 구현은 제거했다. 원래 실험의 관찰·설계 근거는 [과거 결과](../../docs/koog-abstraction-validation-results.md)와 evidence의 기존 JSON에 보존한다.

production의 bare ToolRegistry 구성은 `harness-koog`에 있다. 이 실험은 승인 도구·질문 도구·파일 저장소를 명시적으로 구성한 별도 adapter이며, 해당 선택 보장을 production Koog의 기본 지원으로 일반화하지 않는다.

## 실행

JDK 25를 JAVA_HOME에 지정한 뒤 저장소 루트에서 실행한다.

```powershell
./gradlew.bat --offline -p experiments/koog-validation test --console=plain
```

Koog 1.2.0, Kotlin 2.3.10, JVM target 21을 사용한다. composite build가 현재 저장소의 harness-protocol과 harness-runtime을 참조한다. 과거 Git object나 추출 소스는 필요하지 않다. 최초 의존성 준비에는 Maven artifact 다운로드가 필요하며 외부 모델 호출·인증은 필요하지 않다.

결과는 `%TEMP%/harness-protocol-koog-validation/reports/tests/test/index.html`, JUnit XML은 같은 build 디렉터리의 test-results/test에 생긴다. 같은 임시 build 경로를 쓰는 checkout을 동시에 실행하지 않는다. 이 독립 실험은 배포 bundle의 의존성이 아니다.

## 실제 실행 경계

- 실제: Koog graph, singleRunStrategy, tool registry·호출·hooks, coroutine 취소, 파일 이력과 native checkpoint 저장·복원.
- 통제: 모델 응답과 테스트 업무 자료. ScriptedExecutor의 요청 상한은 128회이며 overflow 검사는 81회의 실제 graph 모델 호출로 observer queue를 넘긴다. 외부 모델 호출은 없다.
- adapter 책임: 구성된 효과의 승인, typed 질문 응답, 명시적 보관 요구와 재개, 순차 작업, bounded 정리. 상태·pending·outcome은 production ManagedTask를 사용한다.

ReviewOperations의 업무 규칙과 변경 횟수는 테스트 애플리케이션이 소유한다. 승인 전 효과 0회, 승인 뒤 1회, 거절·취소 뒤 효과 없음, 비협조적 도구의 정리 이후 실제 효과를 대조한다.

## 지원 범위

SessionSpec·TaskRequest·TaskOutcome을 사용한다. 승인 선택은 APPROVE_ONCE, DECLINE, CANCEL이며 명시적 허용 범위가 없는 지속 승인은 제공하지 않는다. ProviderDefault와 DenyAll은 이 구성의 변경 도구를 거절한다. CallerAnswers를 요구하면 실제 질문 tool이 Question을 열고 Answer로 이어진다.

PersistenceRequirement.Required의 참조로 같은 애플리케이션 process 안에서 harness 재생성 후 파일 이력을 재개한다. 다른 namespace, 미확정 문맥, process 재시작 및 동시 접근 조정 요구를 거절한다. 재개 설정은 새 system 지시로 적용한다. 진단·workspace·sandbox·구조화 산출물·자동 reviewer는 구성하지 않았으므로 요구를 거절한다.

유예 이후 결과를 확인하지 못하면 Unresolved를 회수하고 문맥을 차단한다. 뒤늦은 실제 효과나 graph 종료가 이미 회수한 판정을 바꾸지 않는다. 반복 한도에서는 Completed + ITERATION_LIMIT를 전달하며 확보하지 않은 산출물을 빈 문자열로 만들지 않는다.

전체 적합성 인증은 아니다. 다중 writer, crash consistency, 저장소 migration, 실모델 품질과 모든 provider 오류 분류는 검증 범위 밖이다. 파일 checkpoint의 특정 조회 재실행 방지를 외부 효과의 exactly-once 보장으로 일반화하지 않는다.

## 코드와 증거

1. KoogRuntime.kt: AHP 밖의 native 구성과 업무 도구.
2. NativeKoogTest.kt: native 실행·문맥·취소·checkpoint 검사.
3. KoogHarness.kt: 최신 Port에 연결한 명시적 선택 구성.
4. PortScenariosTest.kt: 현재 계약의 공개 행동과 실제 업무 효과 대조.
5. [현재 Port 이전 기록](evidence/current-port-migration.json): 현재 소스·실행 결과. 기존 verification.json 및 reproduction-after-port-revision.json은 과거 시점의 기록이다.

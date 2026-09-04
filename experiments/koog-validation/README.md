# Koog abstraction validation

[검증 계획](../../docs/koog-abstraction-validation-plan.md)의 단계 1–5에서 수행한 AHP 0.1.0 대상 격리 실험이다. [결과와 새 설계 판단](../../docs/koog-abstraction-validation-results.md)을 함께 읽는다. 현재 계약 기준은 [추상과 용어](../../docs/abstraction-and-terminology.md)이며, 이 실험 코드는 당시 재현 자료로 남긴다. 코드의 AgentExecution 등 기존 명칭과 아래 지원 설명은 새 AgentTask 계약의 적합성을 뜻하지 않는다. 세 adapter의 후속 통합은 별도 작업이다.

## 재현

JDK 25를 `JAVA_HOME`에 지정한 뒤 저장소 루트에서 실행한다. 기존 Gradle wrapper를 사용한다.

```powershell
.\gradlew.bat -p experiments/koog-validation test --console=plain
```

Koog 1.2.0, Kotlin 2.3.10, JVM target 21을 사용한다. 의존성 버전은 `gradle.lockfile`에 기록했다. 최초 실행에는 Gradle/Maven artifact 다운로드가 필요하다. 모델 인증이나 유료 API 호출은 필요하지 않다.

테스트 결과는 `%TEMP%/harness-protocol-koog-validation/reports/tests/test/index.html`, JUnit XML은 같은 build 디렉터리의 `test-results/test`에 생긴다. 이 고정 build 디렉터리를 쓰는 여러 checkout의 실험을 동시에 실행하지 않는다.

루트 `settings.gradle.kts`, production adapter, 배포 bundle에는 연결하지 않았다. `prepareBaselineProtocol`이 기존 기록의 revision `d5733031a2533dfd0d56821d912442a08552b0fd`에서 protocol Kotlin 소스를 build 디렉터리로 추출한다. HEAD나 임시 legacy package를 참조하지 않으므로 이후 공개 Port 변경·legacy 제거에도 같은 실험을 재현한다. Kotlin 소스와 기존 verification.json은 당시 의미로 유지한다.

Git 실행 파일과 위 revision의 object가 로컬 저장소에 있어야 한다. shallow clone 등으로 해당 이력이 없으면 먼저 필요한 revision을 가져온다. 빌드는 네트워크로 Git 이력을 자동 조회하거나 다른 revision으로 대체하지 않는다. 최초 Gradle/Maven 의존성 준비와 이 Git 이력 준비는 별개다.

패키지 이동 후 재현 복구의 실행 결과와 변경된 빌드 파일 해시는 [재현 복구 기록](evidence/reproduction-after-port-revision.json)에 남긴다. 기존 verification.json의 build.gradle.kts 해시는 당시 빌드에 대한 기록이며 현재 준비 방식과 다르다.

## 무엇이 실제이고 무엇이 fixture인가

- 실제: 배포된 Koog graph runtime, `singleRunStrategy`, tool registry와 호출, event hooks, coroutine 취소, 파일 checkpoint 저장·복원.
- fixture: 모델 응답과 업무 자료·조회·상태 변경. `ScriptedExecutor`만 모델 경계를 대체한다. 실행기 하나당 모델 요청은 최대 12회이며 외부 모델 호출은 0회다.
- adapter가 보충한 것: 완료된 대화 이력 저장, 승인 중재, 공개 상태·이벤트·결과, session 순차 실행과 자원 정리.

`ReviewOperations`의 업무 규칙과 실제 변경 횟수는 테스트 애플리케이션이 소유한다. Koog tool은 이를 호출하고 adapter가 승인 경계를 적용한다. 공개 custom tool 등록 API를 추가한 실험은 아니다.

## 읽는 순서

1. `src/main/kotlin/experiment/KoogRuntime.kt`: AHP 밖의 자연스러운 Koog 구성과 업무 도구.
2. `src/test/kotlin/experiment/NativeKoogTest.kt`: native 실행, 반복 입력, 취소, checkpoint 재개.
3. `src/main/kotlin/experiment/KoogHarness.kt`: 기존 Port에 연결한 부분 실험 adapter.
4. `src/test/kotlin/experiment/PortScenariosTest.kt`: S1–S4의 공개 행동과 실제 모의 효과 대조.
5. `evidence/verification.json`: 수행한 테스트 결과와 관찰 로그 요약.

## 지원 범위

문자열 입력·결과, 순차 실행, 완료된 대화의 파일 저장·재개, 구성된 변경 도구의 승인·거절, 협조적 취소를 다룬다. 승인 선택은 `APPROVE_ONCE`, `DECLINE`, `CANCEL`이다. 기본 정책과 `DENY_ALL`에서는 변경 요청을 거절한다. `AGENT_REVIEWED`, 다른 모델 descriptor, 명시적 filesystem/network 정책, working directory, skills는 `validate`에서 거절한다.

이 구현은 **검증 당시 계약의 부분 실험이며, 개정된 AHP의 적합 구현도 아니다.** 종료 유예를 넘긴 작업을 강제로 `CANCELLED`로 보고하지 않고 종료 미확정 오류를 내는 반례를 의도적으로 포함한다. 질문 답변은 현행 Port로 전달할 수 없으며 해당 tool 호출이 오류를 반환한다. Koog의 모든 기능·이벤트를 매핑하지 않았다.

대화 저장소는 단일 writer용이며 완료된 실행만 저장한다. 중단된 실행의 복구, 여러 process 간 동시 접근, crash consistency, 저장소 migration, 실제 LLM의 출력 품질과 전체 provider 오류 분류는 검증하지 않았다. 파일 checkpoint 실험에서 한 번의 조회 재실행을 피한 관찰을 외부 효과의 exactly-once 보장으로 일반화하지 않는다.

package dev.harnessprotocol.conformance.reference

import dev.harnessprotocol.conformance.HarnessConformanceCoreTest
import dev.harnessprotocol.conformance.HarnessFixture

/**
 * [HarnessConformanceCoreTest]가 실제로 구동 가능함을 보이는 참조 구현.
 *
 * 이 클래스 자체는 규범이 아니다 — Codex/Gemini/Koog adapter는 각자의 [HarnessFixture]로
 * 같은 추상 suite를 상속해 채운다.
 */
class ReferenceConformanceCoreTest : HarnessConformanceCoreTest() {
    override fun fixture(): HarnessFixture = ReferenceFixture()
}

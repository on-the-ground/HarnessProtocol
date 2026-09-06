package dev.harnessprotocol.conformance

import dev.harnessprotocol.SessionSpec

/** Actual loaded skill bodies and configured directory, supplied independently of adapter reports. */
interface WorkspaceFixture : AcceptanceFixture {
    val workingDirectory: String
    val activeSkillName: String
    val activeSkillPath: String
    val activeSkillBody: String
    val inactiveSkillName: String
    val inactiveSkillPath: String
    val inactiveSkillBody: String
    val invalidSpec: SessionSpec
}

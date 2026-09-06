package dev.harnessprotocol.integration

import dev.harnessprotocol.*
import dev.harnessprotocol.conformance.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

abstract class NativeWorkspaceTest : HarnessWorkspaceConformanceTest() {
    @TempDir lateinit var directory: Path
    protected abstract val factory: NativeHarnessFactory
    override fun workspaceFixture(): WorkspaceFixture = object : WorkspaceFixture {
        override val observation = ModelBoundary()
        override val workingDirectory = Files.createDirectories(directory.resolve("workspace")).toString()
        override val activeSkillName = "ahp-active"
        override val inactiveSkillName = "ahp-inactive"
        override val activeSkillBody = "AHP_ACTIVE_SKILL_BODY_7f264"
        override val inactiveSkillBody = "AHP_INACTIVE_SKILL_BODY_a218e"
        private fun skill(name: String, body: String, activate: Boolean): SkillReference {
            val folder = Files.createDirectories(directory.resolve(name))
            Files.writeString(folder.resolve("SKILL.md"), "---\nname: $name\ndescription: Isolated AHP validation skill\n---\n\n$body\n")
            return SkillReference(name, folder.toString(), activate)
        }
        private val activeSkill = skill(activeSkillName, activeSkillBody, true)
        private val inactiveSkill = skill(inactiveSkillName, inactiveSkillBody, false)
        override val activeSkillPath = activeSkill.path
        override val inactiveSkillPath = inactiveSkill.path
        override val spec = factory.spec().copy(requirements = SessionRequirements(workspace = WorkspaceRequirement.Required(
            workingDirectory, listOf(activeSkill, inactiveSkill))))
        override val invalidSpec = factory.spec().copy(requirements = SessionRequirements(workspace = WorkspaceRequirement.Required(
            workingDirectory, listOf(SkillReference("missing", directory.resolve("missing-skill").toString(), true)))))
        override val harness = factory.create(observation, directory)
        override fun close() { try { harness.close() } finally { observation.close() } }
    }
}
class CodexNativeWorkspaceTest : NativeWorkspaceTest() { override val factory = CodexNativeFactory }
class GeminiNativeWorkspaceTest : NativeWorkspaceTest() { override val factory = GeminiNativeFactory }

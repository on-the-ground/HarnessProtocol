package dev.harnessprotocol.integration

import com.sun.net.httpserver.HttpServer
import dev.harnessprotocol.*
import dev.harnessprotocol.conformance.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CodexNativeExecutionConstraintTest : HarnessExecutionConstraintConformanceTest() {
    @TempDir lateinit var directory: Path
    override fun executionFixture(case: ExecutionCase): ExecutionConstraintFixture = object : ExecutionConstraintFixture {
        private val workspace = Files.createDirectories(directory.resolve("workspace"))
        private val additional = Files.createDirectories(directory.resolve("additional"))
        private val outside = Files.createDirectories(directory.resolve("outside"))
        private val hits = AtomicInteger()
        private val endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
            start()
        }
        private fun quoted(path: Path) = "'" + path.toString().replace("'", "''") + "'"
        @Volatile private var attempt: ExecutionAttempt? = null
        private val sent = AtomicBoolean()
        override val observation = ModelBoundary { body ->
            val current = checkNotNull(attempt) { "Prepare an execution attempt before starting its Task" }
            if (!sent.compareAndSet(false, true)) null else {
                val tools = Json.parseToJsonElement(body).jsonObject["tools"]!!.jsonArray.map { it.jsonObject }
                val name = tools.mapNotNull { it["name"]?.jsonPrimitive?.content }.first { it in setOf("shell_command", "exec_command", "shell") }
                val target = when (current) {
                    ExecutionAttempt.WRITE_WORKSPACE -> workspace.resolve("effect.txt")
                    ExecutionAttempt.WRITE_ADDITIONAL -> additional.resolve("effect.txt")
                    ExecutionAttempt.WRITE_OUTSIDE -> outside.resolve("effect.txt")
                    ExecutionAttempt.USE_NETWORK -> null
                }
                val command = if (target != null)
                    "Set-Content -LiteralPath ${quoted(target)} -Value 'effect'"
                else "Invoke-WebRequest -Uri 'http://127.0.0.1:${endpoint.address.port}/effect' -TimeoutSec 3 -UseBasicParsing | Out-Null"
                val args = buildJsonObject {
                    when (name) {
                        "shell_command" -> put("command", command)
                        "exec_command" -> put("cmd", command)
                        else -> put("command", JsonArray(listOf("powershell.exe", "-NoProfile", "-Command", command).map(::JsonPrimitive)))
                    }
                    put("sandbox_permissions", "use_default")
                }
                val item = buildJsonObject {
                    put("id", "fc_${current.name.lowercase()}"); put("type", "function_call"); put("call_id", "call_${current.name.lowercase()}")
                    put("name", name); put("arguments", args.toString()); put("status", "completed")
                }
                listOf(
                    """{"type":"response.created","response":{"id":"resp_constraint","status":"in_progress","output":[]}}""",
                    """{"type":"response.output_item.done","output_index":0,"item":$item}""",
                    """{"type":"response.completed","response":{"id":"resp_constraint","status":"completed","output":[$item]}}""",
                ).joinToString("") { "data: $it\n\n" }
            }
        }
        override val spec = CodexNativeFactory.spec().copy(requirements = SessionRequirements(
            workspace = WorkspaceRequirement.Required(workspace.toString()), approval = ApprovalRequirement.DenyAll,
            execution = ExecutionConstraint.Required(
                filesystem = if (case == ExecutionCase.READ_ONLY) FilesystemAccess.ReadOnly else FilesystemAccess.WorkspaceWrite(setOf(additional.toString())),
                network = when (case) { ExecutionCase.READ_ONLY -> null; ExecutionCase.WORKSPACE_DENIED_NETWORK -> NetworkAccess.DENIED; else -> NetworkAccess.ALLOWED })))
        override val harness = CodexNativeFactory.create(observation, directory)
        override fun prepare(attempt: ExecutionAttempt): TaskRequest {
            this.attempt = attempt
            sent.set(false)
            return TaskRequest(TaskInput.Text("attempt-${attempt.name.lowercase()}"))
        }
        override fun writtenTargets(): Set<String> {
            return listOf("workspace" to workspace, "additional" to additional, "outside" to outside)
                .filter { Files.exists(it.second.resolve("effect.txt")) }.map { it.first }.toSet()
        }
        override fun networkRequests() = hits.get()
        override fun close() { try { harness.close() } finally { observation.close(); endpoint.stop(0) } }
    }
}

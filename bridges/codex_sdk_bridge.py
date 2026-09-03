"""NDJSON bridge between the Kotlin adapter and the official openai-codex SDK.

The host drives `openai_codex.client.CodexClient` directly rather than the
high-level `Codex`/`AsyncCodex` wrappers because only the low-level client lets
us (1) omit approval fields so `PROVIDER_DEFAULT` really means the runtime's own
default, and (2) register an approval handler. See
docs/spikes/2026-09-03-codex-low-level-client.md.

Threading model
---------------
* The asyncio loop owns stdin/stdout and the pending-interaction table.
* `CodexClient` runs a reader thread. Approval requests arrive on that thread and
  block it until a decision is available. Therefore every path that must make
  progress while an approval is open (respond, cancel, release, close) first
  unblocks the handler by putting a decision into its queue and only then talks
  to the SDK.
* Turn notifications are drained by one worker thread per execution.
"""

from __future__ import annotations

import asyncio
import dataclasses
import itertools
import json
import queue
import sys
import threading
from datetime import date, datetime
from enum import Enum
from pathlib import Path
from typing import Any

from pydantic import BaseModel

from openai_codex.client import CodexClient, CodexConfig

APPROVAL_REQUEST_METHODS = {
    "item/commandExecution/requestApproval": "command",
    "item/fileChange/requestApproval": "file_change",
}
AVAILABLE_DECISIONS = ["accept", "acceptForSession", "decline", "cancel"]
DECISION_WIRE = {
    "approve_once": "accept",
    "approve_for_session": "acceptForSession",
    "decline": "decline",
    "cancel": "cancel",
}


@dataclasses.dataclass
class PendingInteraction:
    interaction_id: str
    execution_id: str
    decisions: "queue.Queue[str]"
    resolved: bool = False
    # Set by the handler once it has taken the decision and is returning it to
    # the SDK reader thread. cancel/close wait on it before talking to the SDK.
    answered: threading.Event = dataclasses.field(default_factory=threading.Event)


@dataclasses.dataclass
class Execution:
    execution_id: str
    session_id: str
    worker: threading.Thread | None = None


class Bridge:
    def __init__(self, client: CodexClient | None = None) -> None:
        self.client = client or CodexClient(CodexConfig(), approval_handler=self.on_server_request)
        self.loop: asyncio.AbstractEventLoop | None = None
        self.sessions: dict[str, dict[str, Any]] = {}
        self.executions: dict[str, Execution] = {}
        self.pending: dict[str, PendingInteraction] = {}
        self.pending_lock = threading.Lock()
        self.interaction_ids = itertools.count(1)
        self.output_lock = threading.Lock()
        self.started = False

    # ------------------------------------------------------------------ output

    def send(self, message: dict[str, Any]) -> None:
        line = json.dumps(message, ensure_ascii=False, separators=(",", ":"))
        with self.output_lock:
            sys.stdout.write(line + "\n")
            sys.stdout.flush()

    def respond(self, request_id: int, result: Any = None, error: Exception | None = None) -> None:
        if error is None:
            self.send({"kind": "response", "id": request_id, "result": jsonable(result or {})})
        else:
            self.send(
                {
                    "kind": "response",
                    "id": request_id,
                    "error": {"type": type(error).__name__, "message": str(error)},
                }
            )

    def emit(self, execution_id: str, method: str, payload: Any) -> None:
        """Thread-safe: may be called from the loop, the reader thread, or a worker."""
        self.send(
            {
                "kind": "event",
                "executionId": execution_id,
                "payload": {"method": method, "payload": jsonable(payload)},
            }
        )

    # ---------------------------------------------------------------- dispatch

    async def ensure_started(self) -> None:
        if self.started:
            return
        await asyncio.to_thread(self.client.start)
        await asyncio.to_thread(self.client.initialize)
        self.started = True

    async def dispatch(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if method == "create_session":
            await self.ensure_started()
            started = await asyncio.to_thread(self.client.thread_start, thread_start_params(params))
            thread_id = started.thread.id
            self.sessions[thread_id] = params
            return {"sessionId": thread_id}

        if method == "resume_session":
            await self.ensure_started()
            session_id = required_string(params, "sessionId")
            spec = required_object(params, "spec")
            resumed = await asyncio.to_thread(self.client.thread_resume, session_id, thread_resume_params(spec))
            thread_id = resumed.thread.id
            self.sessions[thread_id] = spec
            return {"sessionId": thread_id}

        if method == "release_session":
            session_id = required_string(params, "sessionId")
            for execution in list(self.executions.values()):
                if execution.session_id == session_id:
                    await self.cancel_execution(execution)
            self.sessions.pop(session_id, None)
            return {}

        if method == "start_execution":
            session_id = required_string(params, "sessionId")
            spec = self.sessions.get(session_id)
            if spec is None:
                raise ValueError(f"session {session_id!r} has not been created or resumed")
            inputs = make_inputs(required_object(params, "input"), spec)
            started = await asyncio.to_thread(self.client.turn_start, session_id, inputs)
            turn_id = started.turn.id
            execution = Execution(execution_id=turn_id, session_id=session_id)
            self.executions[turn_id] = execution
            execution.worker = threading.Thread(target=self.stream_turn, args=(execution,), daemon=True)
            execution.worker.start()
            return {"executionId": turn_id}

        if method == "cancel_execution":
            execution = self.executions.get(required_string(params, "executionId"))
            if execution is not None:
                await self.cancel_execution(execution)
            return {}

        if method == "respond_interaction":
            interaction_id = required_string(params, "interactionId")
            response = required_object(params, "response")
            decision = DECISION_WIRE.get(str(response.get("decision")))
            if decision is None:
                raise ValueError(f"unsupported approval decision: {response.get('decision')!r}")
            self.resolve_interaction(interaction_id, decision, {"type": "responded", "decision": response.get("decision")})
            return {}

        raise ValueError(f"unknown bridge method: {method}")

    # ------------------------------------------------------------ executions

    def stream_turn(self, execution: Execution) -> None:
        """Worker thread: forward every notification of one turn until it completes."""
        turn_id = execution.execution_id
        try:
            while True:
                notification = self.client.next_turn_notification(turn_id)
                self.emit(turn_id, notification.method, notification.payload)
                if notification.method == "turn/completed" and turn_completed_matches(notification.payload, turn_id):
                    break
        except Exception as error:  # SDK failures must terminate the Kotlin execution.
            self.emit(turn_id, "error", {"error": {"message": str(error), "type": type(error).__name__}})
        finally:
            try:
                self.client.unregister_turn_notifications(turn_id)
            except Exception:  # noqa: BLE001 - best effort
                pass
            self.clear_interactions(turn_id, "turn_completed")
            self.executions.pop(turn_id, None)

    async def cancel_execution(self, execution: Execution) -> None:
        # Unblock the reader thread first (see module docstring), wait until the
        # handler has actually returned its decision, then interrupt. The SDK
        # writes the decision from its reader thread, so wire order relative to
        # the interrupt is best-effort beyond this point.
        cleared = self.clear_interactions(execution.execution_id, "turn_interrupted", decision="cancel")
        for pending in cleared:
            await asyncio.to_thread(pending.answered.wait, 5.0)
        try:
            await asyncio.to_thread(self.client.turn_interrupt, execution.session_id, execution.execution_id)
        except Exception as error:  # noqa: BLE001 - the turn may already be over
            self.emit(execution.execution_id, "warning", {"kind": "other", "message": f"interrupt failed: {error}"})

    # ------------------------------------------------------------ approvals

    def on_server_request(self, method: str, params: dict[str, Any] | None) -> dict[str, Any]:
        """Runs on the SDK reader thread. Never returns `accept` on its own."""
        effect = APPROVAL_REQUEST_METHODS.get(method)
        if effect is None:
            return {}
        params = params or {}
        turn_id = str(params.get("turnId") or "")
        thread_id = str(params.get("threadId") or "")
        spec = self.sessions.get(thread_id) or {}
        policy = spec.get("approval", "provider_default")

        if policy != "caller_decides":
            self.emit(
                turn_id or "unknown",
                "warning",
                {
                    "kind": "configuration",
                    "message": f"provider requested approval for {effect} under approval policy {policy!r}; declined",
                },
            )
            return {"decision": "decline"}

        interaction_id = f"{turn_id}#{next(self.interaction_ids)}"
        pending = PendingInteraction(interaction_id, turn_id, queue.Queue(maxsize=1))
        with self.pending_lock:
            self.pending[interaction_id] = pending
        self.emit(
            turn_id,
            "interaction_requested",
            {
                "interactionId": interaction_id,
                "kind": "approval",
                "effect": effect,
                "workId": params.get("itemId"),
                "prompt": approval_prompt(effect, params),
                "availableDecisions": AVAILABLE_DECISIONS,
                "detail": params,
            },
        )
        decision = pending.decisions.get()  # blocks the reader thread until resolved
        with self.pending_lock:
            self.pending.pop(interaction_id, None)
        pending.answered.set()
        return {"decision": decision}

    def resolve_interaction(self, interaction_id: str, decision: str, resolution: dict[str, Any]) -> None:
        with self.pending_lock:
            pending = self.pending.get(interaction_id)
            if pending is None or pending.resolved:
                raise ValueError(f"interaction {interaction_id!r} is unknown or already resolved")
            pending.resolved = True
        pending.decisions.put_nowait(decision)
        self.emit(pending.execution_id, "interaction_resolved", {"interactionId": interaction_id, "resolution": resolution})

    def clear_interactions(self, execution_id: str, reason: str, decision: str = "decline") -> list[PendingInteraction]:
        with self.pending_lock:
            open_ones = [p for p in self.pending.values() if p.execution_id == execution_id and not p.resolved]
            for pending in open_ones:
                pending.resolved = True
        for pending in open_ones:
            pending.decisions.put_nowait(decision)
            self.emit(
                execution_id,
                "interaction_resolved",
                {"interactionId": pending.interaction_id, "resolution": {"type": "cleared", "reason": reason}},
            )
        return open_ones

    # ------------------------------------------------------------------ loop

    async def run(self) -> None:
        self.loop = asyncio.get_running_loop()
        try:
            while True:
                line = await asyncio.to_thread(sys.stdin.readline)
                if line == "":
                    return
                if not line.strip():
                    continue
                request: dict[str, Any] | None = None
                try:
                    request = json.loads(line)
                    if request.get("kind") != "request":
                        continue
                    result = await self.dispatch(
                        required_string(request, "method"),
                        request.get("params") or {},
                    )
                    self.respond(int(request["id"]), result=result)
                except Exception as error:
                    if request is not None and "id" in request:
                        self.respond(int(request["id"]), error=error)
                    else:
                        print(f"bridge input error: {error}", file=sys.stderr, flush=True)
        finally:
            # Unblock any handler before closing the client so close() cannot deadlock.
            for execution in list(self.executions.values()):
                for pending in self.clear_interactions(execution.execution_id, "turn_interrupted", decision="cancel"):
                    await asyncio.to_thread(pending.answered.wait, 5.0)
            if self.started:
                await asyncio.to_thread(self.client.close)


# ------------------------------------------------------------------ mapping

SANDBOX_WIRE = {
    "read_only": "read-only",
    "workspace_write": "workspace-write",
    "full_access": "danger-full-access",
}


def approval_params(approval: str) -> dict[str, Any]:
    """Wire fields for the requested approval policy. provider_default sends nothing."""
    if approval == "provider_default":
        return {}
    if approval == "deny_all":
        return {"approvalPolicy": "never"}
    if approval == "agent_reviewed":
        return {"approvalPolicy": "on-request", "approvalsReviewer": "auto_review"}
    if approval == "caller_decides":
        return {"approvalPolicy": "on-request"}
    raise ValueError(f"unsupported approval policy: {approval!r}")


def common_thread_params(spec: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    copy_if_present(spec, result, "instructions", "developerInstructions")
    copy_if_present(spec, result, "model", "model")
    copy_if_present(spec, result, "workingDirectory", "cwd")

    filesystem = spec.get("filesystem", "provider_default")
    if filesystem != "provider_default":
        result["sandbox"] = SANDBOX_WIRE[filesystem]

    result.update(approval_params(spec.get("approval", "provider_default")))

    sandbox_config: dict[str, Any] = {}
    network = spec.get("network", "provider_default")
    if network != "provider_default":
        sandbox_config["network_access"] = network == "allowed"
    roots = spec.get("additionalWritableRoots") or []
    if roots:
        sandbox_config["writable_roots"] = roots
    if sandbox_config:
        result["config"] = {"sandbox_workspace_write": sandbox_config}
    return result


def thread_start_params(spec: dict[str, Any]) -> dict[str, Any]:
    return common_thread_params(spec)


def thread_resume_params(spec: dict[str, Any]) -> dict[str, Any]:
    return common_thread_params(spec)


def make_inputs(input_value: dict[str, Any], spec: dict[str, Any]) -> list[dict[str, Any]]:
    input_type = input_value.get("type")
    if input_type != "text":
        raise ValueError(f"unsupported input type: {input_type!r}")

    skills = spec.get("skills") or []
    text = required_string(input_value, "text")
    # Provider activation envelope: Codex activates a skill through a `$name`
    # mention plus a skill input item. The user's text itself is not altered.
    mentions = " ".join(f"${skill['name']}" for skill in skills)
    if mentions:
        text = f"{mentions}\n\n{text}"
    result: list[dict[str, Any]] = [{"type": "text", "text": text}]
    result.extend(
        {"type": "skill", "name": required_string(skill, "name"), "path": required_string(skill, "path")}
        for skill in skills
    )
    return result


def approval_prompt(effect: str, params: dict[str, Any]) -> str:
    if effect == "command":
        command = params.get("command")
        if isinstance(command, list):
            return " ".join(str(part) for part in command)
        if command:
            return str(command)
    if effect == "file_change":
        reason = params.get("reason")
        if reason:
            return str(reason)
    return json.dumps(params, ensure_ascii=False)[:500]


def turn_completed_matches(payload: Any, turn_id: str) -> bool:
    turn = getattr(payload, "turn", None)
    if turn is not None:
        return getattr(turn, "id", None) == turn_id
    if isinstance(payload, dict):
        inner = payload.get("turn") or {}
        return inner.get("id") == turn_id
    params = getattr(payload, "params", None)
    if isinstance(params, dict):
        return (params.get("turn") or {}).get("id") == turn_id
    return True


def jsonable(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, BaseModel):
        return jsonable(value.model_dump(mode="json", by_alias=True))
    if dataclasses.is_dataclass(value):
        return jsonable(dataclasses.asdict(value))
    if isinstance(value, Enum):
        return jsonable(value.value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [jsonable(item) for item in value]
    if hasattr(value, "__dict__"):
        return jsonable(vars(value))
    return str(value)


def required_string(value: dict[str, Any], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result:
        raise ValueError(f"{key!r} must be a non-empty string")
    return result


def required_object(value: dict[str, Any], key: str) -> dict[str, Any]:
    result = value.get(key)
    if not isinstance(result, dict):
        raise ValueError(f"{key!r} must be an object")
    return result


def copy_if_present(source: dict[str, Any], target: dict[str, Any], source_key: str, target_key: str) -> None:
    value = source.get(source_key)
    if value is not None:
        target[target_key] = value


if __name__ == "__main__":
    asyncio.run(Bridge().run())

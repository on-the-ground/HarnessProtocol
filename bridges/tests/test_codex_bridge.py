"""Host tests for bridges/codex_sdk_bridge.py.

Two layers:
* pure mapping tests (no SDK process),
* end-to-end tests that run the real `openai_codex.client.CodexClient` against
  `stub_app_server.py`, which is how the approval round-trip and the
  unblock-before-interrupt ordering (spike 8b) are verified without credentials.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import codex_sdk_bridge as bridge_module  # noqa: E402
from codex_sdk_bridge import Bridge, codex_config, make_inputs, thread_start_params  # noqa: E402

pytest.importorskip("openai_codex")
from openai_codex.client import CodexClient, CodexConfig  # noqa: E402


# ---------------------------------------------------------------- mapping


def test_codex_runtime_override_is_explicit_and_optional():
    assert codex_config({}).codex_bin is None
    assert codex_config({"HARNESS_CODEX_EXECUTABLE": "/opt/codex"}).codex_bin == "/opt/codex"


def test_provider_default_approval_sends_no_approval_fields():
    params = thread_start_params({"approval": "provider_default"})
    assert "approvalPolicy" not in params
    assert "approvalsReviewer" not in params


@pytest.mark.parametrize(
    "approval, expected",
    [
        ("deny_all", {"approvalPolicy": "never"}),
        ("agent_reviewed", {"approvalPolicy": "on-request", "approvalsReviewer": "auto_review"}),
        ("caller_decides", {"approvalPolicy": "on-request"}),
    ],
)
def test_explicit_approval_policies_map_to_wire_values(approval, expected):
    params = thread_start_params({"approval": approval})
    assert {k: params[k] for k in expected} == expected
    if "approvalsReviewer" not in expected:
        assert "approvalsReviewer" not in params


def test_null_instructions_are_omitted_and_empty_string_is_sent():
    assert "developerInstructions" not in thread_start_params({})
    assert thread_start_params({"instructions": ""})["developerInstructions"] == ""


def test_network_intent_rides_on_workspace_write_config_only():
    params = thread_start_params({"filesystem": "workspace_write", "network": "denied", "additionalWritableRoots": ["/x"]})
    assert params["sandbox"] == "workspace-write"
    assert params["config"]["sandbox_workspace_write"] == {"network_access": False, "writable_roots": ["/x"]}
    # The Kotlin validate() rejects network intent outside workspace-write; the host stays a dumb translator.


def test_skill_activation_envelope_keeps_user_text():
    items = make_inputs({"type": "text", "text": "do it"}, {"skills": [{"name": "s", "path": "/s"}]})
    assert items[0] == {"type": "text", "text": "$s\n\ndo it"}
    assert items[1] == {"type": "skill", "name": "s", "path": "/s"}


# ------------------------------------------------------------ handler table


class _Recorder:
    def __init__(self):
        self.events = []

    def __call__(self, execution_id, method, payload):
        self.events.append((execution_id, method, payload))


@pytest.mark.parametrize("policy", ["provider_default", "deny_all", "agent_reviewed"])
def test_handler_never_accepts_outside_caller_decides(policy):
    bridge = Bridge(client=object())  # handler logic only; the client is never touched
    bridge.sessions["thread-1"] = {"approval": policy}
    recorder = _Recorder()
    bridge.emit = recorder
    result = bridge.on_server_request(
        "item/commandExecution/requestApproval", {"threadId": "thread-1", "turnId": "turn-1", "command": ["rm"]}
    )
    assert result == {"decision": "decline"}
    assert recorder.events[0][1] == "warning"
    assert recorder.events[0][2]["kind"] == "configuration"


def test_unknown_server_requests_get_empty_result_without_events():
    bridge = Bridge(client=object())
    recorder = _Recorder()
    bridge.emit = recorder
    assert bridge.on_server_request("some/otherRequest", {}) == {}
    assert recorder.events == []


# ----------------------------------------------------------- end to end


def _stub_client(behavior: str, log_path: Path, handler) -> CodexClient:
    env = {"START_BEHAVIOR": behavior, "STUB_LOG": str(log_path)}
    return CodexClient(
        CodexConfig(launch_args_override=(sys.executable, str(HERE / "stub_app_server.py")), env=env),
        approval_handler=handler,
    )


class _CapturingBridge(Bridge):
    """Bridge whose emitted events are captured instead of written to stdout."""

    def __init__(self, client):
        super().__init__(client=client)
        self.captured: list[tuple[str, str, dict]] = []
        self.captured_lock = threading.Lock()

    def emit(self, execution_id, method, payload):
        with self.captured_lock:
            self.captured.append((execution_id, method, bridge_module.jsonable(payload)))

    def wait_for(self, method: str, timeout: float = 10.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.captured_lock:
                for entry in self.captured:
                    if entry[1] == method:
                        return entry
            time.sleep(0.02)
        raise AssertionError(f"no {method!r} event; got {[e[1] for e in self.captured]}")


def _read_log(path: Path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


@pytest.fixture
def stub_log(tmp_path):
    return tmp_path / "stub.log"


def _run(coro):
    return asyncio.run(coro)


def test_end_to_end_completion_with_provider_default(stub_log):
    holder = {}
    client = _stub_client("complete", stub_log, lambda m, p: holder["bridge"].on_server_request(m, p))
    bridge = _CapturingBridge(client)
    holder["bridge"] = bridge

    async def scenario():
        session = await bridge.dispatch("create_session", {"approval": "provider_default"})
        started = await bridge.dispatch("start_execution", {"sessionId": session["sessionId"], "input": {"type": "text", "text": "hi"}})
        bridge.wait_for("turn/completed")
        return session, started

    try:
        session, started = _run(scenario())
    finally:
        client.close()
    thread_start = next(e for e in _read_log(stub_log) if e["received"] == "thread/start")
    assert "approvalPolicy" not in thread_start["params"] and "approvalsReviewer" not in thread_start["params"]
    assert started["executionId"].startswith("turn-")
    methods = [e[1] for e in bridge.captured]
    assert methods[-1] == "turn/completed"


def test_caller_decides_round_trip_respond_resumes(stub_log):
    holder = {}
    client = _stub_client("approval", stub_log, lambda m, p: holder["bridge"].on_server_request(m, p))
    bridge = _CapturingBridge(client)
    holder["bridge"] = bridge

    async def scenario():
        session = await bridge.dispatch("create_session", {"approval": "caller_decides"})
        await bridge.dispatch("start_execution", {"sessionId": session["sessionId"], "input": {"type": "text", "text": "hi"}})
        requested = await asyncio.to_thread(bridge.wait_for, "interaction_requested")
        payload = requested[2]
        assert payload["kind"] == "approval" and payload["effect"] == "command"
        assert payload["availableDecisions"] == ["accept", "acceptForSession", "decline", "cancel"]
        await bridge.dispatch("respond_interaction", {"executionId": requested[0], "interactionId": payload["interactionId"], "response": {"decision": "approve_once"}})
        await asyncio.to_thread(bridge.wait_for, "turn/completed")

    try:
        _run(scenario())
    finally:
        client.close()
    log = _read_log(stub_log)
    assert any(e.get("decision") == "accept" for e in log)
    resolved = bridge.wait_for("interaction_resolved")
    assert resolved[2]["resolution"] == {"type": "responded", "decision": "approve_once"}
    final = next(e for e in bridge.captured if e[1] == "item/completed")
    assert "decision=accept" in json.dumps(final[2])


def test_cancel_unblocks_handler_before_interrupt(stub_log):
    """Spike 8b: with the reader thread blocked in the handler, cancel must resolve
    the interaction (handler returns its decision) before turn/interrupt is sent,
    and the turn must end interrupted without a deadlock.

    The SDK writes the decision from its reader thread, so the wire order of the
    decision response and the interrupt request is best-effort; what is
    deterministic is that the handler has returned before we ask to interrupt."""
    holder = {}
    client = _stub_client("hang", stub_log, lambda m, p: holder["bridge"].on_server_request(m, p))
    bridge = _CapturingBridge(client)
    holder["bridge"] = bridge

    async def scenario():
        session = await bridge.dispatch("create_session", {"approval": "caller_decides"})
        started = await bridge.dispatch("start_execution", {"sessionId": session["sessionId"], "input": {"type": "text", "text": "hi"}})
        await asyncio.to_thread(bridge.wait_for, "interaction_requested")
        await asyncio.wait_for(bridge.dispatch("cancel_execution", {"executionId": started["executionId"]}), timeout=10)
        await asyncio.to_thread(bridge.wait_for, "turn/completed")

    try:
        _run(scenario())
    finally:
        client.close()
    log = _read_log(stub_log)
    decisions = [e for e in log if "decision" in e]
    assert decisions and decisions[0]["decision"] == "cancel", log
    assert any(e.get("received") == "turn/interrupt" for e in log), log
    assert not bridge.pending, "no interaction may stay pending after cancel"
    resolved = bridge.wait_for("interaction_resolved")
    assert resolved[2]["resolution"] == {"type": "cleared", "reason": "turn_interrupted"}
    completed = bridge.wait_for("turn/completed")
    assert completed[2]["turn"]["status"] == "interrupted"


def test_duplicate_or_unknown_response_is_rejected(stub_log):
    holder = {}
    client = _stub_client("approval", stub_log, lambda m, p: holder["bridge"].on_server_request(m, p))
    bridge = _CapturingBridge(client)
    holder["bridge"] = bridge

    async def scenario():
        session = await bridge.dispatch("create_session", {"approval": "caller_decides"})
        await bridge.dispatch("start_execution", {"sessionId": session["sessionId"], "input": {"type": "text", "text": "hi"}})
        requested = await asyncio.to_thread(bridge.wait_for, "interaction_requested")
        iid = requested[2]["interactionId"]
        with pytest.raises(ValueError):
            await bridge.dispatch("respond_interaction", {"executionId": requested[0], "interactionId": "nope", "response": {"decision": "decline"}})
        await bridge.dispatch("respond_interaction", {"executionId": requested[0], "interactionId": iid, "response": {"decision": "decline"}})
        with pytest.raises(ValueError):
            await bridge.dispatch("respond_interaction", {"executionId": requested[0], "interactionId": iid, "response": {"decision": "decline"}})
        await asyncio.to_thread(bridge.wait_for, "turn/completed")

    try:
        _run(scenario())
    finally:
        client.close()

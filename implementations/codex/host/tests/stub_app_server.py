"""Minimal Codex App Server stand-in for host tests.

Speaks the JSON-RPC-over-stdio shape `openai_codex.client.CodexClient` expects:
requests get responses, `turn/start` triggers a scripted turn. The script is
selected by the START_BEHAVIOR environment variable:

* ``complete``  — emit one agent message item and complete the turn.
* ``approval``  — send ``item/commandExecution/requestApproval`` as a server
                  request, wait for the client's decision, then complete the
                  turn with an item whose text records the decision.
* ``hang``      — after the approval request, never complete unless
                  ``turn/interrupt`` arrives (then complete with status
                  ``interrupted``).

Every request the stub receives is logged to STUB_LOG (one JSON per line) so a
test can assert on ordering, e.g. that the approval response was written before
``turn/interrupt`` was received.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time

BEHAVIOR = os.environ.get("START_BEHAVIOR", "complete")
LOG = os.environ.get("STUB_LOG")
_lock = threading.Lock()
_pending_approval: dict[str, str] = {}
_server_ids = iter(range(1000, 10_000))
_turn_state: dict[str, str] = {}


def log(entry: dict) -> None:
    if not LOG:
        return
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")


def send(message: dict) -> None:
    with _lock:
        sys.stdout.write(json.dumps(message) + "\n")
        sys.stdout.flush()


def thread_payload(thread_id: str) -> dict:
    return {
        "id": thread_id, "cliVersion": "stub", "createdAt": 0, "cwd": "/", "ephemeral": False,
        "modelProvider": "openai", "preview": "", "sessionId": thread_id, "source": "appServer",
        "status": {"type": "idle"}, "turns": [], "updatedAt": 0,
    }


def thread_response(thread_id: str, params: dict) -> dict:
    return {
        "approvalPolicy": params.get("approvalPolicy", "on-request"),
        "approvalsReviewer": params.get("approvalsReviewer", "user"),
        "cwd": params.get("cwd", "/"), "model": params.get("model", "stub-model"), "modelProvider": "openai",
        "sandbox": {"type": "readOnly"}, "thread": thread_payload(thread_id),
    }


def complete_turn(thread_id: str, turn_id: str, text: str, status: str = "completed") -> None:
    send({"method": "item/completed", "params": {"threadId": thread_id, "turnId": turn_id,
          "item": {"id": f"{turn_id}-msg", "type": "agentMessage", "text": text, "phase": "final_answer"}}})
    send({"method": "turn/completed", "params": {"threadId": thread_id, "turn": {"id": turn_id, "items": [], "status": status}}})


def main() -> None:
    turn_counter = iter(range(1, 1000))
    for line in sys.stdin:
        msg = json.loads(line)
        method = msg.get("method")
        log({"received": method, "params": msg.get("params"), "id": msg.get("id")})
        if "id" in msg and method is None:
            # response to one of our server requests (approval decision)
            decision = (msg.get("result") or {}).get("decision")
            turn_id = _pending_approval.pop(str(msg["id"]), None)
            log({"decision": decision, "turn": turn_id})
            if turn_id and _turn_state.get(turn_id) == "awaiting":
                thread_id = "thread-1"
                if BEHAVIOR == "hang":
                    _turn_state[turn_id] = "hanging"
                else:
                    _turn_state[turn_id] = "done"
                    complete_turn(thread_id, turn_id, f"decision={decision}")
            continue
        if method == "initialize":
            send({"id": msg["id"], "result": {"userAgent": "stub", "codexHome": "/tmp", "platformFamily": "linux",
                                               "platformOs": "linux", "serverVersion": "stub"}})
        elif method == "initialized":
            continue
        elif method in ("thread/start", "thread/resume"):
            params = msg.get("params") or {}
            thread_id = params.get("threadId", "thread-1")
            send({"id": msg["id"], "result": thread_response(thread_id, params)})
        elif method == "turn/start":
            turn_id = f"turn-{next(turn_counter)}"
            thread_id = msg["params"]["threadId"]
            send({"id": msg["id"], "result": {"turn": {"id": turn_id, "items": [], "status": "inProgress"}}})
            # The real server has latency between the turn/start response and the
            # first notification; CodexClient registers the turn queue in that window.
            time.sleep(0.05)
            send({"method": "turn/started", "params": {"threadId": thread_id, "turn": {"id": turn_id, "items": [], "status": "inProgress"}}})
            if BEHAVIOR == "complete":
                complete_turn(thread_id, turn_id, "hello from stub")
            else:
                server_id = str(next(_server_ids))
                _pending_approval[server_id] = turn_id
                _turn_state[turn_id] = "awaiting"
                send({"id": server_id, "method": "item/commandExecution/requestApproval",
                      "params": {"threadId": thread_id, "turnId": turn_id, "itemId": f"{turn_id}-cmd",
                                 "command": ["rm", "-rf", "build"], "cwd": "/"}})
        elif method == "turn/interrupt":
            turn_id = msg["params"]["turnId"]
            thread_id = msg["params"]["threadId"]
            send({"id": msg["id"], "result": {}})
            if _turn_state.get(turn_id) != "done":
                _turn_state[turn_id] = "done"
                complete_turn(thread_id, turn_id, "", status="interrupted")
        else:
            send({"id": msg["id"], "result": {}})


if __name__ == "__main__":
    main()

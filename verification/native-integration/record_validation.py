"""Snapshot the most recent native JUnit run; never infer unexecuted coverage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("stage")
parser.add_argument("--command", required=True)
parser.add_argument("--suite-contains")
args = parser.parse_args()
module = Path(__file__).resolve().parent
repo = module.parent.parent
results = Path(os.environ["TEMP"]) / "harness-protocol-build/harness-native-integration/test-results/test"
rows = []
for path in sorted(results.glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    if args.suite_contains and args.suite_contains not in suite.get("name", ""):
        continue
    for case in suite.findall("testcase"):
        failure = case.find("failure")
        error = case.find("error")
        rows.append(dict(suite=suite.get("name"), timestamp=suite.get("timestamp"), name=case.get("name"),
                         result="failed" if failure is not None or error is not None else "skipped" if case.find("skipped") is not None else "passed",
                         detail=(failure.text if failure is not None else error.text if error is not None else None)))
assert rows, "No native test results found"
sources = {}
source_modules = (
    "protocol/core",
    "protocol/conformance",
    "implementations/shared/runtime",
    "implementations/shared/process-bridge",
    "implementations/codex",
    "implementations/gemini-cli",
    "implementations/koog",
    "verification/native-integration",
)
for source_module in source_modules:
    for path in sorted((repo / source_module / "src").rglob("*.kt")):
        sources[path.relative_to(repo).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
document = dict(stage=args.stage, command=args.command, tests=len(rows),
                failed=sum(r["result"] == "failed" for r in rows), skipped=sum(r["result"] == "skipped" for r in rows),
                matrix=rows, sourceSha256=sources,
                boundary="Actual configured SDK/runtime; controlled model responses and explicitly documented delivery faults. No external paid model.")
target = module / "evidence" / (args.stage + ".json")
target.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps({k: document[k] for k in ("stage", "tests", "failed", "skipped")}))

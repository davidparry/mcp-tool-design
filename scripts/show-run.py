#!/usr/bin/env python3
"""Prints one run as a readable transcript: each model call's text, every tool call with its full
arguments and result, the answer cards, and the verifier's checks and failures.

    scripts/show-run.py results/pilot/pilot-qwen-v2 pilot-qwen-v2-F1-endpoint-r1
"""
import json
import sys
from pathlib import Path


def records(path):
    if not path.exists():
        return {}
    found = {}
    for line in path.read_text().splitlines():
        if line.strip():
            record = json.loads(line)
            found[record["runId"]] = record
    return found


def main():
    batch, run_id = Path(sys.argv[1]), sys.argv[2]
    run = records(batch / "runs.jsonl").get(run_id)
    if run is None:
        sys.exit(f"No run {run_id} in {batch}")
    verification = records(batch / "verification.jsonl").get(run_id)
    print(f"{run_id}: {run['taskId']} {run['arm']} {run['model']}, stopped {run['stopReason']}")
    for i, message in enumerate(run.get("customerMessages") or []):
        print(f"\nCUSTOMER {i + 1}: {message}")
    for turn in run["turns"]:
        usage = turn.get("usage") or {}
        print(f"\n--- model call {turn['index']} (message {turn['segment'] + 1}, "
              f"{usage.get('inputTokens')} in, {usage.get('outputTokens')} out)"
              + (" [reminded]" if turn.get("reminded") else ""))
        if turn.get("text"):
            print("TEXT:", turn["text"])
        for call in turn.get("toolCalls") or []:
            print(f"CALL {call['name']} [{call['kind']}{', error' if call.get('isError') else ''}]: "
                  f"{call['arguments']}")
            if call["kind"] != "final_answer":
                print("RESULT:", call.get("resultText"))
    for change in run.get("worldChanges") or []:
        print("\nWORLD CHANGE:", change)
    for i, card in enumerate(run.get("cards") or []):
        print(f"\nCARD {i + 1}:", json.dumps(card, indent=1))
    if verification:
        print(f"\nVERIFIER: valid={verification['valid']} success={verification['success']}")
        for check in verification.get("checks") or []:
            print(f"  {'PASS' if check['passed'] else 'FAIL'} {check['name']}"
                  f"{'' if check['required'] else ' (soft)'}: {check.get('detail')}")
        for failure in verification.get("failures") or []:
            print(f"  {failure['failureClass']} ({failure['severity']}): {failure['detail']}")


if __name__ == "__main__":
    main()

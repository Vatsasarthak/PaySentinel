#!/usr/bin/env python3
"""
Reproducible end-to-end benchmark for PaySentinel (Python 3.9+, standard library only).

It seeds a ledger account, submits N payouts with C concurrent clients, then waits until every
payout reaches a terminal state, and reports:
  * API latency and request throughput for POST /api/v1/payouts
  * end-to-end processing throughput (accept -> COMPLETED/FAILED)
  * a money-movement check: final balance == seed - sum(COMPLETED amounts)

Requires the dev profile (ledger seeding enabled) and an ADMIN key:
  python loadtest/benchmark.py --base-url http://localhost:8080 --admin-key <ADMIN_KEY> \
      --client-key <CLIENT_KEY> --payouts 2000 --concurrency 50

Results depend on hardware and on payout.gateway.* / payout.worker.* settings; record them with
the numbers you publish.
"""
import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

TERMINAL = {"COMPLETED", "FAILED", "CANCELLED", "REJECTED"}


def call(method, url, key, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("X-API-Key", key)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def percentile(values, pct):
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round(pct / 100 * len(ordered)) - 1))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--client-key", required=True)
    parser.add_argument("--admin-key", required=True)
    parser.add_argument("--account", default="bench_account",
                        help="ledger account granted to the client key; its balance is overwritten")
    parser.add_argument("--payouts", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--amount", type=int, default=100,
                        help="payout amount in whole paise (minor units); 100 = INR 1.00")
    parser.add_argument("--timeout", type=int, default=600, help="seconds to wait for processing")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    account = args.account
    seed = args.payouts * args.amount * 2
    status, body = call("POST", f"{base}/api/v1/payouts/ledger/seed?accountId={account}&amount={seed}", args.admin_key)
    if status != 200:
        raise SystemExit(f"Seeding failed ({status}): {body}  -- is the dev profile active?")

    run_id = uuid.uuid4().hex[:8]

    def submit(i):
        payload = {"recipientId": account, "amount": args.amount, "currency": "INR",
                   "idempotencyKey": f"bench-{run_id}-{i:07d}"}
        throttled = 0
        while True:
            started = time.perf_counter()
            code, text = call("POST", f"{base}/api/v1/payouts", args.client_key, payload)
            elapsed = time.perf_counter() - started
            if code != 429:
                return code, elapsed, (json.loads(text)["id"] if code == 202 else None), throttled
            throttled += 1
            time.sleep(1)

    print(f"Submitting {args.payouts} payouts with {args.concurrency} concurrent clients (account {account})")
    wall_start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(submit, range(args.payouts)))
    submit_seconds = time.perf_counter() - wall_start

    latencies = [r[1] * 1000 for r in results]
    accepted = [r[2] for r in results if r[0] == 202]
    errors = {}
    throttled = sum(r[3] for r in results)
    for code, _, _, _ in results:
        if code != 202:
            errors[code] = errors.get(code, 0) + 1

    print("\n== API (POST /api/v1/payouts) ==")
    print(f"requests:        {len(results)}  accepted: {len(accepted)}  errors: {errors or 0}  "
          f"429 retries: {throttled}")
    print(f"throughput:      {len(results) / submit_seconds:.1f} req/s over {submit_seconds:.1f}s")
    print(f"latency ms:      avg {statistics.mean(latencies):.1f}  p50 {percentile(latencies, 50):.1f}  "
          f"p95 {percentile(latencies, 95):.1f}  p99 {percentile(latencies, 99):.1f}  max {max(latencies):.1f}")

    print("\nWaiting for asynchronous processing...")
    pending = set(accepted)
    final = {}
    deadline = time.time() + args.timeout
    while pending and time.time() < deadline:
        def fetch(pid):
            code, text = call("GET", f"{base}/api/v1/payouts/{pid}", args.client_key)
            return pid, (json.loads(text) if code == 200 else None)
        with ThreadPoolExecutor(max_workers=min(args.concurrency, 32)) as pool:
            for pid, payout in pool.map(fetch, list(pending)):
                if payout and payout["status"] in TERMINAL and payout["fundsStatus"] in ("SETTLED", "RELEASED", "NONE"):
                    final[pid] = payout
                    pending.discard(pid)
        if pending:
            time.sleep(1)
    process_seconds = time.perf_counter() - wall_start

    by_status = {}
    for payout in final.values():
        by_status[payout["status"]] = by_status.get(payout["status"], 0) + 1
    completed_amount = sum(p["amount"] for p in final.values() if p["status"] == "COMPLETED")
    _, balance_text = call("GET", f"{base}/api/v1/payouts/ledger/balance/{account}", args.admin_key)
    balance = int(balance_text)

    print("\n== End-to-end processing ==")
    print(f"terminal:        {len(final)} / {len(accepted)}  by status: {by_status}  unfinished: {len(pending)}")
    print(f"throughput:      {len(final) / process_seconds:.1f} payouts/s (wall clock {process_seconds:.1f}s "
          f"from first request to last terminal state)")
    print(f"ledger check:    balance {balance}  expected {seed - completed_amount}  "
          f"{'OK' if balance == seed - completed_amount and not pending else 'MISMATCH'}")


if __name__ == "__main__":
    main()

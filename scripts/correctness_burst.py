"""
Correctness burst tests - race conditions and invariants, not performance.

Reproduces the three scenarios below against a live deployment. Each is a
one-command run:

    python3 scripts/correctness_burst.py get-or-create
    python3 scripts/correctness_burst.py idempotent-retry
    python3 scripts/correctness_burst.py conservation
    python3 scripts/correctness_burst.py all

Target defaults to $STRESS_TEST_BASE_URL, or pass --base-url explicitly.
Every check prints PASS/FAIL with the actual measured values - nothing here
is asserted without being run against the real service.
"""
import argparse
import concurrent.futures
import os
import random
import sys
import uuid

import requests

DEFAULT_BASE_URL = os.environ.get("STRESS_TEST_BASE_URL", "http://localhost:8080")
SESSION = requests.Session()


def create_wallet(base_url, token, initial_balance_paise=0):
    return SESSION.post(
        base_url + "/wallets",
        headers={"Authorization": f"Bearer {token}"},
        json={"initial_balance_paise": initial_balance_paise},
    )


def get_wallet(base_url, wallet_id):
    return SESSION.get(base_url + f"/wallets/{wallet_id}")


def transfer(base_url, idempotency_key, from_id, to_id, amount_paise):
    return SESSION.post(
        base_url + "/transfers",
        json={
            "idempotency_key": idempotency_key,
            "from": str(from_id),
            "to": str(to_id),
            "amount_paise": amount_paise,
        },
    )


def test_concurrent_get_or_create(base_url, n=50):
    print(f"\n=== Concurrent get-or-create: {n} simultaneous POST /wallets, same brand-new user ===")
    token = f"race_user_{uuid.uuid4().hex[:12]}"

    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:
        responses = list(pool.map(lambda _: create_wallet(base_url, token, 1000), range(n)))

    ids = {r.json()["id"] for r in responses if r.status_code == 201}
    codes = [r.status_code for r in responses]
    ok = codes.count(201) == n
    single_wallet = len(ids) == 1

    print(f"  requests={n} http_201={codes.count(201)} distinct_wallet_ids={len(ids)}")
    passed = ok and single_wallet
    print(f"  {'PASS' if passed else 'FAIL'}: expected {n}x 201 and exactly 1 wallet id, "
          f"got {codes.count(201)}x 201 and {len(ids)} distinct id(s)")
    return passed


def test_idempotent_retry_storm(base_url, k=30):
    print(f"\n=== Idempotent retry storm: {k} concurrent POST /transfers, same key+body ===")
    a = create_wallet(base_url, f"idem_a_{uuid.uuid4().hex[:8]}", 1_000_000).json()["id"]
    b = create_wallet(base_url, f"idem_b_{uuid.uuid4().hex[:8]}", 1_000_000).json()["id"]
    idempotency_key = str(uuid.uuid4())
    amount = 500

    before_a = get_wallet(base_url, a).json()["balance_paise"]
    before_b = get_wallet(base_url, b).json()["balance_paise"]

    with concurrent.futures.ThreadPoolExecutor(max_workers=k) as pool:
        responses = list(pool.map(
            lambda _: transfer(base_url, idempotency_key, a, b, amount), range(k)))

    bodies = [(r.status_code, r.json()) for r in responses]
    transfer_ids = {body["id"] for _, body in bodies}
    statuses = {body["status"] for _, body in bodies}
    all_201 = all(code == 201 for code, _ in bodies)

    after_a = get_wallet(base_url, a).json()["balance_paise"]
    after_b = get_wallet(base_url, b).json()["balance_paise"]
    debited_once = (before_a - after_a) == amount
    credited_once = (after_b - before_b) == amount

    print(f"  requests={k} all_201={all_201} distinct_transfer_ids={len(transfer_ids)} "
          f"distinct_statuses={statuses}")
    print(f"  wallet A: {before_a} -> {after_a} (debited {before_a - after_a}, expected {amount})")
    print(f"  wallet B: {before_b} -> {after_b} (credited {after_b - before_b}, expected {amount})")

    passed = all_201 and len(transfer_ids) == 1 and debited_once and credited_once
    print(f"  {'PASS' if passed else 'FAIL'}: expected 1 distinct transfer id and exactly one "
          f"debit/credit of {amount}, got {len(transfer_ids)} id(s), "
          f"debit={before_a - after_a}, credit={after_b - before_b}")
    return passed


def test_conservation_under_contention(base_url, num_wallets=5, num_transfers=200, concurrency=40):
    print(f"\n=== Conservation under contention: {num_transfers} concurrent transfers "
          f"across {num_wallets} wallets (all directions) ===")
    initial_balance = 1_000_000
    wallet_ids = [
        create_wallet(base_url, f"cons_{uuid.uuid4().hex[:8]}", initial_balance).json()["id"]
        for _ in range(num_wallets)
    ]
    total_before = initial_balance * num_wallets

    def fire_one(_):
        a, b = random.sample(wallet_ids, 2)  # direction is random - A->B and B->A both happen
        amount = random.randint(100, 5000)
        return transfer(base_url, str(uuid.uuid4()), a, b, amount)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        responses = list(pool.map(fire_one, range(num_transfers)))

    succeeded = sum(1 for r in responses if r.status_code == 201 and r.json()["status"] == "SUCCESS")
    declined = sum(1 for r in responses if r.status_code == 201 and r.json()["status"] == "DECLINED_INSUFFICIENT_FUNDS")
    errored = num_transfers - succeeded - declined

    balances = [get_wallet(base_url, wid).json()["balance_paise"] for wid in wallet_ids]
    total_after = sum(balances)
    any_negative = any(b < 0 for b in balances)

    print(f"  transfers: succeeded={succeeded} declined={declined} errored={errored}")
    print(f"  total balance: before={total_before} after={total_after} "
          f"(delta={total_after - total_before})")
    print(f"  per-wallet balances: {balances}")

    passed = (errored == 0) and (total_after == total_before) and not any_negative
    print(f"  {'PASS' if passed else 'FAIL'}: expected total balance unchanged and no negative "
          f"balances, got delta={total_after - total_before}, any_negative={any_negative}")
    return passed


TESTS = {
    "get-or-create": test_concurrent_get_or_create,
    "idempotent-retry": test_idempotent_retry_storm,
    "conservation": test_conservation_under_contention,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("test", choices=list(TESTS) + ["all"])
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    args = parser.parse_args()

    print(f"Target: {args.base_url}")
    names = list(TESTS) if args.test == "all" else [args.test]
    results = {name: TESTS[name](args.base_url) for name in names}

    print("\n=== Summary ===")
    for name, passed in results.items():
        print(f"  {name}: {'PASS' if passed else 'FAIL'}")
    sys.exit(0 if all(results.values()) else 1)


if __name__ == "__main__":
    main()

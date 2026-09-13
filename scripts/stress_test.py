import concurrent.futures
import os
import random
import statistics
import sys
import time
import uuid

import requests

TARGETS = {
    "local": "http://localhost:8080",
    "prod": "https://wallet-raghul-baskar.onrender.com",
}

ENV = os.environ.get("STRESS_TEST_ENV", "local")
BASE = os.environ.get("STRESS_TEST_BASE_URL", TARGETS.get(ENV))
DURATION_S = int(os.environ.get("STRESS_TEST_DURATION_S", "20"))
CONCURRENCY = int(os.environ.get("STRESS_TEST_CONCURRENCY", "30"))

SESSION = requests.Session()


def rand_id(prefix):
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def timed(method, path, **kwargs):
    start = time.perf_counter()
    try:
        resp = SESSION.request(method, BASE + path, timeout=15, **kwargs)
        return time.perf_counter() - start, resp.status_code
    except Exception:
        return time.perf_counter() - start, -1


def create_wallet_get_id(token):
    r = SESSION.post(
        BASE + "/wallets",
        headers={"Authorization": f"Bearer {token}"},
        json={"initial_balance_paise": 10_000_000},
    )
    return r.json()["id"]


def percentiles(samples_ms):
    if not samples_ms:
        return {}
    s = sorted(samples_ms)
    n = len(s)

    def pct(p):
        return s[min(n - 1, int(round(p * (n - 1))))]

    return {
        "count": n,
        "min": s[0],
        "p50": pct(0.50),
        "p90": pct(0.90),
        "p95": pct(0.95),
        "p99": pct(0.99),
        "max": s[-1],
        "mean": statistics.fmean(s),
    }


def run_burst(name, fn, duration_s, concurrency):
    stop_at = time.perf_counter() + duration_s

    def worker():
        local = []
        while time.perf_counter() < stop_at:
            elapsed, code = fn()
            local.append((elapsed * 1000.0, code))
        return local

    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(worker) for _ in range(concurrency)]
        for f in futures:
            results.extend(f.result())

    latencies = [r[0] for r in results]
    codes = [r[1] for r in results]
    ok = sum(1 for c in codes if 200 <= c < 300)
    errors = sum(1 for c in codes if c < 0 or c >= 400)
    total = len(results)
    stats = percentiles(latencies)
    throughput = total / duration_s
    print(f"\n== {name} ==")
    print(f"  requests={total} throughput={throughput:.1f} rps  ok={ok} errors={errors}")
    if stats:
        print(f"  latency ms: min={stats['min']:.1f} p50={stats['p50']:.1f} p90={stats['p90']:.1f} "
              f"p95={stats['p95']:.1f} p99={stats['p99']:.1f} max={stats['max']:.1f} mean={stats['mean']:.1f}")
    return {"name": name, "total": total, "ok": ok, "errors": errors, "throughput": throughput, "stats": stats}


def guard_against_prod():
    if ENV != "prod":
        return
    if os.environ.get("CONFIRM_PROD") != "yes":
        print(f"Refusing to stress-test {BASE} — this is a live deployment, not a local instance.")
        print("Set CONFIRM_PROD=yes if you really mean to load-test the deployed service.")
        sys.exit(1)


def main():
    if not BASE:
        print(f"Unknown STRESS_TEST_ENV={ENV!r} and no STRESS_TEST_BASE_URL set.")
        sys.exit(1)
    guard_against_prod()

    print(f"Target: {BASE}  (env={ENV}, duration={DURATION_S}s, concurrency={CONCURRENCY})")
    print("Setting up fixtures...")
    wallet_ids = [create_wallet_get_id(rand_id("stress_wallet")) for _ in range(20)]
    print(f"  {len(wallet_ids)} wallets created, each funded with 10,000,000 paise")

    login_accounts = []
    for _ in range(20):
        email = rand_id("stress") + "@example.com"
        SESSION.post(BASE + "/auth/signup", json={"email": email, "password": "StressTest123!"})
        login_accounts.append(email)
    print(f"  {len(login_accounts)} accounts pre-created for login test")

    def hello():
        return timed("GET", "/hello")

    def get_wallet():
        return timed("GET", f"/wallets/{random.choice(wallet_ids)}")

    def create_wallet():
        token = rand_id("burst_wallet")
        return timed("POST", "/wallets", headers={"Authorization": f"Bearer {token}"},
                     json={"initial_balance_paise": 0})

    def signup():
        email = rand_id("burst") + "@example.com"
        return timed("POST", "/auth/signup", json={"email": email, "password": "StressTest123!"})

    def login():
        email = random.choice(login_accounts)
        return timed("POST", "/auth/login", json={"email": email, "password": "StressTest123!"})

    def transfer():
        a, b = random.sample(wallet_ids, 2)
        return timed("POST", "/transfers", json={
            "idempotency_key": str(uuid.uuid4()),
            "from": a,
            "to": b,
            "amount_paise": random.randint(100, 1000),
        })

    print("  seeding transfer ids for GET /transfers/{id} burst...")
    transfer_ids = []
    for _ in range(30):
        a, b = random.sample(wallet_ids, 2)
        r = SESSION.post(BASE + "/transfers", json={
            "idempotency_key": str(uuid.uuid4()), "from": a, "to": b, "amount_paise": 100,
        })
        if r.status_code == 201:
            transfer_ids.append(r.json()["id"])

    def get_transfer():
        return timed("GET", f"/transfers/{random.choice(transfer_ids)}")

    results = [
        run_burst("GET /hello", hello, DURATION_S, CONCURRENCY),
        run_burst("GET /wallets/{id}", get_wallet, DURATION_S, CONCURRENCY),
        run_burst("POST /wallets (create)", create_wallet, DURATION_S, CONCURRENCY),
        run_burst("POST /auth/signup", signup, DURATION_S, CONCURRENCY),
        run_burst("POST /auth/login", login, DURATION_S, CONCURRENCY),
        run_burst("POST /transfers", transfer, DURATION_S, CONCURRENCY),
        run_burst("GET /transfers/{id}", get_transfer, DURATION_S, CONCURRENCY),
    ]

    print("\n=== Summary ===")
    for r in results:
        s = r["stats"]
        if s:
            print(f"{r['name']:<28} rps={r['throughput']:>6.1f}  p50={s['p50']:>7.1f}ms  "
                  f"p90={s['p90']:>7.1f}ms  p95={s['p95']:>7.1f}ms  p99={s['p99']:>7.1f}ms  errors={r['errors']}")


if __name__ == "__main__":
    main()

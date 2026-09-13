# wallet
Light weight Wallet &amp; P2P Transfer

**Live:** https://walletsvc-production.up.railway.app

A small ledger service for creating wallets and transferring money between them, built to stay correct under concurrent load: no double-spends, no duplicate transfers on retry, no overdrafts, and no lost updates when the same request races itself.

- API docs: [`/docs`](https://walletsvc-production.up.railway.app/docs)
- Public metrics dashboard: https://jademongoose1486.grafana.net/public-dashboards/272cadd13d4648eca495eab32a691023
- Public logs dashboard: https://jademongoose1486.grafana.net/public-dashboards/ddbf34081bea4cee97887f8257895851
- Run locally: `docker compose up`
- Verify correctness against a live deployment: `python3 scripts/correctness_burst.py all --base-url <url>`
- Load test: `python3 scripts/stress_test.py`

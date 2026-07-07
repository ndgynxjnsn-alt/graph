# Grafana LGTM showcase for Spring Boot

A self-contained observability demo: a small **Spring Boot 3** app (Java 25) instrumented for
**metrics, logs, traces and continuous profiling**, wired into a full **Grafana LGTM+P** stack —
**L**oki (logs), **G**rafana (dashboards), **T**empo (traces), **M**imir (metrics) and
**P**yroscope (profiles) — the storage backends all using **MinIO** as S3 object storage, plus
**Grafana Alloy** as the single collector. A **k6** load test fires a mix of good and failing
requests so there is always something to look at.

```
                 ┌────────── metrics (scrape /actuator/prometheus) ──────────┐
                 │                                                            ▼
  loadgen ──▶  app ──OTLP traces──▶ Alloy ──tail-sampled──▶ Tempo         Mimir
                 │                    │  (keep ALL failures)   │             │
                 └──push logs─────────┼────────────────────────┼─────────────┤
                                      ▼                         ▼             ▼
                                    Loki ───────────────── MinIO (S3) ── Grafana
```

## What you get

| Component | Role | URL (local) |
|-----------|------|-------------|
| **app** | Spring Boot sample under observation | http://localhost:8080 |
| **Grafana** | Dashboards + datasources (pre-provisioned) | http://localhost:3000 (`admin` / `admin`) |
| **Mimir** | Metrics store | http://localhost:9009 |
| **Loki** | Logs store | http://localhost:3100 |
| **Tempo** | Traces store | http://localhost:3200 |
| **Pyroscope** | Continuous profiling store | http://localhost:4040 |
| **Alloy** | Collector (scrape + OTLP receiver) | http://localhost:12345 |
| **MinIO** | S3 storage for Mimir/Loki/Tempo | http://localhost:9001 (`minioadmin` / `minioadmin`) |
| **k6** | Load test (incl. 404s / 500s) | — |

## Requirements

- Docker Engine with the Compose plugin (`docker compose`). That's it — the app is built inside
  a container, so you do **not** need Java or Maven on the host.

## Start everything

From the repository root:

```bash
docker compose up -d --build
```

The first run builds the app image and pulls the stack images (a few minutes). Startup order is
handled automatically: MinIO comes up, buckets are created, the backends start, then Alloy, the
app and the load generator.

Check status:

```bash
docker compose ps
docker compose logs -f app        # or: alloy / loadgen / grafana ...
```

### Open the dashboard

1. Go to **http://localhost:3000** and log in with `admin` / `admin`.
2. Open **Dashboards → Showcase → Spring Boot Showcase**.

Within ~30 seconds you'll see live data: requests/sec, error rate, p50/p95/p99 response times,
CPU, JVM memory, request rate per endpoint, GC, and a live log panel. Because the load generator
sends a steady mix of traffic, the 2xx / 4xx / 5xx panel and the error-rate stat stay populated.

## The app

Spring Boot 3.5 + Actuator, instrumented with Micrometer (Prometheus metrics), Micrometer Tracing
→ OpenTelemetry (OTLP traces), and a Loki logback appender (structured logs with the `traceId`
embedded). Endpoints:

| Endpoint | Behaviour |
|----------|-----------|
| `GET /` | index, lists endpoints |
| `GET /api/hello?name=…` | 200 |
| `GET /api/work` | 200 with random 20–400 ms latency |
| `GET /api/flaky` | fails ~1/3 of the time (500) |
| `GET /api/error` | always 400 |
| `GET /api/boom` | always 500 |
| anything else | 404 |

### Tracing: every trace is kept, so every log link resolves

The app records **100%** of traces and Alloy forwards **all** of them to Tempo. This is a
deliberate choice: a log line is written *during* a request, but any drop-sampling decision would
happen *after* the request finishes — so if traces were dropped, their `traceId` would already be
in the logs, leaving dead "not found" links. Keeping every trace means **every `traceId` you see
in a log resolves to a real trace**, and failing requests are (of course) always traced.

- **5xx** server errors are marked as span status `ERROR` (find them with TraceQL `{status=error}`).
- **4xx** client errors (including a plain **404**) are not "errors" by OpenTelemetry convention,
  but they still carry Spring's `outcome=CLIENT_ERROR` attribute and are fully traced.

Verify — hit a non-existent endpoint and find its trace in Tempo:

```bash
curl -s http://localhost:8080/this-does-not-exist        # -> 404
curl -s -G http://localhost:3200/api/search --data-urlencode 'q={status=error}'   # the 5xx traces
```

> **Want to reduce trace volume?** [`observability/alloy/config.alloy`](observability/alloy/config.alloy)
> contains a commented-out **tail-sampling** recipe that keeps *all* failures plus a 20% sample of
> healthy traffic. The trade-off is spelled out there: dropped successful traces will leave dead
> log→trace links, because the drop happens after the log line was written.

### Logs ↔ traces correlation

Errors are handled by a `@RestControllerAdvice`
([`GlobalExceptionHandler`](app/src/main/java/com/example/showcase/GlobalExceptionHandler.java)) so
they are logged *inside* the trace scope — every log line, success or failure, carries a real
`traceId=<id>` (never `traceId=none`). The Loki datasource has a derived field that turns it into a
clickable link straight to the trace in Tempo, and from a Tempo span you can jump back to the
matching logs in Loki. Open **Explore → Loki**, query `{app="showcase"}`, expand a line and click
the **TraceID** link.

## Continuous profiling (Pyroscope)

The app image ships with the **Grafana Pyroscope Java agent** (async-profiler based: CPU,
allocation and lock profiling). The agent only attaches when `PYROSCOPE_SERVER_ADDRESS` is set,
so the image also runs fine standalone.

The agent jar is downloaded at image build time (pinned version). The same download by hand:

```bash
curl -fL -o pyroscope.jar https://github.com/grafana/pyroscope-java/releases/download/v2.8.0/pyroscope.jar
```

Explore profiles in Grafana: **Explore → Pyroscope datasource → `process_cpu` → 
`service_name="showcase"`** — or open http://localhost:4040 directly. Flamegraphs show exactly
where the app burns CPU / allocates under the k6 load.

## Load testing (Grafana k6)

[`k6/load.js`](k6/load.js) drives a constant-arrival-rate mix (~3 req/s by default): ~60%
success, plus deliberate 404s, 400s-ish flaky calls and 500s, with checks and thresholds. Tune
via env vars on the `k6` service: `TARGET`, `RATE` (req/s), `DURATION`.

Run a one-off, heavier blast by hand:

```bash
docker compose run --rm -e RATE=20 -e DURATION=2m k6 run --quiet /scripts/load.js
```

## Rebuild after changing the app

```bash
docker compose up -d --build app
```

## Stop / clean up

```bash
docker compose down            # stop, keep data
docker compose down -v         # stop and delete all volumes (MinIO/Grafana/backends)
```

## Building the app on its own (optional)

The app is a normal Maven project under [`app/`](app/) with a Maven wrapper, so no host Maven is
needed:

```bash
cd app
./mvnw clean package           # produces target/showcase-0.0.1-SNAPSHOT.jar
```

## Running on Docker Swarm

There is a dedicated, **swarm-tested** stack file: [`docker-stack.yml`](docker-stack.yml). It was
verified end-to-end on a real single-node swarm on this machine (all four signals flowing, rings
healthy, zero gRPC errors).

```bash
# 1. The app image must exist on every node — swarm does not build.
docker build -t showcase-app:latest ./app
#    Multi-node: tag + push to a registry instead, and update `image:` in docker-stack.yml.

# 2. Deploy. If your NIC has several addresses (e.g. multiple IPv6), swarm init
#    refuses to guess — pass the node's IPv4 explicitly.
docker swarm init --advertise-addr <node-ipv4>
docker stack deploy -c docker-stack.yml showcase

# 3. Watch it converge (backends crash-loop politely until MinIO buckets exist)
docker service ls
```

### The Loki/Tempo/Mimir swarm networking problem — and the fix

On swarm, tasks attached to an overlay network get **multiple interfaces** (the overlay, plus the
ingress network whenever ports are published). Loki, Tempo, Mimir — and Pyroscope, which shares
the same architecture — autodetect the address they advertise to their own ring/memberlist, and
routinely pick the wrong interface. Result: the components of a service can't reach *each other*,
even though external clients can reach the service fine. In `docker service logs` it shows up as
gRPC errors like:

```
rpc error: code = Unavailable desc = ... dial tcp 10.0.0.8:4040: i/o timeout
```

(`10.0.0.x` = the ingress network — the service is dialling *itself* on the wrong interface.
This exact failure was reproduced on this machine's swarm with an unpinned Pyroscope.)

The fix in this repo: every ring is **pinned to `127.0.0.1`** — a single-binary instance only
ever needs to reach itself, so loopback is always right, regardless of how many NICs swarm
attaches. For Loki/Tempo/Mimir it lives in their config files
([`loki.yml`](observability/loki/loki.yml), [`tempo.yml`](observability/tempo/tempo.yml),
[`mimir.yml`](observability/mimir/mimir.yml)); for Pyroscope it's CLI flags on the service
(see `docker-stack.yml`). If you later scale a backend to multiple replicas (microservices mode),
replace the loopback pinning with `instance_interface_names` (e.g. `[eth0]`) or DNS-based
memberlist join on `tasks.<service>` with `endpoint_mode: dnsrr`.

### Other swarm notes

- `docker stack deploy` ignores `build:` and `depends_on:`; ordering is handled by restart
  policies (services retry until MinIO/buckets are up). Config files ship as swarm `configs`.
- **Storage & placement (multi-node):** pin each stateful service (MinIO, Mimir, Loki, Tempo,
  Grafana, Pyroscope) to a node via `deploy.placement.constraints` so it finds its volume again,
  or use shared storage. MinIO can run distributed across nodes for real HA.
- **Scaling the app:** with multiple `app` replicas, replace Alloy's static scrape target with
  DNS discovery of `tasks.app` so every replica is scraped.
- **⚠️ IPv6 and the routing mesh:** published swarm ports listen dual-stack, but on this host
  only IPv4 is actually forwarded — requests to `http://localhost:<port>` can hang because
  `localhost` resolves to `::1` first. Use `http://127.0.0.1:<port>` (or the node's IPv4)
  when curling published services.
- **⚠️ Snap-packaged Docker cannot run swarm workloads.** If Docker was installed via
  `snap install docker`, every swarm task fails with
  `mkdir /var/lib/docker: read-only file system` (the swarm executor writes task state under a
  path the snap confines; plain `docker run`/compose is unaffected). Install Docker from apt
  on all swarm nodes.

## Layout

```
.
├── docker-compose.yml            # local dev: wires the whole stack together
├── docker-stack.yml              # swarm: same stack via `docker stack deploy` (swarm-tested)
├── app/                          # Spring Boot app + Dockerfile (incl. Pyroscope Java agent)
│   ├── src/main/java/...         # controllers + error handling
│   └── src/main/resources/       # application.yml, logback-spring.xml (Loki appender)
├── k6/load.js                    # Grafana k6 load test
└── observability/
    ├── alloy/config.alloy        # scrape -> Mimir; OTLP -> Tempo
    ├── mimir/mimir.yml           # metrics, S3 backend (rings pinned to loopback for swarm)
    ├── loki/loki.yml             # logs, S3 backend (          "          )
    ├── tempo/tempo.yml           # traces, S3 backend (         "          )
    └── grafana/
        ├── provisioning/         # datasources (Mimir/Loki/Tempo/Pyroscope) + dashboard provider
        └── dashboards/           # Spring Boot Showcase dashboard
```

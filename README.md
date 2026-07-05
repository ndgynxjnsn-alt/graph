# Grafana LGTM showcase for Spring Boot

A self-contained observability demo: a small **Spring Boot 3** app (Java 25) instrumented for
**metrics, logs and traces**, wired into a full **Grafana LGTM** stack — **L**oki (logs),
**G**rafana (dashboards), **T**empo (traces) and **M**imir (metrics) — all backed by **MinIO**
as S3 object storage, plus **Grafana Alloy** as the single collector. A load generator fires a
mix of good and failing requests so there is always something to look at.

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
| **Alloy** | Collector (scrape + OTLP receiver) | http://localhost:12345 |
| **MinIO** | S3 storage for all three backends | http://localhost:9001 (`minioadmin` / `minioadmin`) |
| **loadgen** | Generates traffic (incl. 404s / 500s) | — |

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

## Running on a 5-node Docker Swarm

The stack is a standard Compose file; a few things change when moving from `docker compose` (one
host) to `docker stack deploy` (a swarm):

1. **Build and push the app image to a registry** the swarm can pull from — `docker stack deploy`
   does not build images:
   ```bash
   docker build -t <registry>/showcase-app:latest ./app
   docker push <registry>/showcase-app:latest
   ```
   Then set `image: <registry>/showcase-app:latest` for the `app` service (and drop `build:`).

2. **Deploy the stack:**
   ```bash
   docker stack deploy -c docker-compose.yml showcase
   ```
   Note: `docker stack deploy` ignores `build:`, `depends_on`, `restart:` and bind-mount relative
   paths behave differently — put the config files on a shared path or ship them as Docker
   [configs](https://docs.docker.com/reference/cli/docker/config/) / mounted volumes available on
   every node.

3. **Storage & placement.** MinIO, Mimir, Loki and Tempo keep local state (WAL / TSDB) in volumes.
   Pin each stateful service to a specific node with a `deploy.placement.constraints` rule (e.g.
   `node.hostname == node-1`) so it always finds its data, or back the volumes with shared/network
   storage. MinIO can also be run as a distributed deployment across nodes for real HA.

4. **Scaling the app.** If you scale the `app` service to multiple replicas, replace Alloy's
   static scrape target (`app:8080`) with service discovery
   (`discovery.dns` on the Swarm `tasks.app` DNS name) so every replica is scraped.

Because the app talks to Alloy/Loki by service name over OTLP/HTTP, the observability wiring works
unchanged on the swarm overlay network.

## Layout

```
.
├── docker-compose.yml            # wires the whole stack together
├── app/                          # Spring Boot app + Dockerfile
│   ├── src/main/java/...         # controllers + config
│   └── src/main/resources/       # application.yml, logback-spring.xml (Loki appender)
├── loadgen/loadgen.sh            # traffic generator (busybox sh)
└── observability/
    ├── alloy/config.alloy        # scrape -> Mimir; OTLP -> tail sample -> Tempo
    ├── mimir/mimir.yml           # metrics, S3 backend
    ├── loki/loki.yml             # logs, S3 backend
    ├── tempo/tempo.yml           # traces, S3 backend
    └── grafana/
        ├── provisioning/         # datasources + dashboard provider
        └── dashboards/           # Spring Boot Showcase dashboard
```

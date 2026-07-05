#!/bin/sh
# Small traffic generator so the dashboard has something to show.
# Fires a weighted mix of successful and FAILING requests against the app.
# Uses only busybox wget (no extra packages needed).

TARGET="${TARGET:-http://app:8080}"
# Average delay between requests, in tenths of a second (default ~0.4s -> ~2.5 req/s)
DELAY_TENTHS="${DELAY_TENTHS:-4}"

echo "loadgen: waiting for ${TARGET} to become available..."
until wget -q -O /dev/null "${TARGET}/actuator/health" 2>/dev/null; do
  sleep 2
done
echo "loadgen: app is up, generating traffic against ${TARGET}"

# Endpoint pool. Roughly 60% success, ~40% failures (incl. a plain 404),
# so both the 2xx and the 4xx/5xx panels — and error traces — stay populated.
i=0
while true; do
  r=$(( i % 10 ))
  case "$r" in
    0|1|2) path="/api/hello?name=grafana" ;;   # 200
    3|4)   path="/api/work" ;;                  # 200, variable latency
    5)     path="/" ;;                          # 200
    6|7)   path="/api/flaky" ;;                 # ~1/3 -> 500
    8)     path="/does-not-exist" ;;            # 404 (unmapped endpoint)
    9)     path="/api/boom" ;;                  # 500 (always)
  esac

  code=$(wget -q -O /dev/null -S "${TARGET}${path}" 2>&1 | awk '/HTTP\//{print $2; exit}')
  echo "GET ${path} -> ${code:-ERR}"

  i=$(( i + 1 ))
  sleep "0.${DELAY_TENTHS}"
done

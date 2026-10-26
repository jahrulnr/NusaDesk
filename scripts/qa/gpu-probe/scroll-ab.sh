#!/bin/bash
# QA only: run the scroll benchmark repeatedly against the currently running
# Electron arm and print one compact line per run so arms can be compared.
set -u
PORT=${1:-19224}
WHEELS=${2:-150}
CADENCE=${3:-33}
REPEATS=${4:-4}
SELECTOR=${5:-#event-stream}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)

for run in $(seq 1 "$REPEATS"); do
  out=$(timeout 180 node "$ROOT/scripts/qa/gpu-probe/electron_scroll.mjs" \
    "$PORT" "$WHEELS" "$CADENCE" "$SELECTOR" 2>&1) || { echo "run $run FAILED: $out"; continue; }
  echo "$out" | python3 -c '
import json, sys
data = json.load(sys.stdin)
frames = data["scrollFrameMs"]
long = data["scrollLongFrames"]
print("run p50={p50} p95={p95} p99={p99} max={max} fps={fps} "
      "over50={o50}/{total} over100={o100} wheels={wheels} idle_p95={idle}".format(
          p50=frames["p50"], p95=frames["p95"], p99=frames["p99"], max=frames["max"],
          fps=data["effectiveFps"], o50=long["over50ms"], total=long["ofFrames"],
          o100=long["over100ms"], wheels=data["wheelEventsReceived"],
          idle=data["idleFrameMs"]["p95"]))
'
done

#!/usr/bin/env bash
# Runs the evaluation ON THE PHONE (real ML Kit OCR, real latency, real fallback calls), then runs the harness.
# Needs: phone on USB with debugging allowed, and the backend started with the eval endpoints enabled:
#   (cd backend && AMRIT_EVAL_DIR=../data/test_photos AMRIT_EVAL_OUT=../eval/out/device .venv/Scripts/python -m uvicorn app:app --port 8000)
# Usage: bash eval/run_on_device.sh [api|offline]
set -euo pipefail
export MSYS_NO_PATHCONV=1  # Git Bash on Windows would otherwise rewrite device paths
cd "$(dirname "$0")/.."
source env.sh
MODE="${1:-api}"
PKG=org.amrit.app
OUT=eval/out/device
adb get-state >/dev/null
curl -sf localhost:8000/eval/labels.csv >/dev/null || { echo "backend eval endpoints not enabled (see header)"; exit 1; }
./gradlew -q :app:installRelease
rm -rf $OUT && mkdir -p $OUT
adb reverse tcp:8000 tcp:8000 >/dev/null   # phone reaches the laptop backend over USB, no Wi-Fi needed
adb shell am force-stop $PKG
adb shell am start -n $PKG/.ui.MainActivity --es backend http://127.0.0.1:8000 --es eval "$MODE" >/dev/null
echo "running on the phone (the Stats screen shows progress)..."
for i in $(seq 1 600); do [ -f $OUT/results.json ] && break; sleep 3; done; [ -f $OUT/results.json ] || { echo "timed out waiting for results"; exit 1; }
sleep 1
./gradlew -q :eval:run --args="--source $OUT --write-assets"

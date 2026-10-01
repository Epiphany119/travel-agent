#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="${ACCEPTANCE_OUT_DIR:-$ROOT_DIR/target/acceptance/$STAMP}"
mkdir -p "$OUT_DIR"

echo "[1/3] Backend regression, including A2A/MCP contract tests"
(cd "$ROOT_DIR" && mvn -B test 2>&1 | tee "$OUT_DIR/backend.log")

echo "[2/3] Frontend typecheck and production build"
if [[ ! -d "$ROOT_DIR/frontend/node_modules" ]]; then
  echo "frontend/node_modules 不存在，请先在 frontend 执行 npm ci" >&2
  exit 2
fi
(cd "$ROOT_DIR/frontend" && npm run build 2>&1 | tee "$OUT_DIR/frontend.log")

echo "[3/3] Collecting immutable acceptance evidence"
ROOT_DIR="$ROOT_DIR" OUT_DIR="$OUT_DIR" python3 - <<'PY'
from pathlib import Path
import os, subprocess, xml.etree.ElementTree as ET

root = Path(os.environ["ROOT_DIR"])
out = Path(os.environ["OUT_DIR"])
totals = [0, 0, 0, 0]
files = 0
for path in root.glob("**/target/surefire-reports/TEST-*.xml"):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    files += 1
    for index, key in enumerate(("tests", "failures", "errors", "skipped")):
        totals[index] += int(suite.attrib.get(key, 0))
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
report = out / "acceptance-summary.md"
report.write_text(
    "# Acceptance Loop\n\n"
    f"- UTC: `{out.name}`\n- Git baseline: `{commit}`\n"
    f"- Surefire reports: {files}\n- Tests: {totals[0]}\n"
    f"- Failures: {totals[1]}\n- Errors: {totals[2]}\n- Skipped: {totals[3]}\n"
    "- Frontend build: passed\n", encoding="utf-8")
print(report)
if totals[1] or totals[2] or totals[3]:
    raise SystemExit("acceptance gate failed")
PY

echo "Acceptance Loop passed. Evidence: $OUT_DIR/acceptance-summary.md"

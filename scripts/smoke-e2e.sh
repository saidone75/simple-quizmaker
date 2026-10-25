#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec bb scripts/smoke-e2e.clj "$@"

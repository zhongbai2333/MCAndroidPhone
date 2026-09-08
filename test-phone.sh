#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ "$#" -eq 0 ]; then set -- pattern; fi
if [ -x .venv/bin/python3 ]; then
  exec .venv/bin/python3 scripts/dev.py "$@"
fi
exec python3 scripts/dev.py "$@"

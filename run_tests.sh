#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf test-bin
mkdir -p test-bin
mapfile -d '' files < <(find src/com/mina/yuedu/model src/com/mina/yuedu/core src/com/mina/yuedu/network tests -name '*.java' -print0 2>/dev/null || true)
if [ ${#files[@]} -eq 0 ]; then echo 'No test sources found' >&2; exit 1; fi
javac -source 1.8 -target 1.8 -d test-bin "${files[@]}"
java -cp test-bin TestRunner

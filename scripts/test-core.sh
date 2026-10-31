#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
java com.sun.tools.javac.Main -d "$OUT" app/src/main/java/com/myfamcall/app/CallPolicy.java app/src/main/java/com/myfamcall/app/IceGeneration.java core/PolicyTest.java
java -cp "$OUT" PolicyTest

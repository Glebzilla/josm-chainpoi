#!/bin/sh
# Build chainpoi without Gradle: javac against josm-latest.jar, then jar.
#   ./build.sh
# JOSM_JAR=/path/to/josm-latest.jar ./build.sh   (if the jar is elsewhere)
set -e

here=$(cd "$(dirname "$0")" && pwd)
JOSM_JAR="${JOSM_JAR:-$HOME/Downloads/josm-latest.jar}"
out="$here/build"
target="$here/chainpoi.jar"

if [ ! -f "$JOSM_JAR" ]; then
    echo "JOSM jar not found: $JOSM_JAR"
    echo "Put josm-latest.jar there or run: JOSM_JAR=/path/to/josm-latest.jar ./build.sh"
    exit 1
fi
if ! command -v javac >/dev/null 2>&1; then
    echo "javac not found. Install JDK 17 or newer."
    exit 1
fi

rm -rf "$out"
mkdir -p "$out"
cd "$here"
sources=$(mktemp)
trap 'rm -f "$sources"' EXIT
find src -name '*.java' > "$sources"

javac --release 17 -encoding UTF-8 -cp "$JOSM_JAR" -d "$out" @"$sources"

if [ -d "$here/resources" ]; then
    cp -R "$here/resources/." "$out/"
fi

jar --create --file "$target" --manifest "$here/manifest.txt" -C "$out" .
echo "built: $target"

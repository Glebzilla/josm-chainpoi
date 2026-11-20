#!/bin/sh
# chainpoi installer for Linux and macOS.
# Close JOSM first, then run:  sh install.sh
set -e

here=$(cd "$(dirname "$0")" && pwd)
jar="$here/chainpoi.jar"

if [ "$(uname -s)" = "Darwin" ]; then
    conf="$HOME/Library/Preferences/JOSM"
else
    if [ -d "$HOME/.josm" ]; then
        conf="$HOME/.josm"
    else
        conf="${XDG_CONFIG_HOME:-$HOME/.config}/JOSM"
    fi
fi

plugins="$conf/plugins"
prefs="$conf/preferences.xml"

if [ ! -f "$jar" ]; then
    echo "chainpoi.jar not found next to this script"
    exit 1
fi
if [ ! -d "$plugins" ]; then
    echo "JOSM plugins directory not found: $plugins"
    echo "Start JOSM once (it creates the directory), close it, then run this again."
    exit 1
fi
if [ ! -f "$prefs" ]; then
    echo "JOSM preferences not found: $prefs"
    exit 1
fi

if pgrep -x josm >/dev/null 2>&1 || pgrep -f 'josm.*\.jar' >/dev/null 2>&1; then
    echo "JOSM is running. Close it and run again."
    exit 1
fi

cp "$prefs" "$prefs.bak-$(date +%Y%m%d-%H%M%S)"
cp "$jar" "$plugins/chainpoi.jar"
echo "copied: $plugins/chainpoi.jar"

if grep -q "value='chainpoi'" "$prefs"; then
    echo "already enabled in preferences"
else
    tmp="$prefs.tmp.$$"
    awk -v entry="    <entry value='chainpoi'/>" \
        "{ print; if (\$0 ~ /<list key='plugins'>/) print entry }" "$prefs" > "$tmp"
    mv "$tmp" "$prefs"
    echo "enabled in preferences"
fi

echo "done - start JOSM"

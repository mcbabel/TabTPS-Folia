#!/bin/bash
set -u

cd "$(dirname "$0")"
root=$(pwd)
mkdir -p logs work

plugin=$(ls -t ../folia/build/libs/tabtps-folia-*.jar 2>/dev/null | grep -v -e unshaded -e sources | head -1)
if [ -z "$plugin" ]; then
  echo "No plugin jar found, run ./gradlew build first"
  exit 1
fi
plugin=$(realpath "$plugin")

java_for() {
  local var="JAVA$1"
  if [ -n "${!var:-}" ]; then
    echo "${!var}"
    return
  fi
  for candidate in "$HOME"/.gradle/jdks/*-"$1"-*/bin/java /usr/lib/jvm/java-"$1"-*/bin/java; do
    if [ -x "$candidate" ]; then
      echo "$candidate"
      return
    fi
  done
}

fetch() {
  local source=$1 sha=$2 java=$3 target=$4
  if [[ $source == buildtools:* ]]; then
    local rev=${source#buildtools:}
    local bt="$root/work/buildtools-$rev"
    mkdir -p "$bt"
    curl -sSL -o "$bt/BuildTools.jar" https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar || return 1
    (cd "$bt" && "$java" -Xmx2G -jar BuildTools.jar --rev "$rev" --compile spigot --output-dir out > buildtools.log 2>&1) || { echo "BuildTools failed, see $bt/buildtools.log"; return 1; }
    mv "$bt"/out/spigot-*.jar "$target"
    rm -rf "$bt"
    return
  fi
  curl -sSL -o "$target" "$source" || return 1
  if [ "$sha" != "-" ]; then
    echo "$sha  $target" | sha256sum -c --quiet || { echo "Checksum mismatch for $target"; return 1; }
  fi
}

run() {
  local name=$1 version=$2 source=$3 sha=$4
  local java
  java=$(java_for "$version")
  if [ -z "$java" ]; then
    printf '%-14s SKIPPED (no Java %s found, set JAVA%s)\n' "$name" "$version" "$version"
    return
  fi
  local dir="$root/work/$name"
  rm -rf "$dir"
  mkdir -p "$dir/plugins"
  cd "$dir" || return
  if ! fetch "$source" "$sha" "$java" server.jar; then
    printf '%-14s DOWNLOAD FAILED\n' "$name"
    cd "$root" && rm -rf "$dir"
    return
  fi
  cp "$plugin" plugins/
  echo eula=true > eula.txt
  printf 'online-mode=false\nserver-port=%s\nview-distance=4\nsimulation-distance=4\nlevel-type=minecraft\\:flat\n' "${PORT:-25599}" > server.properties
  : > cmd.txt
  (tail -n +1 -f "$dir/cmd.txt" | "$java" -Xms1G -Xmx2G -jar server.jar --nogui > console.log 2>&1) &
  timeout 300 bash -c 'until grep -qE "Done \(|Failed to start|Encountered an unexpected exception" console.log; do sleep 1; done'
  for command in "tickinfo" "tabtps reload" "tickinfo" "memory" "tabtps about"; do
    echo "$command" >> cmd.txt
    sleep 2
  done
  echo stop >> cmd.txt
  timeout 90 bash -c 'until grep -q "Disabling TabTPS" console.log; do sleep 1; done'
  sleep 5
  pkill -f "[t]ail -n \+1 -f $dir/cmd.txt" 2>/dev/null
  sleep 2
  cp console.log "$root/logs/$name.log"
  local mode tps exceptions
  if grep -q "Successfully initialized Folia region tick data accessor" console.log; then
    mode="per region"
  elif grep -q "is not a regionized server" console.log; then
    mode="global"
  else
    mode="NOT ENABLED"
  fi
  tps=$(grep -m1 -oE "TPS: [0-9,. ()a-z]+" console.log | head -1)
  exceptions=$(grep -c "Exception" console.log)
  printf '%-14s %-11s exceptions=%-3s %s\n' "$name" "$mode" "$exceptions" "$tps"
  cd "$root" && rm -rf "$dir"
}

if [ $# -eq 0 ]; then
  echo "Usage: $0 all | <name>...   (names from servers.txt)"
  awk '{print "  " $1}' servers.txt
  exit 1
fi

while read -r name version source sha; do
  [ -z "$name" ] && continue
  if [ "$1" = "all" ] || printf '%s\n' "$@" | grep -qx "$name"; then
    run "$name" "$version" "$source" "$sha"
  fi
done < servers.txt

rmdir work 2>/dev/null
echo "Logs: $root/logs"

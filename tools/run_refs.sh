#!/usr/bin/env bash
# Run reference routes from tools/areas.json through the app's own planner (AreaRoutesTest)
# on this machine instead of in CI: a few minutes instead of a CI round.
#
#   tools/run_refs.sh [-b] [-k] [name-regex]
#     name-regex  only the reference routes whose name matches (Python regex); default all
#     -b          packs from the route-pack-beta pre-release instead of route-pack
#     -k          keep going with the cached packs (no catalog check, works offline)
#
# Packs (public release downloads) are cached in ~/.cache/noaa-route/<release>/ and fetched
# again when the release's catalog changes. The Android SDK (platform 35) and Gradle 8.11.1
# are installed under ~/.cache/noaa-route on first use. Run tools/cloud-setup.sh first for a JDK.
# Output: one PASS?/REFUSED line per route as in CI; the full log is printed at the end.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
tag=route-pack
offline=0
while getopts bk o; do
  case $o in
    b) tag=route-pack-beta ;;
    k) offline=1 ;;
    *) sed -n '2,15p' "$0"; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
pattern="${1:-.}"
cache="$HOME/.cache/noaa-route"
packs="$cache/$tag"
base="https://github.com/jnyer27/noaa-osmand-charts/releases/download/$tag"
mkdir -p "$packs"

# Catalog: a new one means new packs, so the cached ones are dropped
if [ $offline = 0 ]; then
  curl -fsSL "$base/catalog.json" -o "$packs/catalog.new"
  if ! cmp -s "$packs/catalog.new" "$packs/catalog.json" 2>/dev/null; then
    rm -f "$packs"/*.bin
    mv "$packs/catalog.new" "$packs/catalog.json"
  else
    rm -f "$packs/catalog.new"
  fi
fi

# Only the matching routes, and the packs they need
areas="$cache/areas-run.json"
ids="$(python3 - "$repo/tools/areas.json" "$areas" "$pattern" <<'PY'
import json, re, sys
src, out, pat = sys.argv[1], sys.argv[2], re.compile(sys.argv[3])
d = json.load(open(src))
need = set()
for a in d["areas"]:
    a["tests"] = [t for t in a.get("tests", []) if pat.search(t["name"])]
    if a["tests"]:
        need.add(a["id"])
d["tests"] = [t for t in d.get("tests", []) if pat.search(t["name"])]
for t in d["tests"]:
    need.update(t["areas"])
json.dump(d, open(out, "w"), indent=1)
n = len(d["tests"]) + sum(len(a["tests"]) for a in d["areas"])
print(f"{n} reference routes", file=sys.stderr)
print(" ".join(sorted(need)))
PY
)"
[ -n "$ids" ] || { echo "No reference route matches '$pattern'"; exit 1; }
for id in $ids; do
  [ -s "$packs/$id.bin" ] && continue
  [ $offline = 1 ] && { echo "No cached $id.bin (-k)"; exit 1; }
  echo "Downloading $id.bin ($tag)"
  curl -fsSL "$base/$id.bin" -o "$packs/$id.bin.part" && mv "$packs/$id.bin.part" "$packs/$id.bin"
done

# Android SDK: the app module needs platform 35 even for JVM unit tests
sdk="${ANDROID_HOME:-$cache/android-sdk}"
if [ ! -d "$sdk/platforms/android-35" ]; then
  sdk="$cache/android-sdk"
  if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "Installing Android command-line tools"
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$cache/cmdline-tools.zip"
    rm -rf "$sdk/cmdline-tools"
    mkdir -p "$sdk/cmdline-tools"
    unzip -q "$cache/cmdline-tools.zip" -d "$sdk/cmdline-tools"
    mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
  fi
  yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --licenses >/dev/null || true
  "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" "platforms;android-35" "build-tools;35.0.0" >/dev/null
fi
export ANDROID_HOME="$sdk"
unset ANDROID_SDK_ROOT

# Gradle 8.11.1, the version CI uses (the repo has no wrapper)
gradle="$cache/gradle-8.11.1/bin/gradle"
if [ ! -x "$gradle" ]; then
  echo "Installing Gradle 8.11.1"
  curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o "$cache/gradle.zip"
  unzip -q -o "$cache/gradle.zip" -d "$cache"
fi

log="$cache/routes.log"
rc=0
PACK_DIR="$packs" AREAS_JSON="$areas" "$gradle" -p "$repo/android" --no-daemon \
  testReleaseUnitTest --tests '*AreaRoutesTest' -i > "$log" 2>&1 || rc=$?
grep -E 'PASS\?|REFUSED' "$log" | sed 's/^ *//' || true
if [ $rc -ne 0 ]; then
  if grep -q 'Reference route failures:' "$log"; then
    sed -n '/Reference route failures:/,/at org\.junit\./p' "$log"
  else
    grep -v 'PROGRESS\|POINTS' "$log" | tail -40
  fi
fi
echo "Full log (with POINTS and PROGRESS lines): $log"
exit $rc

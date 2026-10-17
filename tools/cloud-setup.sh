#!/usr/bin/env bash
# Set up a Debian/Ubuntu machine (a Claude Code cloud session, a desktop, a CI runner) for
# this repo: GDAL + numpy/scipy for noaa-route.py and the pack and vector tools, JDK 17 for
# the app's tests. The Android SDK and Gradle are installed on first use by tools/run_refs.sh.
# Safe to run again: installs only what is missing.
set -euo pipefail
need=()
command -v ogrinfo >/dev/null || need+=(gdal-bin)
python3 -c 'from osgeo import gdal' 2>/dev/null || need+=(python3-gdal)
python3 -c 'import numpy' 2>/dev/null || need+=(python3-numpy)
python3 -c 'import scipy' 2>/dev/null || need+=(python3-scipy)
command -v javac >/dev/null || need+=(openjdk-17-jdk-headless)
command -v unzip >/dev/null || need+=(unzip)
command -v curl >/dev/null || need+=(curl)
if [ ${#need[@]} -gt 0 ]; then
  sudo=""
  [ "$(id -u)" -ne 0 ] && sudo=sudo
  echo "cloud-setup: installing ${need[*]}"
  $sudo apt-get update -qq
  DEBIAN_FRONTEND=noninteractive $sudo apt-get install -y -qq --no-install-recommends "${need[@]}" >/dev/null
fi
echo "cloud-setup: $(ogrinfo --version | cut -d, -f1), javac $(javac -version 2>&1 | cut -d' ' -f2), numpy $(python3 -c 'import numpy; print(numpy.__version__)')"

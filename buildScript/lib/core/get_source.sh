#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
PATCHES="$(pwd)/buildScript/lib/core/patches"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout -f "$COMMIT_SING_BOX"
# STRIDER fixes on top of the core (see buildScript/lib/core/patches)
for p in "$PATCHES"/sing-box-*.patch; do
  git apply "$p"
  echo "patched: $(basename "$p")"
done
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd

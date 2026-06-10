#!/bin/bash

[ -f ./env_java.sh ] && source ./env_java.sh
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# Verify gVisor workaround.
if ! go run -tags=with_gvisor ../../sing-box/cmd/internal/gvisor_workaround_verify/main.go; then
  echo "ERROR: gVisor workaround verification failed"
  exit 1
fi

export GOBIND=gobind
gomobile bind -v -androidapi 23 -trimpath -ldflags='-s -w' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_awg,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"

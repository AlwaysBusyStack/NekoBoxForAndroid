#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.25.10}"

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12

gomobile init

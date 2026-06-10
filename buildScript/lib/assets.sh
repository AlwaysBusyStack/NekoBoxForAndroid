#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  curl --silent "https://api.github.com/repos/$1/releases/latest" | # Get latest release from GitHub api
    grep '"tag_name":' |                                            # Get tag line
    sed -E 's/.*"([^"]+)".*/\1/'                                    # Pluck JSON value
}

get_latest_release_json() {
  curl --silent "https://api.github.com/repos/$1/releases/latest"
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
curl -fLSsO https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db
xz -9 geosite.db

####
THRONE_RULESET_SHA=`curl --silent "https://api.github.com/repos/throneproj/routeprofiles/commits/rule-set" | grep '"sha":' | head -n1 | sed -E 's/.*"([^"]+)".*/\1/'`
THRONE_RULESET_SHA_SHORT=`printf '%s' "$THRONE_RULESET_SHA" | cut -c1-7`
echo THRONE_RULESET_SHA=$THRONE_RULESET_SHA_SHORT
echo -n $THRONE_RULESET_SHA_SHORT > throne-ruleset.version.txt
curl -fLSso throne-ruleset-srslist.h https://raw.githubusercontent.com/throneproj/routeprofiles/refs/heads/rule-set/srslist.h

####
ITDOG_RELEASE_JSON=`get_latest_release_json "itdoginfo/allow-domains"`
ITDOG_RULESET_VERSION=`printf '%s' "$ITDOG_RELEASE_JSON" | jq -r '.tag_name'`
echo ITDOG_RULESET_VERSION=$ITDOG_RULESET_VERSION
echo -n $ITDOG_RULESET_VERSION > itdog-ruleset.version.txt
printf '%s' "$ITDOG_RELEASE_JSON" | jq -c '
  reduce (
    .assets[]
      | if (.name | endswith(".srs") and (endswith("_domain.srs") | not)) then
        {
          alias: ("itdog-" + (.name | sub("\\.srs$"; ""))),
          key: "rsip",
          url: .browser_download_url
        }
      else empty end
  ) as $item
  ({};
    .[$item.alias] = ((.[$item.alias] // {}) + {($item.key): $item.url})
  )
' > itdog-ruleset.json
#!/usr/bin/env bash
set -euo pipefail
source /home/gjessing/android-sdk/env.sh
source /home/gjessing/.config/glance/keystore.env
source scripts/release-common.sh
npm run build
stamp_web
npm run sync --prefix apps/android
apps/android/android/gradlew -p apps/android/android assembleRelease
mkdir -p releases
cp apps/android/android/app/build/outputs/apk/release/app-release.apk "releases/glance-$version.apk"
printf 'Built %s\n' "$PWD/releases/glance-$version.apk"
# The APK's own web bundle, published too so the manifest always names one; installs of this
# APK already show it and are not offered it again.
zip_web

# The homepage link and the app's update check use fixed names. Homepage only lists static
# files at startup, so replacing these is served immediately while a new filename would 404
# until a restart.
if [ -d "$downloads" ]; then
  publish_file "releases/glance-$version.apk" glance-latest.apk
  publish_file "$web_zip" glance-web.zip
  write_manifest "releases/glance-$version.apk"
  # Cloudflare overrides browser caching of .apk to hours, so a fixed URL can hand out an old
  # release; the version query makes each release a new URL for browsers and the edge alike.
  services=/home/gjessing/docker/infrastructure/homepage/config/services.yaml
  sed -i -E "s#(href: https://home\.gjessing\.io/downloads/glance-latest\.apk)(\?v=[0-9]+)?\$#\1?v=$code#" "$services"
  printf 'Published %s (%s) as %s\n' "$version" "$code" "$downloads/glance-latest.apk"
fi

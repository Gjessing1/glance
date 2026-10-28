#!/usr/bin/env bash
# Publishes the web app alone. Installed copies of the current APK download it from the update
# banner and reload, no reinstall. Native changes (widgets, plugin methods, layouts) need
# `npm run android:release` instead.
set -euo pipefail
source scripts/release-common.sh
apk="releases/glance-$version.apk"
[ -d "$downloads" ] || { echo "No $downloads to publish to" >&2; exit 1; }
published="$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1])).get("versionCode", ""))' "$downloads/glance-version.json")"
if [ "$published" != "$code" ] || [ ! -f "$apk" ]; then
  echo "build.gradle is at $version ($code) but the published APK is versionCode $published: run npm run android:release" >&2
  exit 1
fi
# Capacitor sync rewrites the assets and config.xml; anything else newer than the APK is native.
changed="$(find apps/android/android/app/src/main "$gradle" -type f -newer "$apk" \
  ! -path '*/src/main/assets/*' ! -path '*/res/xml/config.xml' | sort)"
if [ -n "$changed" ]; then
  printf 'Native code changed since %s, so it needs an APK release (bump the version, npm run android:release):\n%s\n' "$apk" "$changed" >&2
  exit 1
fi
npm run build
stamp_web
zip_web
publish_file "$web_zip" glance-web.zip
write_manifest
printf 'Published web bundle %s for %s (%s)\n' "$web_id" "$version" "$code"

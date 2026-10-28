# Shared by the APK release (build-android-release.sh) and the web-only release (android-ota.sh).
# Homepage serves these fixed names; Caddy exempts exactly them from SSO so the app can reach them.
downloads=/home/gjessing/docker/homepage/downloads
public=https://home.gjessing.io/downloads
gradle=apps/android/android/app/build.gradle
version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)"[[:space:]]*$/\1/p' "$gradle")"
code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+)[[:space:]]*$/\1/p' "$gradle")"

# Gives the built web app an id from its contents, so an unchanged UI never counts as an update.
# The app reads it from web-build.json, in the APK's assets or in a downloaded bundle.
stamp_web() {
  rm -f apps/web/dist/web-build.json
  web_id="$(cd apps/web/dist && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -c1-16)"
  printf '{"id":"%s"}\n' "$web_id" > apps/web/dist/web-build.json
}

zip_web() {
  web_zip="releases/glance-web-$web_id.zip"
  mkdir -p releases
  python3 -I -c 'import pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as out:
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        out.write(path, path.relative_to(root).as_posix())' apps/web/dist "$web_zip"
}

# Replaces a published file atomically, so a client never reads half of it.
publish_file() {
  cp "$1" "$downloads/.$2.tmp"
  mv "$downloads/.$2.tmp" "$downloads/$2"
}

# Rewrites glance-version.json last, so it never names a file that is not there yet. With an APK
# path it describes that APK; otherwise it keeps the published APK fields. The web bundle targets
# versionCode $code only: the bridge drops it when another APK is installed.
write_manifest() {
  python3 -I -c 'import hashlib, json, os, sys
manifest, apk, apk_url, zip_path, zip_url, web_id, code, version = sys.argv[1:]
sha = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
data = json.load(open(manifest)) if os.path.exists(manifest) else {}
if apk:
    data.update(versionCode=int(code), versionName=version, sha256=sha(apk), bytes=os.path.getsize(apk), apkUrl=apk_url)
data["web"] = {"id": web_id, "nativeCode": int(code), "sha256": sha(zip_path), "bytes": os.path.getsize(zip_path), "url": zip_url}
tmp = os.path.join(os.path.dirname(manifest), ".glance-version.json.tmp")
with open(tmp, "w") as out:
    out.write(json.dumps(data, separators=(",", ":")) + "\n")
os.replace(tmp, manifest)' "$downloads/glance-version.json" "${1:-}" "$public/glance-latest.apk?v=$code" \
    "$web_zip" "$public/glance-web.zip?v=$web_id" "$web_id" "$code" "$version"
}

#!/usr/bin/env bash
set -euo pipefail
if [[ -z "${ANDROID_HOME:-}" && -f "$HOME/android-sdk/env.sh" ]]; then
  source "$HOME/android-sdk/env.sh"
fi
apps/android/android/gradlew -p apps/android/android testDebugUnitTest

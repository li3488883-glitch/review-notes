#!/usr/bin/env bash
# Packs everything an offline build needs (Gradle distribution, dependency cache, Android platform and
# build tools) into toolchain.tgz, so the app can be built and its screens rendered on a machine
# that cannot reach the Google / Maven repositories.
set -euxo pipefail
cd app
gradle :mobile:assembleRelease :mobile:assembleDebug :mobile:testReleaseUnitTest :mobile:recordPaparazziDebug --no-daemon --stacktrace || true
ls -la mobile/build/outputs/apk/*/ || true
find mobile/src/test/snapshots -name '*.png' 2>/dev/null | head || true
GH=$(dirname "$(dirname "$(readlink -f "$(which gradle)")")")
echo "gradle home: $GH"
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
ls "$SDK" "$SDK/build-tools" "$SDK/platforms"
cd "$RUNNER_TEMP"
mkdir -p tc && cd tc
cp -a "$GH" gradle-dist
mkdir -p gradle-user && cp -a ~/.gradle/caches/modules-2 gradle-user/ 
mkdir -p sdk/platforms sdk/build-tools
cp -a "$SDK/platforms/android-35" sdk/platforms/
for b in 34.0.0 35.0.0 35.0.1; do [ -d "$SDK/build-tools/$b" ] && cp -a "$SDK/build-tools/$b" sdk/build-tools/ || true; done
cp -a "$SDK/licenses" sdk/ || true
[ -d "$SDK/platform-tools" ] && cp -a "$SDK/platform-tools" sdk/ || true
du -sh *
tar czf "$GITHUB_WORKSPACE/toolchain.tgz" .
cd "$GITHUB_WORKSPACE"
ls -la toolchain.tgz
split -b 1500m -d toolchain.tgz toolchain.tgz.part
gh release view toolchain -R "$GITHUB_REPOSITORY" >/dev/null 2>&1 || gh release create toolchain -R "$GITHUB_REPOSITORY" --title "构建工具包" --notes "离线打包用的 Gradle 与安卓 SDK 缓存，给开发用。" --prerelease
gh release upload toolchain toolchain.tgz.part* -R "$GITHUB_REPOSITORY" --clobber
if [ -d app/mobile/src/test/snapshots ]; then tar czf shots-paparazzi.tgz -C app/mobile/src/test snapshots; gh release upload toolchain shots-paparazzi.tgz -R "$GITHUB_REPOSITORY" --clobber; fi

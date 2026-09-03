#!/bin/zsh
# Test release build script for Chronicle
set -e

print "========================================="
print "Chronicle Release Build Test"
print "========================================="

RED='%F{1}'
GREEN='%F{2}'
YELLOW='%B%F{3}'
NC='%f%b'

print "${YELLOW}Step 1: Clean build${NC}"
./gradlew clean

print "${YELLOW}Step 2: Building release APK...${NC}"
./gradlew assembleRelease

# There is no release signing config (signing is owner-only), so R8 emits
# app-release-unsigned.apk. The old hardcoded app-release.apk meant this script
# reported failure after a fully successful build — a false negative that made a
# real R8 breakage indistinguishable from a healthy one.
APK_PATH=$(print -l app/build/outputs/apk/release/*.apk(N) | head -1)
if [[ -z ${APK_PATH} || ! -f ${APK_PATH} ]]; then
  print "${RED}❌ No APK found in app/build/outputs/apk/release/${NC}"
  exit 1
fi

APK_SIZE=$(du -h "${APK_PATH}" | cut -f1)
print "${GREEN}✅ Release build succeeded${NC}"
print "${GREEN}APK: ${APK_PATH:t} (${APK_SIZE})${NC}"

print "${YELLOW}Step 2b: Verifying reflection-dependent classes survived R8...${NC}"

# Check the DEX, not mapping.txt. A class R8 keeps *unrenamed* gets no top-level
# mapping entry at all, so grepping mapping.txt silently passes for exactly the
# classes we most want to assert. The DEX is what ships.
DEXDUMP=$(print -l ${HOME}/Library/Android/sdk/build-tools/*/dexdump(N) | sort | tail -1)
if [[ -z ${DEXDUMP} ]]; then
  print "${RED}❌ dexdump not found in Android SDK build-tools; cannot verify R8 output${NC}"
  exit 1
fi

DEXDIR=$(mktemp -d)
trap "rm -rf ${DEXDIR}" EXIT
unzip -q -o "${APK_PATH}" 'classes*.dex' -d "${DEXDIR}" || {
  print "${RED}❌ could not extract dex from ${APK_PATH}${NC}"
  exit 1
}
DESCRIPTORS="${DEXDIR}/descriptors.txt"
LC_ALL=C ${DEXDUMP} ${DEXDIR}/classes*.dex 2>/dev/null \
  | LC_ALL=C grep "Class descriptor" \
  | LC_ALL=C sed -e "s/.*'L//" -e "s/;'.*//" -e 's|/|.|g' > "${DESCRIPTORS}"

if [[ ! -s ${DESCRIPTORS} ]]; then
  print "${RED}❌ could not read class list from dex${NC}"
  exit 1
fi
print "  (${$(wc -l < ${DESCRIPTORS})// /} classes in dex)"

# Anything reached by reflection rather than a direct call: Room resolves the
# @Database/@Dao/@Entity types by name, Retrofit reads the service interfaces,
# Dagger instantiates the generated components. If R8 strips one the app builds
# fine and dies at runtime, so assert it here rather than on a device.
typeset -a REQUIRED
REQUIRED=(
  "io.github.mattpvaughn.chronicle.data.local.BookDatabase"
  "io.github.mattpvaughn.chronicle.data.local.TrackDatabase"
  "io.github.mattpvaughn.chronicle.data.local.ChapterDatabase"
  "io.github.mattpvaughn.chronicle.data.local.CollectionsDatabase"
  "io.github.mattpvaughn.chronicle.data.local.BookDao"
  "io.github.mattpvaughn.chronicle.data.local.TrackDao"
  "io.github.mattpvaughn.chronicle.data.model.Audiobook"
  "io.github.mattpvaughn.chronicle.data.model.MediaItemTrack"
  "io.github.mattpvaughn.chronicle.data.model.Chapter"
  "io.github.mattpvaughn.chronicle.data.sources.plex.PlexMediaService"
  "io.github.mattpvaughn.chronicle.data.sources.plex.PlexLoginService"
  "io.github.mattpvaughn.chronicle.injection.components.DaggerAppComponent"
)

MISSING=0
for cls in ${REQUIRED[@]}; do
  if ! grep -qxF "${cls}" "${DESCRIPTORS}"; then
    print "${RED}❌ stripped or renamed by R8: ${cls}${NC}"
    MISSING=$((MISSING + 1))
  fi
done

# Every Moshi model must survive under its own name: these parse live Plex JSON,
# and a stripped or renamed model fails at parse time, not build time.
#
# Only classes actually carrying @JsonClass count. This used to take *every* `data class` in any
# file containing the annotation, which is a different claim: SettingsBackup.kt holds one annotated
# DTO plus four `internal` sealed-interface members that never touch JSON, so R8 rightly inlined
# them and the check reported four phantom failures (cu-136). Widening proguard-rules.pro to
# silence that would have exempted correctly-optimised code from R8 — the opposite of cu-45's rule
# that keeps stay narrow. A nested class also has a `Parent$Child` descriptor, so the flat
# `PKG.Child` name it looked for could not have matched even if the class had survived.
for f in app/src/main/java/**/*.kt(N); do
  grep -q "@JsonClass" "${f}" || continue
  PKG=$(grep -m1 '^package ' "${f}" | cut -d' ' -f2)
  # The `data class` on the line *after* an @JsonClass annotation, which is where Moshi codegen
  # requires it. -A1 keeps the pairing rather than trusting file-level co-occurrence.
  ANNOTATED=$(grep -A1 '@JsonClass' "${f}" | grep -oE 'data class [A-Za-z0-9_]+' | cut -d' ' -f3)
  for cls in ${(f)ANNOTATED}; do
    if ! grep -qxF "${PKG}.${cls}" "${DESCRIPTORS}"; then
      print "${RED}❌ Moshi model missing from dex: ${PKG}.${cls} (${f:t})${NC}"
      MISSING=$((MISSING + 1))
    fi
  done
done

if (( MISSING > 0 )); then
  print "${RED}❌ ${MISSING} reflection-dependent class(es) did not survive R8${NC}"
  print "${RED}   Add keep rules in app/proguard-rules.pro before shipping.${NC}"
  exit 1
fi
print "${GREEN}✅ All reflection-dependent classes survived R8${NC}"

if ! adb devices | grep -q "device$"; then
  print "${YELLOW}⚠️  No device connected. Skipping installation.${NC}"
  print "${YELLOW}Manual testing required.${NC}"
  exit 0
fi

print "${YELLOW}Step 3: Installing release APK...${NC}"
adb install -r "${APK_PATH}" || {
  print "${RED}❌ Installation FAILED${NC}"
  exit 1
}

print "${GREEN}✅ APK installed successfully${NC}"
print "\n========================================="
print "Manual Testing Checklist:"
print "========================================="
print "1. [ ] App launches (no crash on start)"
print "2. [ ] Login works (OAuth flow)"
print "3. [ ] Library loads (books display)"
print "4. [ ] Book details open"
print "5. [ ] Playback works"
print "6. [ ] Downloads work"
print "7. [ ] Settings accessible"
print "8. [ ] No crashes in logcat"
print "\nMonitor logcat with:"
print "  adb logcat | grep Chronicle"


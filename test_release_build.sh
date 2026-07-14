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

APK_PATH="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f ${APK_PATH} ]]; then
  print "${RED}❌ APK not found at ${APK_PATH}${NC}"
  exit 1
fi

APK_SIZE=$(du -h "${APK_PATH}" | cut -f1)
print "${GREEN}✅ Release build succeeded${NC}"
print "${GREEN}APK size: ${APK_SIZE}${NC}"

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


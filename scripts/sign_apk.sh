#!/usr/bin/env bash

set -euo pipefail

input=${1:?input APK or AAB is required}
output=${2:?output path is required}

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
: "${ANDROID_KEYSTORE_PATH:?ANDROID_KEYSTORE_PATH must be set}"
: "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD must be set}"
: "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS must be set}"
: "${ANDROID_KEY_PASSWORD:?ANDROID_KEY_PASSWORD must be set}"

if [[ "$input" == *.apk ]]; then
    zipalign=$(find "$ANDROID_HOME/build-tools" -name zipalign -type f -print | sort -V | tail -n 1)
    apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f -print | sort -V | tail -n 1)
    test -n "$zipalign" && test -n "$apksigner"

    "$zipalign" -v -p 4 "$input" "$input.aligned"
    mv "$input.aligned" "$input"
    "$apksigner" sign \
        --ks "$ANDROID_KEYSTORE_PATH" \
        --ks-key-alias "$ANDROID_KEY_ALIAS" \
        --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
        --key-pass env:ANDROID_KEY_PASSWORD \
        "$input"
    "$apksigner" verify --verbose --print-certs "$input"
elif [[ "$input" == *.aab ]]; then
    jarsigner -verbose \
        -keystore "$ANDROID_KEYSTORE_PATH" \
        -storepass:env ANDROID_KEYSTORE_PASSWORD \
        -keypass:env ANDROID_KEY_PASSWORD \
        "$input" "$ANDROID_KEY_ALIAS"
else
    echo "Unsupported file type: $input" >&2
    exit 1
fi

mv "$input" "$output"

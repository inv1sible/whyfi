#!/bin/sh
set -u

# Always-on watcher (not a one-shot build). Polls the shared build_output
# volume for a request signal written by the Django backend and reports
# progress back through plain files on that same volume — no Docker socket,
# no RPC, no database access from this container. See
# backend/distribution/services.py's module docstring and MEMORY.md for why.

SIGNAL_DIR=/build-output
mkdir -p "$SIGNAL_DIR"

echo "whyfi android-builder watcher started, polling $SIGNAL_DIR/request.txt every 3s"

while true; do
  if [ -f "$SIGNAL_DIR/request.txt" ]; then
    BUILD_ID=$(cat "$SIGNAL_DIR/request.txt")
    rm -f "$SIGNAL_DIR/request.txt"
    echo "Build $BUILD_ID requested, starting Gradle..."

    echo "BUILDING" > "$SIGNAL_DIR/$BUILD_ID.state"

    if gradle assembleRelease --no-daemon --console=plain > "$SIGNAL_DIR/$BUILD_ID.log" 2>&1; then
      APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" | head -n1)
      if [ -n "$APK_PATH" ]; then
        cp "$APK_PATH" "$SIGNAL_DIR/$BUILD_ID.apk"
        echo "SUCCESS" > "$SIGNAL_DIR/$BUILD_ID.state"
        echo "Build $BUILD_ID succeeded -> $SIGNAL_DIR/$BUILD_ID.apk"
      else
        echo "no APK found in app/build/outputs/apk/release" >> "$SIGNAL_DIR/$BUILD_ID.log"
        echo "FAILED" > "$SIGNAL_DIR/$BUILD_ID.state"
        echo "Build $BUILD_ID failed: no APK produced"
      fi
    else
      echo "FAILED" > "$SIGNAL_DIR/$BUILD_ID.state"
      echo "Build $BUILD_ID failed, see $SIGNAL_DIR/$BUILD_ID.log"
    fi
  fi
  sleep 3
done

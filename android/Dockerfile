# Headless Android build: Gradle + Android SDK cmdline-tools, no Android
# Studio, no emulator. Produces a signed (if ANDROID_KEYSTORE_* env vars are
# set) or unsigned APK — see docs/android-setup.md.
FROM gradle:8.9-jdk17

ARG ANDROID_CMDLINE_TOOLS_VERSION=14742923
ARG ANDROID_COMPILE_SDK=34
ARG ANDROID_BUILD_TOOLS_VERSION=34.0.0

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -sSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
       -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" \
        "platforms;android-${ANDROID_COMPILE_SDK}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" > /dev/null

WORKDIR /workspace
# Only a fallback for running this image standalone — docker-compose
# bind-mounts ./android over /workspace so the watcher always builds current
# source instead of whatever was frozen in at image-build time.
COPY . .

# Run via `sh` rather than as an executable, and deliberately without relying
# on `chmod +x`: the bind-mounted copy carries the *host's* permissions, and
# this repo sits on a filesystem that hands out 0666 and doesn't preserve
# exec bits — `./docker-build.sh` fails there with permission denied.
ENTRYPOINT ["sh", "./docker-build.sh"]

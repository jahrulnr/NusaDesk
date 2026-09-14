SHELL := /bin/sh

GRADLEW ?= ./gradlew
ADB ?= adb
APK ?= app/build/outputs/apk/debug/app-debug.apk
GRADLE_FLAGS ?= --no-daemon

.PHONY: test lint build check devices push install clean

# Run the JVM/unit test suite.
test:
	$(GRADLEW) $(GRADLE_FLAGS) test

# Run Android lint with warnings treated as errors.
lint:
	$(GRADLEW) $(GRADLE_FLAGS) lintDebug

# Produce the debug APK after linting.
build: lint
	$(GRADLEW) $(GRADLE_FLAGS) assembleDebug

# Run the complete local quality gate.
check: test build

# List only adb targets that are online and ready to receive installs.
devices:
	@$(ADB) devices | awk 'NR > 1 && $$2 == "device" { print $$1 }'

# Build first, then install the debug APK to every online adb device.
# `install -r` preserves each device's app data and runtime payload.
push: build
	@set -eu; \
	devices="$$($(ADB) devices | awk 'NR > 1 && $$2 == "device" { print $$1 }')"; \
	if [ -z "$$devices" ]; then \
		echo "No online adb devices found; APK was built but not installed." >&2; \
		exit 1; \
	fi; \
	for serial in $$devices; do \
		echo "Installing $(APK) on $$serial"; \
		$(ADB) -s "$$serial" install -r "$(APK)"; \
	done

# Explicit alias for callers that prefer the Android terminology.
install: push

clean:
	$(GRADLEW) $(GRADLE_FLAGS) clean

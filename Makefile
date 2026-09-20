SHELL := /bin/sh

GRADLEW ?= ./gradlew
ADB ?= adb
NODE ?= node
APK ?= app/build/outputs/apk/debug/app-debug.apk
GRADLE_FLAGS ?= --no-daemon

# Local signing material for release builds (ADR-0040). The directory is
# gitignored: it holds the release keystore plus the .env with the keystore
# credentials and the certificate fingerprint. CI uses repository secrets
# instead; this is the local path.
SECRET_DIR ?= .secret
RELEASE_APK ?= app/build/outputs/apk/release/app-release.apk
SDK_DIR ?= $(shell sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)
APKSIGNER ?= $(shell find "$(SDK_DIR)/build-tools" -name apksigner 2>/dev/null | sort -V | tail -n 1)

.PHONY: test lint build check release devices push install clean

# Run the JVM/unit test suite (which includes the Robolectric cross-API launch
# guard, ADR-0022) and every pure-JavaScript terminal policy test. The glob is
# deliberate: a new policy test must not be able to sit outside the gate.
test:
	$(GRADLEW) $(GRADLE_FLAGS) test
	@for policy_test in app/src/test/js/*.test.js; do \
		echo "$(NODE) $$policy_test"; \
		$(NODE) "$$policy_test" || exit 1; \
	done

# Run Android lint with warnings treated as errors.
lint:
	$(GRADLEW) $(GRADLE_FLAGS) lintDebug

# Produce the debug APK after linting.
build: lint
	$(GRADLEW) $(GRADLE_FLAGS) assembleDebug

# Run the complete local quality gate.
check: test build

# Build and verify the signed release APK from the local signing material in
# $(SECRET_DIR) (ADR-0040): the keystore plus the .env holding the
# credentials and the certificate fingerprint. The --no-daemon flag is
# deliberate here: a running Gradle daemon keeps the environment it was
# started with, so an exported keystore path would not reach the build.
release:
	@set -eu; \
	if [ ! -f "$(SECRET_DIR)/.env" ] || [ ! -f "$(SECRET_DIR)/keystore.jks" ]; then \
		echo "Missing $(SECRET_DIR)/.env or $(SECRET_DIR)/keystore.jks; a signed release needs the local signing material (ADR-0040)." >&2; \
		exit 1; \
	fi; \
	if [ -z "$(APKSIGNER)" ]; then \
		echo "apksigner not found; set APKSIGNER= or point sdk.dir in local.properties at the Android SDK." >&2; \
		exit 1; \
	fi; \
	set -a; . "$(SECRET_DIR)/.env"; set +a; \
	ANDROID_KEYSTORE_PATH="$(abspath $(SECRET_DIR)/keystore.jks)" \
		$(GRADLEW) $(GRADLE_FLAGS) assembleRelease; \
	apk="$(RELEASE_APK)"; \
	"$(APKSIGNER)" verify --print-certs "$$apk"; \
	cert="$$("$(APKSIGNER)" verify --print-certs "$$apk" | awk -F': ' '/certificate SHA-256 digest/ {print $$NF; exit}')"; \
	expected="$$(printf '%s' "$$SHA" | tr -d ':' | tr '[:upper:]' '[:lower:]')"; \
	if [ "$$cert" != "$$expected" ]; then \
		echo "Signer fingerprint mismatch: got $$cert expected $$expected (ADR-0040)." >&2; \
		exit 1; \
	fi; \
	echo "Signed release ready: $$apk (fingerprint $$cert)"

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

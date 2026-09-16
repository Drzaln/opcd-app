.PHONY: build release install serve stop clean ship

build:
	./gradlew assembleDebug

release:
	./gradlew assembleRelease

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

ship:
	./scripts/ship.sh

serve:
	@OPENCODE_SERVER_PASSWORD=secret opencode serve --port 4199 --hostname 127.0.0.1 > /tmp/opencode-serve.log 2>&1 &
	@sleep 3
	@echo "opencode test server on http://127.0.0.1:4199 (user: opencode, password: secret)"
	@curl -s -u opencode:secret http://127.0.0.1:4199/global/health

stop:
	@pkill -f "opencode serve --port 4199" || true
	@echo "stopped"

clean:
	./gradlew clean
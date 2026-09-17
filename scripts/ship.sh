#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE_FILE="app/build.gradle.kts"

current_code=$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+')
current_name=$(grep -oE 'versionName = "[^"]+"' "$GRADLE_FILE" | grep -oE '"[^"]+"' | tr -d '"')

if [ -z "$current_code" ] || [ -z "$current_name" ]; then
  echo "Could not read version from $GRADLE_FILE" >&2
  exit 1
fi

bump="${1:-patch}"
case "$bump" in
  major|minor|patch)
    major="${current_name%%.*}"
    rest="${current_name#*.}"
    minor="${rest%%.*}"
    patch="${rest##*.}"
    case "$bump" in
      major) major=$((major + 1)); minor=0; patch=0 ;;
      minor) minor=$((minor + 1)); patch=0 ;;
      patch) patch=$((patch + 1)) ;;
    esac
    new_name="$major.$minor.$patch"
    ;;
  *)
    if echo "$bump" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
      new_name="$bump"
    else
      echo "usage: ./scripts/ship.sh [patch|minor|major|<x.y.z>]" >&2
      exit 1
    fi
    ;;
esac

new_code=$((current_code + 1))

sed -i '' "s/versionCode = $current_code/versionCode = $new_code/" "$GRADLE_FILE"
sed -i '' "s/versionName = \"$current_name\"/versionName = \"$new_name\"/" "$GRADLE_FILE"

echo "Bumping v$current_name (code $current_code) -> v$new_name (code $new_code)"

PROGRESS_FILE="PROGRESS.md"
if [ -f "$PROGRESS_FILE" ]; then
  if grep -q '^<!-- ship:' "$PROGRESS_FILE"; then
    sed -i '' "1s|.*|<!-- ship: v$new_name (versionCode $new_code) -->|" "$PROGRESS_FILE"
  else
    printf '<!-- ship: v%s (versionCode %s) -->\n\n' "$new_name" "$new_code" | cat - "$PROGRESS_FILE" > "$PROGRESS_FILE.tmp" && mv "$PROGRESS_FILE.tmp" "$PROGRESS_FILE"
  fi
fi

./gradlew assembleRelease --no-daemon

git add -A
git commit -m "Release v$new_name (versionCode $new_code)"
git push origin main
git tag "v$new_name"
git push origin "v$new_name"

echo "Shipped v$new_name"
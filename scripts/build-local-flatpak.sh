#!/bin/sh
# Build chiaki-ng from THIS checkout as a Flatpak and install it for the current user,
# replacing the Flathub version (same app id, so settings and registered consoles are kept).
#
# Needs (no sudo):  flatpak install --user flathub org.flatpak.Builder org.kde.Sdk//6.11 io.qt.qtwebengine.BaseApp//6.11
# Usage:            scripts/build-local-flatpak.sh
# Only committed code is built (the builder clones this repo at HEAD, including submodules).
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
APP=io.github.streetpea.Chiaki4deck
SHA=$(git -C "$REPO" rev-parse HEAD)
WORK=${XDG_CACHE_HOME:-$HOME/.cache}/chiaki-ng-local-build

if [ -n "$(git -C "$REPO" status --porcelain --untracked-files=no)" ]; then
    echo "Note: uncommitted changes are NOT included in this build; commit them first to include them."
fi

mkdir -p "$WORK"
# Patches referenced by the manifest live next to it
cp "$REPO"/scripts/flatpak/*.patch "$WORK"/ 2>/dev/null || true
sed -e "s|^        url: https://github.com/chiaki-ng/chiaki-ng.git$|        url: file://$REPO|" \
    -e "s|^        branch: main$|        commit: $SHA|" \
    "$REPO/scripts/flatpak/$APP.yaml" > "$WORK/$APP.yaml"
grep -q "url: file://$REPO" "$WORK/$APP.yaml" || { echo "Couldn't point the manifest at this checkout"; exit 1; }
grep -q "commit: $SHA" "$WORK/$APP.yaml" || { echo "Couldn't pin the manifest to $SHA"; exit 1; }

echo "Building $APP from $REPO at $(git -C "$REPO" log -1 --format='%h %s')"
cd "$WORK"
flatpak run org.flatpak.Builder \
    --user --install --force-clean --disable-rofiles-fuse \
    --state-dir="$WORK/state" \
    --ccache \
    "$WORK/build" "$WORK/$APP.yaml"

echo "Installed. Commit: $(flatpak info --user --show-commit "$APP")"

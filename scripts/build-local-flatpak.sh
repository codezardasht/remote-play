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
# Patches and included manifests (python3-modules.yaml, …) are referenced relative to the manifest
cp -r "$REPO"/scripts/flatpak/. "$WORK"/
sed -e "s|^        url: https://github.com/chiaki-ng/chiaki-ng.git$|        url: file://$REPO|" \
    -e "s|^        branch: main$|        commit: $SHA|" \
    "$REPO/scripts/flatpak/$APP.yaml" > "$WORK/$APP.yaml"
grep -q "url: file://$REPO" "$WORK/$APP.yaml" || { echo "Couldn't point the manifest at this checkout"; exit 1; }
grep -q "commit: $SHA" "$WORK/$APP.yaml" || { echo "Couldn't pin the manifest to $SHA"; exit 1; }

# After the first build every third-party source is cached. Only refresh this checkout's copy and skip the
# network for the rest, so a down server (gitlab.xiph.org, …) can't break a local rebuild.
# FULL_UPDATE=1 re-checks all sources.
UPDATES=""
MIRROR="$WORK/state/git/file_$(printf '%s' "${REPO#/}" | tr '/' '_')"
if [ "${FULL_UPDATE:-0}" != 1 ] && [ -d "$MIRROR" ]; then
    git -C "$MIRROR" fetch --quiet --prune "$REPO" '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
    git -C "$MIRROR" cat-file -e "$SHA^{commit}" && UPDATES="--disable-updates"
fi

echo "Building $APP from $REPO at $(git -C "$REPO" log -1 --format='%h %s')"
cd "$WORK"
flatpak run org.flatpak.Builder \
    --user --install --force-clean --disable-rofiles-fuse $UPDATES \
    --state-dir="$WORK/state" \
    --ccache \
    "$WORK/build" "$WORK/$APP.yaml"

# The local build is branch "master"; if the Flathub copy ("stable") is also installed, open the local one by default
flatpak make-current --user "$APP" master
COMMIT=$(flatpak info --user --show-commit "$APP//master")
# Keep the QML-on-pull override pointed at this build (see ~/.local/share/chiaki-ng-ui)
if flatpak override --user --show "$APP" 2>/dev/null | grep -q '^CHIAKI_QML_APP_COMMIT='; then
    flatpak override --user --env=CHIAKI_QML_APP_COMMIT="$COMMIT" "$APP"
fi
echo "Installed and set as default. Commit: $COMMIT"
echo "Flathub version still available: flatpak run $APP//stable"

#!/bin/bash
# Makes a Mac release that installed copies pick up on their own (Sparkle, App/Updates.swift).
#
#   1. Raise CFBundleShortVersionString and CFBundleVersion in mac/Info.plist.
#   2. scripts/release-mac.sh ["What's new, one line"]
#
# With the GitHub CLI signed in (brew install gh; gh auth login, once), it does the rest itself:
# creates the GitHub release with the DMG, then commits and pushes docs/appcast.xml, in that order,
# so the feed never points at a DMG that isn't online yet. Without it, it prints those two steps.
#
# Signs with the "Lync Local" certificate (or SIGN_IDENTITY): every release must use the same one,
# or macOS treats the update as a different app and asks for its permissions again. The update
# itself is signed with the EdDSA key in the login keychain (account "lync"), which matches
# SUPublicEDKey in Info.plist. Back up both.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="santhoshsunkara24/Lync"
# The website serves the DMG (scripts/site.sh); GitHub keeps a copy of each release too.
SITE="https://lync-theta.vercel.app"
APPCAST="../docs/appcast.xml"
SPARKLE_BIN=".build/artifacts/sparkle/Sparkle/bin"

if [ -z "${SIGN_IDENTITY:-}" ] && ! security find-certificate -c "Lync Local" >/dev/null 2>&1; then
  echo "No \"Lync Local\" certificate in the keychain. A release signed ad-hoc can't update earlier ones" >&2
  echo "without macOS asking for every permission again. Make it once (Keychain Access › Certificate" >&2
  echo "Assistant › Create a Certificate: name Lync Local, Self Signed Root, Code Signing), then rerun." >&2
  exit 1
fi

VERSION=$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" Info.plist)
BUILD=$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" Info.plist)
# Sparkle offers an update only when its build number is higher than the installed one.
LAST=$(sed -n 's:.*<sparkle\:version>\([0-9]*\)</sparkle\:version>.*:\1:p' "$APPCAST" | head -1)
if [ -n "$LAST" ] && [ "$BUILD" -le "$LAST" ]; then
  echo "CFBundleVersion is $BUILD, but the appcast already offers build $LAST. Raise it in Info.plist." >&2
  exit 1
fi

scripts/build-app.sh release dmg
TAG="mac-v$VERSION"
DMG="build/Lync-$VERSION.dmg"
mv build/Lync.dmg "$DMG"

# sparkle:edSignature="…" length="…", for the enclosure.
SIGNATURE=$("$SPARKLE_BIN/sign_update" --account lync "$DMG")
URL="$SITE/download/Lync-$VERSION.dmg"
MIN_OS=$(/usr/libexec/PlistBuddy -c "Print :LSMinimumSystemVersion" Info.plist)

cat > "$APPCAST" <<XML
<?xml version="1.0" encoding="utf-8"?>
<!-- The Mac app's update feed, read by Sparkle once a day. Written by mac/scripts/release-mac.sh. -->
<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle">
  <channel>
    <title>Lync for Mac</title>
    <link>$SITE/appcast.xml</link>
    <item>
      <title>Lync $VERSION</title>
      <pubDate>$(LC_ALL=C date -u "+%a, %d %b %Y %H:%M:%S +0000")</pubDate>
      <sparkle:version>$BUILD</sparkle:version>
      <sparkle:shortVersionString>$VERSION</sparkle:shortVersionString>
      <sparkle:minimumSystemVersion>$MIN_OS</sparkle:minimumSystemVersion>
      <link>https://github.com/$REPO/releases/tag/$TAG</link>
      <enclosure url="$URL" type="application/octet-stream" $SIGNATURE />
    </item>
  </channel>
</rss>
XML

echo
echo "Built $DMG and updated docs/appcast.xml for Lync $VERSION (build $BUILD)."

WHATSNEW=""
[ -n "${1:-}" ] && WHATSNEW="
What is new: $1
"
BODY="Lync for Mac: your Android phone's codes, calls, texts and files, in your Mac's notch or menu bar.
$WHATSNEW
Requires macOS $MIN_OS or later, and the Lync app on your Android phone.

First time opening: macOS will say it can't check the app. Open System Settings › Privacy & Security, scroll down, and click \"Open Anyway\" next to Lync. You only need to do this once; updates install by themselves."

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  # 1. The DMG goes online first…
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" "$DMG" --repo "$REPO" --clobber
  else
    gh release create "$TAG" "$DMG" --repo "$REPO" --target main --title "Lync for Mac $VERSION" --notes "$BODY" --latest
  fi
  # The website: the DMG, and the new feed with it, in one deploy.
  ../scripts/site.sh
  curl -sfIL "$URL" >/dev/null || { echo "The DMG isn't downloadable at $URL yet; not pushing the appcast." >&2; exit 1; }
  # 2. …then the feed that points to it. Only the appcast: nothing else that's waiting is committed.
  git -C .. add docs/appcast.xml
  git -C .. commit -q -m "Mac $VERSION (build $BUILD): update feed" -- docs/appcast.xml
  git -C .. push -q origin HEAD
  echo "Released: $SITE (download: $SITE/download/Lync.dmg), copy at https://github.com/$REPO/releases/tag/$TAG"
  echo "Installed copies see it within a day, or straight away from Check for Updates."
else
  echo "The GitHub CLI isn't signed in (brew install gh; gh auth login), so do these two, in order:"
  echo "  1. On GitHub, create release $TAG and attach $DMG (keep its name: Lync-$VERSION.dmg)."
  echo "  2. Commit and push docs/appcast.xml."
fi

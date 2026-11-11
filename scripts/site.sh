#!/bin/bash
# Builds the public website from docs/ and deploys it to Vercel, with the Mac app's DMG served from
# the site itself (/download/Lync.dmg is always the newest; /download/Lync-<version>.dmg each one).
#
#   scripts/site.sh            # build and deploy to production
#   scripts/site.sh build      # build only, into .site/lync
#
# Only what the public needs goes up: the homepage, its assets, the licences, the Mac update feed
# and the privacy policy (rendered from docs/PRIVACY.md). Design and Play notes stay in the repo.
# The DMGs come from mac/build/ and are never committed.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=".site/lync"
rm -rf "$OUT" && mkdir -p "$OUT/download" "$OUT/privacy"

cp docs/index.html docs/appcast.xml "$OUT/"
cp -R docs/assets docs/licenses docs/vectors "$OUT/"

# Every DMG built so far, and the newest as Lync.dmg.
latest=""
for dmg in $(ls mac/build/Lync-*.dmg 2>/dev/null | sort -V); do cp "$dmg" "$OUT/download/"; latest="$dmg"; done
[ -n "$latest" ] && cp "$latest" "$OUT/download/Lync.dmg" || echo "No DMG in mac/build/: the site goes up without one." >&2

# The privacy policy as a page of its own, in the site's fonts and colours.
BODY=$(npx -y marked@15.0.12 --gfm < docs/PRIVACY.md)
cat > "$OUT/privacy/index.html" <<HTML
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Privacy policy · Lync</title><link rel="icon" href="/assets/lync-icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&family=Galindo&display=swap" rel="stylesheet">
<style>
:root { color-scheme: dark; }
body { margin: 0; background: #050807; color: rgba(255,255,255,.86); font: 17px/1.6 Figtree, system-ui, sans-serif; }
main { max-width: 720px; margin: 0 auto; padding: 56px 20px 96px; }
a { color: #7FE3B0; } h1 { font: 44px/1.15 Galindo, Figtree, sans-serif; color: #fff; margin: 0 0 8px; }
h2 { font: 600 22px/1.3 Figtree; color: #fff; margin: 40px 0 8px; } code { font-size: .9em; background: rgba(255,255,255,.08); padding: 1px 6px; border-radius: 6px; }
table { width: 100%; border-collapse: collapse; font-size: 15px; display: block; overflow-x: auto; } th, td { text-align: left; vertical-align: top; padding: 10px 12px 10px 0; border-bottom: 1px solid rgba(255,255,255,.1); }
.back { display: inline-block; margin-bottom: 32px; text-decoration: none; font-weight: 600; }
</style></head><body><main><a class="back" href="/">← Lync</a>
$BODY
</main></body></html>
HTML
# The old address (…/PRIVACY), as linked from the homepage and the Play listing, keeps working.
cat > "$OUT/vercel.json" <<'JSON'
{
  "cleanUrls": true,
  "redirects": [{ "source": "/PRIVACY", "destination": "/privacy" }, { "source": "/PRIVACY.html", "destination": "/privacy" }],
  "headers": [
    { "source": "/download/(.*)\\.dmg", "headers": [{ "key": "Content-Type", "value": "application/x-apple-diskimage" }, { "key": "Content-Disposition", "value": "attachment" }] },
    { "source": "/appcast.xml", "headers": [{ "key": "Cache-Control", "value": "no-cache" }] }
  ]
}
JSON
echo "Built $OUT"
[ "${1:-}" = "build" ] && exit 0

cd "$OUT"
npx -y vercel@65.14.0 deploy --prod --yes

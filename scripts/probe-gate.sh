#!/bin/bash
# Test of de consent-gate van DPG Media nog te omzeilen is.
#
#   scripts/probe-gate.sh <artikel-url>
#
# Context: Article.extractContainer zoekt <div class="article largeWidth">. Op
# een echte Tweakers-artikelpagina zit die div er, maar de pagina komt eerst
# langs een privacy-gate die een lege pagina van 8 kB terugstuurt. De plugin
# had daar een oplossing voor met een decodeURIComponent-bevestigingslink in de
# pagina; die is hier nog niet nagevolgd.
#
# Dit script probeert de routes die het plugin-script gebruikte plus een paar
# varianten, en zegt per route wat er terugkomt. Zonder dit te snappen is elke
# artikelweergave op Tweakers leeg, dus het hoort vóór de extractie.
set -euo pipefail

UA='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36'
URL="${1:?geef een artikel-url}"

probe() {
  local label="$1"; shift
  local out; out=$(mktemp)
  local code
  code=$(curl -sL --max-time 25 --compressed \
      -A "$UA" -H "Accept-Language: nl-NL,nl;q=0.9" \
      "$@" -o "$out" -w '%{http_code}' "$URL")
  local size; size=$(stat -c%s "$out")
  local title; title=$(grep -oE '<title>[^<]{0,60}' "$out" | head -1 | sed 's/<title>//')
  local container; container=$(grep -c 'class="article' "$out" || true)
  printf '%-34s http=%s %6s bytes  container=%s  %s\n' \
      "$label" "$code" "$size" "$container" "$title"
  # Als de gate weg is en de container er is: de bevestigingslink printen.
  if [ "$container" -gt 0 ]; then
    grep -oE 'https://[^"'\'']*decodeURIComponent[^"'\'']*' "$out" | head -1 || true
  fi
  rm -f "$out"
}

echo "== $URL"
echo
probe "zonder cookie"
probe "CONSENT=YES+"            -b "CONSENT=YES+"
probe "CONSENT=1"               -b "CONSENT=1"
probe "CONSENT=YES"             -b "CONSENT=YES"
probe "consent_gate=1"          -b "consent_gate=1"
probe "CONSENT+ twee cookies"   -b "CONSENT=YES+" -b "cookie_consent=true"
probe "geen gate, alleen UA"    -H "Sec-GPC: 1"
probe "googlebot"               -A "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

echo
echo "Let op: een container > 0 betekent dat de gate weg is."
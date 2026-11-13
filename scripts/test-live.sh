#!/bin/bash
# Test de volledige keten: netwerk -> feed -> koppen, tegen de echte feeds.
#
# Fetcher gebruikt alleen java.net en Feed gebruikt XmlPullParser. Met kxml2 op
# het classpath draait dus exact de productiecode, buiten de telefoon om. Alleen
# de parserfactory wordt omgezet (zie de uitleg in Feed.java).
#
#   scripts/test-live.sh
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-35/android.jar"
KXML="${KXML_JAR:-$HOME/.cache/kxml2.jar}"
[ -f "$PLATFORM" ] || { echo "android.jar ontbreekt: $PLATFORM" >&2; exit 1; }
[ -f "$KXML" ] || { echo "kxml2.jar ontbreekt: $KXML" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src/nl/michiel/nieuws" "$WORK/out"

for f in Feed.java Headline.java Fetcher.java; do
  cp "$ROOT/app/src/main/java/nl/michiel/nieuws/$f" "$WORK/src/nl/michiel/nieuws/"
done

cat > "$WORK/src/nl/michiel/nieuws/Main.java" <<'EOF'
package nl.michiel.nieuws;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        Feed.setParserFactory(new Feed.ParserFactory() {
            @Override
            public org.xmlpull.v1.XmlPullParser newPullParser() {
                return new org.kxml2.io.KXmlParser();
            }
        });

        int failures = 0;
        for (String url : args) {
            String label = url.replaceAll("https?://", "").replaceAll("/.*$", "");
            System.out.println("== " + label);
            Fetcher.Result r = Fetcher.fetch(url, url);
            if (r.error != null) {
                System.out.println("   FAIL: " + r.error);
                failures++;
                continue;
            }
            if (r.feed.error != null) {
                System.out.println("   FAIL(parse): " + r.feed.error);
                failures++;
                continue;
            }
            List<Headline> items = r.feed.items;
            if (items.isEmpty()) {
                System.out.println("   FAIL: geen items");
                failures++;
                continue;
            }
            for (Headline h : items) {
                if (!h.link.startsWith("http") || h.title.trim().isEmpty()) {
                    System.out.println("   FAIL: ongeldige kop: " + h.title + " -> " + h.link);
                    failures++;
                    break;
                }
            }
            System.out.println("   ok: " + items.size() + " koppen");
            for (int i = 0; i < Math.min(2, items.size()); i++) {
                Headline h = items.get(i);
                String t = h.title.length() > 58 ? h.title.substring(0, 58) + "…" : h.title;
                System.out.println("     " + (i + 1) + ". " + t);
                System.out.println("        " + h.date + "  " + h.link);
            }
        }
        System.out.println(failures == 0 ? "ALLE TESTS GESLAAGD" : failures + " FOUTEN");
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF

javac -encoding UTF-8 -nowarn -classpath "$PLATFORM:$KXML" \
      -d "$WORK/out" $(find "$WORK/src" -name '*.java')

java -classpath "$PLATFORM:$KXML:$WORK/out" nl.michiel.nieuws.Main \
     https://tweakers.net/feeds/nieuws.xml \
     https://lobste.rs/rss
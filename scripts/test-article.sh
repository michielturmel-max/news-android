#!/bin/bash
# Test de artikelextractie tegen echte pagina's.
#
# Article en PageFetcher gebruiken alleen java.net en regex, dus ze draaien
# buiten de telefoon. PageFetcher stuurt de consent-gate-cookie, wat de
# DPG-interstitial omzeilt; zonder die cookie levert tweakers.net geen artikel.
#
#   scripts/test-article.sh <url> [<url> …]
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-35/android.jar"
[ -f "$PLATFORM" ] || { echo "android.jar ontbreekt: $PLATFORM" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src/nl/michiel/nieuws" "$WORK/out"
for f in Article.java Headline.java PageFetcher.java; do
  cp "$ROOT/app/src/main/java/nl/michiel/nieuws/$f" "$WORK/src/nl/michiel/nieuws/"
done

cat > "$WORK/src/nl/michiel/nieuws/Main.java" <<'EOF'
package nl.michiel.nieuws;

public class Main {
    public static void main(String[] args) {
        int failures = 0;
        for (String url : args) {
            System.out.println("== " + url.replaceAll("https?://", ""));
            PageFetcher.Result page = PageFetcher.fetch(url, url);
            if (page.error != null) {
                System.out.println("   FAIL ophalen: " + page.error);
                failures++;
                continue;
            }
            System.out.println("   html: " + page.html.length() + " bytes");

            Article.Parsed p = Article.parse(page.html);
            if (!p.ok) {
                System.out.println("   FAIL extractie: " + p.reason);
                failures++;
                continue;
            }
            System.out.println("   titel:  " + p.title);
            System.out.println("   auteur: " + (p.author.isEmpty() ? "(geen)" : p.author));
            System.out.println("   datum:  " + (p.date.isEmpty() ? "(geen)" : p.date));
            System.out.println("   blokken: " + p.blocks.size());
            int chars = 0;
            for (Article.Block b : p.blocks) chars += b.text.length();
            System.out.println("   tekens: " + chars);

            int show = Math.min(2, p.blocks.size());
            for (int i = 0; i < show; i++) {
                Article.Block b = p.blocks.get(i);
                String t = b.text.length() > 90 ? b.text.substring(0, 90) + "…" : b.text;
                System.out.println("     [" + b.type + "] " + t);
                if (!b.links.isEmpty()) {
                    System.out.println("        " + b.links.size() + " link(s), bv. "
                            + b.links.get(0)[0]);
                }
            }
            if (p.blocks.isEmpty() || chars < 200) {
                System.out.println("   FAIL: te weinig tekst, dit is geen artikel");
                failures++;
            }
        }
        System.out.println(failures == 0 ? "ALLE TESTS GESLAAGD" : failures + " FOUTEN");
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF

javac -encoding UTF-8 -nowarn -classpath "$PLATFORM" \
      -d "$WORK/out" $(find "$WORK/src" -name '*.java')

java -classpath "$PLATFORM:$WORK/out" nl.michiel.nieuws.Main "$@"
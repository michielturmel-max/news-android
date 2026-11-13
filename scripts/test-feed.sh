#!/bin/bash
# Test de feed-parser tegen echte feeds, buiten de telefoon om.
#
# De parser draait op android.util.Xml en op android.content.Context, dus een
# kale JVM-test zou die klassen missen. Daarom draait deze test in een
# container mét android.jar op het classpath: XmlPullParser en Xml zitten daar
# in, dus parse() werkt onveranderd. Alleen Store en MainActivity zijn Android-
# afhankelijk en die worden niet getest.
#
#   scripts/test-feed.sh
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-35/android.jar"
[ -f "$PLATFORM" ] || { echo "android.jar ontbreekt: $PLATFORM" >&2; exit 1; }

# android.util.Xml.newPullParser() is in android.jar een stub die "Stub!"
# gooit: het framework levert daar normaal KXmlParser in. Op de telefoon
# werkt het dus, maar voor deze test pakken we dezelfde parser uit kxml2, wat
# de referentie-implementatie is die Android ook gebruikt.
KXML="${KXML_JAR:-$HOME/.cache/kxml2.jar}"
[ -f "$KXML" ] || { echo "kxml2.jar ontbreekt: $KXML" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/src/nl/michiel/nieuws" "$WORK/out"
cp "$ROOT/app/src/main/java/nl/michiel/nieuws/Feed.java" \
   "$ROOT/app/src/main/java/nl/michiel/nieuws/Headline.java" "$WORK/src/nl/michiel/nieuws/"

cat > "$WORK/src/nl/michiel/nieuws/Main.java" <<'EOF'
package nl.michiel.nieuws;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        // Zelfde parser die Android gebruikt, maar hier uit kxml2 in plaats
        // van de framework-stub. De parse-logica is identiek.
        Feed.setParserFactory(Feed.ParserFactory.class.cast(
                new Feed.ParserFactory() {
                    @Override
                    public org.xmlpull.v1.XmlPullParser newPullParser() {
                        return new org.kxml2.io.KXmlParser();
                    }
                }));
        int failures = 0;
        for (String path : args) {
            String label = Paths.get(path).getFileName().toString();
            byte[] xml = Files.readAllBytes(Paths.get(path));
            Feed.Result r = Feed.parse(xml, StandardCharsets.UTF_8);
            if (r.error != null) {
                System.out.println("FAIL " + label + ": " + r.error);
                failures++;
                continue;
            }
            List<Headline> items = r.items;
            if (items.isEmpty()) {
                System.out.println("FAIL " + label + ": geen items");
                failures++;
                continue;
            }
            System.out.println("ok   " + label + ": " + items.size() + " items");
            for (int i = 0; i < Math.min(2, items.size()); i++) {
                Headline h = items.get(i);
                boolean linkOk = h.link.startsWith("http");
                boolean titleOk = h.title != null && h.title.length() > 3;
                if (!linkOk || !titleOk) {
                    System.out.println("     KOP " + (i + 1) + " ONGELDIG: link=" + h.link);
                    failures++;
                    continue;
                }
                String t = h.title.length() > 62 ? h.title.substring(0, 62) + "…" : h.title;
                System.out.println("     " + (i + 1) + ". " + t);
                System.out.println("        " + h.date + "  " + h.link);
                if (h.summary.length() > 70) {
                    System.out.println("        " + h.summary.substring(0, 70) + "…");
                } else if (!h.summary.isEmpty()) {
                    System.out.println("        " + h.summary);
                }
            }
        }
        System.out.println(failures == 0 ? "ALLE TESTS GESLAAGD" : failures + " FOUTEN");
        System.exit(failures == 0 ? 0 : 1);
    }
}
EOF

javac -encoding UTF-8 -nowarn -classpath "$PLATFORM:$KXML" \
      -d "$WORK/out" $(find "$WORK/src" -name '*.java')

java -classpath "$PLATFORM:$KXML:$WORK/out" nl.michiel.nieuws.Main "$@"
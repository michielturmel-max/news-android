#!/bin/bash
# Bouw de nieuws-APK. Zonder Gradle: aapt2 voor de resources, javac voor de
# java-code, d8 voor de dex, apksigner voor de handtekening. Dat zijn alle
# tools uit de Android SDK en de hele keten is reproduceerbaar met dit script.
#
#   scripts/build-apk.sh          bouwen + ondertekenen -> dist/
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
DIST="$ROOT/dist"

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35"
[ -d "$BT" ] || { echo "build-tools ontbreekt in $BT" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"/{gen,classes,dex,apk} "$DIST"

# 1. resources -> flat bestanden (resources.arsc + R.java)
echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/gen/res.zip"

echo "== aapt2 link =="
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$PLATFORM/android.jar" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 1.0.0 \
  "$OUT/gen/res.zip"

# 2. java -> class-bestanden
echo "== javac =="
find "$APP/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs echo "  bronbestanden:"
javac \
  -classpath "$PLATFORM/android.jar" \
  -d "$OUT/classes" \
  -encoding UTF-8 -nowarn \
  @"$OUT/sources.txt"

# 3. class -> dex
echo "== d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM/android.jar" \
  --output "$OUT/dex" @"$OUT/classes.txt"

# 4. dex in de apk
echo "== dex in apk =="
# Met een absoluets pad zou aapt de entry als '/work/build/dex/classes.dex'
# wegschrijven, en dan leest de package-parser geen classes.dex. Daarom eerst
# de dex naast de apk leggen en vanuit die map toevoegen.
cp "$OUT/dex/classes.dex" "$OUT/apk/classes.dex"
cd "$OUT/apk"
"$BT/aapt" add -f base.apk classes.dex >/dev/null
rm -f classes.dex
"$BT/zipalign" -f 4 base.apk base-aligned.apk

# 5. ondertekenen met een debug-sleutel
#    Eigen, bewaarde sleutel. De debug.keystore van de SDK is per machine
#    uniek, dus dan zou een update na herinstallatie van de SDK niet lukken.
# De sleutel moet tussen builds STABIEL blijven: elke keer een nieuwe maken
# geeft een andere handtekening, en dan weigert een update over de vorige
# installatie heen met INSTALL_FAILED_UPDATE_INCOMPATIBLE. Daarom staat hij
# buiten build/ (dat elke run gewist wordt) en wordt hij bewaard in git.
KEY="$ROOT/debug.keystore"
if [ ! -f "$KEY" ]; then
  echo "== debug-sleutel maken =="
  keytool -genkeypair -v -keystore "$KEY" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Nieuws Debug, OU=Dev, O=michiel, C=NL" >/dev/null 2>&1
fi

echo "== apksigner =="
"$BT/apksigner" sign \
  --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --min-sdk-version 26 \
  --out "$DIST/nieuws.apk" \
  base-aligned.apk

"$BT/apksigner" verify --print-certs "$DIST/nieuws.apk" | head -3

echo
echo "klaar: $DIST/nieuws.apk  ($(stat -c%s "$DIST/nieuws.apk") bytes)"
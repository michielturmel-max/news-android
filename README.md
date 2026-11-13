# Nieuws — de Omarchy-plugin als Android-app

Een Android-poort van [Tweakers Nieuws](https://github.com/michielturmel-max/tweakers-nieuws): koppen uit RSS-feeds in een eenvoudige app, met meerdere bronnen, een lezer en een eigen archief.

| | Plugin (Quickshell) | Deze app |
|---|---|---|
| RSS-feeds | ja | ja |
| Meerdere bronnen | ja | ja, spinner bovenaan |
| Bronnen bewerken | ja, eigen dialoog | ja, `naam \| url` per regel |
| Koppen tellen | ja | 5, opgeslagen in voorkeuren |
| Artikel lezen | haalt volledige tekst uit de HTML | toont de feed-samenvatting + knop om te openen |
| Archief | ja, markdown | ja, eigen JSON in de app-map |
| YouTube-kanalen | ja, via scrapen | **nee**, zie onderaan |
| Verwijderen | `r` in het paneel | lang drukken |

## Vereisten

- Android 8.0 (API 26) of nieuwer.
- Voor zelf bouwen: de Android SDK met build-tools 35.0.0 en platform android-35, plus een JDK.

## Downloaden en installeren

### Kant-en-klare APK

Download `nieuws.apk` bij de [releases](https://github.com/michielturmel-max/news-android/releases) en installeer hem op je telefoon:

```bash
adb install -r nieuws.apk
```

Of zet het bestand op je telefoon en open het; sta dan "installeren uit onbekende bronnen" toe voor je bestandsbeheerder.

### Zelf bouwen

Zonder Gradle: aapt2, javac, d8, zipalign en apksigner uit de SDK, in die volgorde. Het script doet de hele keten:

```bash
export ANDROID_HOME=/opt/android-sdk   # of je eigen SDK-pad
scripts/build-apk.sh
```

Het resultaat is `dist/nieuws.apk`. Het script verwacht `build-tools/35.0.0` en `platforms/android-35` onder `ANDROID_HOME`.

De APK wordt ondertekend met de meegeleverde `debug.keystore`. Die sleutel is bewust stabiel: met een nieuwe sleutel weigert Android een update boven op een bestaande installatie (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Het is een debug-sleutel, geen productiesleutel; publiceer hier geen app mee in een winkel.

## Gebruik

- **Bron toevoegen** via `☰` → *Nieuwe bron*: een naam en een RSS/Atom-feedadres.
- **Lezen**: tik op een kop; de app haalt de volledige tekst op. De knop opent het origineel in de browser.
- **Archiveren**: via het menu bewaar je een artikel in het archief; daar kun je ook zoeken.
- **Verwijderen**: lang drukken op een kop.
- **Verversen**: omlaag trekken of de menu-optie gebruiken.

De eerste keer staan er drie voorbeeldbronnen in (Tweakers, Tweakers Reviews en Lobsters). Die pas je aan via het menu.

## Wat waar staat

Bronnen, gelezen artikelen en het archief staan in de app-map (`getFilesDir()`), dus niet op externe opslag. De app vraagt alleen `INTERNET` en `ACCESS_NETWORK_STATE`; geen telefoon-, contact-, locatie- of opslagrechten.

## Testen

Twee niveaus, allebei buiten de telefoon om. De tests gebruiken dezelfde parse-code als de app, met kxml2 in plaats van de framework-parser:

```bash
# parser tegen opgeslagen feeds
KXML_JAR=/pad/naar/kxml2.jar scripts/test-feed.sh feed1.xml feed2.xml

# volledige keten tegen de echte feeds
KXML_JAR=/pad/naar/kxml2.jar scripts/test-live.sh
```

`android.util.Xml.newPullParser()` is in `android.jar` een stub die `Stub!` gooit. Daarom zit de parseraanmaak in `Feed.ParserFactory`: op Android gebruikt hij de framework-parser, in de tests kxml2 — dezelfde referentie-implementatie die Android zelf gebruikt. De parse-logica is in beide gevallen exact dezelfde code.

## YouTube is er niet in

De plugin haalde video's op door YouTube te scrapen. Dat werkt zonder API-sleutel maar is precies de techniek die YouTube elk moment kan dichtgooien. Zonder die hulpscripts is er geen YouTube-bron, en een half werkende bron zou erger zijn dan geen. Inbouwen kan met een echte YouTube Data API-sleutel of door het scrapen over te nemen; allebei vraagt een keuze die niet automatisch te maken valt.

## Licentie

MIT, zie [LICENSE](LICENSE).

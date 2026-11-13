package nl.michiel.nieuws;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RSS 2.0 en Atom lezen met de XmlPullParser die al in de Android-framework zit.
 *
 * De plugin deed dit met een perl-regex over de ruwe XML. Dat werkt, maar een
 * pull-parser is robuuster: geen escape-ontploffingen zoals de plugin er
 * expliciet omheen moest bouwen (zie de unesc/clean-functies daar). Het
 * decoder-werk dat de plugin deed gebeurt hier één keer, door de parser.
 */
final class Feed {

    /** Wrapper zodat de exception van de parser niet door de hele app hoeft. */
    static final class Result {
        final List<Headline> items = new ArrayList<>();
        final String feedTitle = "";
        String error;
    }

    /**
     * Android levert Xml.newPullParser() als de parser die het framework zelf
     * gebruikt. Deze app hoeft geen dependency mee te dragen, dus dat is wat er
     * op de telefoon gebeurt.
     *
     * De tests draaien in een container met alleen android.jar, en dat is een
     * stub die "Stub!" gooit. Daarom is de aanmaak losgehaald in een methode
     * die een test kan overschrijven, in plaats van de aanroep hard te
     * programmeren. Zo blijft de productiecode op Android staan en werkt
     * dezelfde parse-logica buiten de telefoon.
     */
    interface ParserFactory {
        XmlPullParser newPullParser();
    }

    private static ParserFactory factory = new XmlPullParserFactory();

    static void setParserFactory(ParserFactory f) {
        factory = f;
    }

    private static XmlPullParser newPullParser() {
        return factory.newPullParser();
    }

    /** Android: de parser uit het framework. */
    static final class XmlPullParserFactory implements ParserFactory {
        @Override
        public XmlPullParser newPullParser() {
            return android.util.Xml.newPullParser();
        }
    }

    static Result parse(byte[] xml, Charset cs) {
        Result r = new Result();
        try {
            // Sommige feeds geven een charset in de XML-declaratie dat in de
            // bytes klopt maar niet in de HTTP-header; daar vallen we op terug.
            Charset use = cs != null ? cs : Charset.forName("UTF-8");
            XmlPullParser p = newPullParser();
            try {
                p.setInput(new ByteArrayInputStream(xml), use.name());
            } catch (XmlPullParserException e) {
                r.error = "charset ongeldig: " + use.name();
                return r;
            }

            String inItem = null;          // "item" (RSS) of "entry" (Atom)
            String cur = null;
            String title = null, link = null, date = null, summary = null, guid = null;

            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if (tag.equals("item") || tag.equals("entry")) {
                        inItem = tag;
                        title = link = date = summary = guid = null;
                    } else if (inItem != null && tag.equals("title")) {
                        cur = "title";
                    } else if (inItem != null && tag.equals("link")) {
                        String rel = p.getAttributeValue(null, "rel");
                        String href = p.getAttributeValue(null, "href");
                        if (href != null) {
                            // Atom: <link rel="alternate" href="..."/>
                            if ((rel == null || "alternate".equals(rel)) && link == null) {
                                link = href.trim();
                            }
                        } else {
                            cur = "link";   // RSS: <link>tekst</link>
                        }
                    } else if (inItem != null && tag.equals("guid")) {
                        // Sommige feeds geven de URL als guid. Niet elke guid is
                        // een URL: permalinks zonder scheme willen we overslaan,
                        // want die geven "gebroken link" in de browser.
                        cur = "guid";
                    } else if (inItem != null
                            && (tag.equals("pubDate") || tag.equals("published")
                                || tag.equals("updated") || tag.equals("dc:date"))) {
                        cur = "date";
                    } else if (inItem != null
                            && (tag.equals("description") || tag.equals("summary")
                                || tag.equals("content"))) {
                        cur = "summary";
                    }
                } else if (ev == XmlPullParser.TEXT && cur != null) {
                    String text = p.getText();
                    switch (cur) {
                        case "title": title = append(title, text); break;
                        case "link": link = append(link, text); break;
                        case "guid": guid = append(guid, text); break;
                        case "date": date = append(date, text); break;
                        case "summary": summary = append(summary, text); break;
                        default: break;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if (tag.equals(inItem)) {
                        // Een feed zonder <link> maar met een permalink-guid is
                        // nog bruikbaar; zonder beide niet.
                        String href = (link != null && !link.isEmpty())
                                ? link.trim()
                                : (guid != null && guid.trim().startsWith("http")
                                    ? guid.trim() : null);
                        if (title != null && !title.trim().isEmpty() && href != null) {
                            r.items.add(new Headline(
                                    clean(title), href, niceDate(date), stripTags(summary)));
                        }
                        inItem = null;
                        cur = null;
                    } else if (tag.equals("title") || tag.equals("link") || tag.equals("guid")
                            || tag.equals("pubDate") || tag.equals("published")
                            || tag.equals("updated") || tag.equals("dc:date")
                            || tag.equals("description") || tag.equals("summary")
                            || tag.equals("content")) {
                        cur = null;
                    }
                }
            }
        } catch (XmlPullParserException e) {
            r.error = "XML kapot: " + e.getLineNumber();
        } catch (IOException | RuntimeException e) {
            r.error = "kon de feed niet lezen: " + e;
        }
        return r;
    }

    private static String append(String a, String b) {
        if (a == null) return b;
        return a + b;
    }

    /**
     * Haalt markup eruit. De plugin deed dit met twee regex-rondes omdat sommige
     * feeds al-HTML leveren die al ge-escaped is en andere echte tags. Hier komt
     * alles binnen als decoded tekst, dus één ronde is genoeg.
     */
    static String stripTags(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?s)<[^>]*>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&eacute;", "é").replace("&euml;", "ë");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        return t.trim();
    }

    private static String clean(String s) {
        return stripTags(s);
    }

    /**
     * "Sun, 27 Sep 2026 09:51:00 +0200" en "2026-09-26T14:15:01+00:00" naar
     * "27 sep 09:51", zoals de plugin het deed.
     */
    static String niceDate(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        String[] nl = {"jan", "feb", "mrt", "apr", "mei", "jun",
                "jul", "aug", "sep", "okt", "nov", "dec"};
        try {
            java.time.ZonedDateTime z = java.time.ZonedDateTime.parse(s);
            return String.format(Locale.getDefault(), "%d %s %02d:%02d",
                    z.getDayOfMonth(), nl[z.getMonthValue() - 1],
                    z.getHour(), z.getMinute());
        } catch (Exception ignored) {
            // niet ISO: val terug op RFC-822, wat de meeste RSS-feeds gebruiken
        }
        try {
            java.time.ZonedDateTime z =
                    java.time.ZonedDateTime.parse(s, RFC822);
            return String.format(Locale.getDefault(), "%d %s %02d:%02d",
                    z.getDayOfMonth(), nl[z.getMonthValue() - 1],
                    z.getHour(), z.getMinute());
        } catch (Exception ignored) {
            // ook dat niet: toon de ruwe datum, afgekapt
        }
        return s.length() > 16 ? s.substring(0, 16) : s;
    }

    private static final java.time.format.DateTimeFormatter RFC822 =
            java.time.format.DateTimeFormatter
                    .ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
}
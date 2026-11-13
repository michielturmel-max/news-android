package nl.michiel.nieuws;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Volledige tekst uit een HTML-artikel, overgezet uit de perl die in
 * ~/Work/news-colector/bin/tweakers-nieuws zit (extract_article).
 *
 * De perl haalt de metadata uit een JSON-LD-blok en de tekst uit de
 * artikelcontainer, met een teller voor div-diepte zodat een geneste div niet
 * de extractie beëindigt. Dat patroon is overgezet; alleen de stringverwerking
 * is Java.
 */
final class Article {

    static final class Block {
        /** p, lead, h, li of quote. */
        final String type;
        final String text;
        /** Links in dit blok, als (label, href). De rest is losse tekst. */
        final List<String[]> links = new ArrayList<>();

        Block(String type, String text) {
            this.type = type;
            this.text = text;
        }

        boolean isHeading() {
            return "h".equals(type);
        }
    }

    static final class Parsed {
        boolean ok;
        String reason = "";
        String title = "";
        String author = "";
        String date = "";
        String comments = "";
        final List<Block> blocks = new ArrayList<>();

        boolean isEmpty() {
            return blocks.isEmpty();
        }
    }

    // Zelfde grenzen als de perl: 200 blokken of 24000 tekens.
    private static final int MAX_BLOCKS = 200;
    private static final int MAX_CHARS = 24000;

    private static final Pattern RE_HEADLINE =
            Pattern.compile("\"headline\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern RE_AUTHOR =
            Pattern.compile("\"author\"\\s*:\\s*\\[.{0,400}?\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                    Pattern.DOTALL);
    private static final Pattern RE_DATE =
            Pattern.compile("\"datePublished\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern RE_COMMENTS =
            Pattern.compile("class=\"commentCount[^\"]*\"[^>]*aria-label=\"([^\"]*)\"", Pattern.DOTALL);
    private static final Pattern RE_TITLE_FALLBACK =
            Pattern.compile("<h1[^>]*>.*?data-cy=\"articleTitle\"[^>]*>(.*?)</span>", Pattern.DOTALL);

    private static final Pattern RE_BLOCK =
            Pattern.compile("<(h2|h3|h4|p|li|blockquote)\\b([^>]*)>(.*?)</\\1>",
                    Pattern.DOTALL);
    private static final Pattern RE_LINK =
            Pattern.compile("<a\\b[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);

    static Parsed parse(String html) {
        Parsed p = new Parsed();

        if (html == null || html.isEmpty()) {
            p.reason = "lege pagina";
            return p;
        }

        p.title = first(RE_HEADLINE, html);
        if (p.title.isEmpty()) {
            p.title = first(RE_TITLE_FALLBACK, html);
        } else {
            p.title = unescapeJson(p.title);
        }
        p.author = unescapeJson(first(RE_AUTHOR, html));
        p.date = dutchDate(first(RE_DATE, html));
        p.comments = first(RE_COMMENTS, html);

        String body = extractContainer(html);
        if (body == null) {
            p.reason = "geen artikelcontainer gevonden";
            return p;
        }

        Matcher m = RE_BLOCK.matcher(body);
        int chars = 0;
        while (m.find() && p.blocks.size() < MAX_BLOCKS && chars < MAX_CHARS) {
            String tag = m.group(1);
            String attrs = m.group(2);
            String inner = m.group(3);

            String type;
            if ("li".equals(tag)) type = "li";
            else if ("blockquote".equals(tag)) type = "quote";
            else if (tag.startsWith("h")) type = "h";
            else if (attrs.contains("lead")) type = "lead";
            else type = "p";

            String text = clean(inner);
            // Lege alinea's zijn bij Tweakers vaak blokken met alleen een
            // afbeelding of iframe; die slaan we over.
            if (text.isEmpty()) continue;

            Block b = new Block(type, text);
            collectLinks(inner, b);
            chars += text.length();
            p.blocks.add(b);
        }

        if (p.blocks.isEmpty()) {
            p.reason = "container gevonden maar geen blokken";
            return p;
        }
        p.ok = true;
        return p;
    }

    /**
     * Zoek de artikelcontainer en pak alles wat er genest in zit. De teller voor
     * de diepte is het hele punt: zonder hem stopt de extractie bij de eerste
     * geneste div.
     */
    private static String extractContainer(String html) {
        int start = html.indexOf("<div class=\"article largeWidth\">");
        if (start < 0) start = html.indexOf("<div class=\"article\">");
        if (start < 0) {
            // Sommige sites gebruiken een ander attribuutvolgorde of een
            // class met meer woorden erachter; dan de eerste div met 'article'
            // in de class pakken.
            Matcher m = Pattern
                    .compile("<div[^>]*class=\"[^\"]*\\barticle\\b[^\"]*\"[^>]*>")
                    .matcher(html);
            if (m.find()) start = m.start();
        }
        if (start < 0) return null;

        Matcher tags = Pattern.compile("<div\\b[^>]*>|</div>").matcher(html);
        tags.region(start, html.length());
        int depth = 0;
        int end = -1;
        while (tags.find()) {
            if (tags.group().startsWith("</")) {
                depth--;
                if (depth == 0) {
                    end = tags.end();
                    break;
                }
            } else {
                depth++;
            }
        }
        // Geen sluitende div gevonden: pak de rest van het document. Beter een
        // te veel dan niets.
        if (end < 0) end = html.length();
        return html.substring(start, end);
    }

    private static void collectLinks(String inner, Block b) {
        Matcher m = RE_LINK.matcher(inner);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&").replace("&quot;", "\"");
            String label = clean(m.group(2));
            if (!href.isEmpty() && !label.isEmpty()) {
                b.links.add(new String[]{label, href});
            }
        }
    }

    // ------------------------------------------------------------- stringwerk

    /** Entities, precies zoals de perl deed. */
    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#0*39;", "'")
                .replace("&#x0*27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&eacute;", "é")
                .replace("&euml;", "ë")
                .replace("&amp;", "&");
    }

    /**
     * Twee rondes tag-strippen met een decode ertussen, want sommige feeds
     * leveren al-HTML die ge-escaped is en andere echte tags. &amp; blijft
     * bewust als laatste, anders zou &amp;lt; dubbel decoderen.
     */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?i)<br\\s*/?>", " ");
        t = unescape(t);
        t = t.replaceAll("<[^>]*>", "");
        t = unescape(t);
        t = t.replaceAll("<[^>]*>", "");
        t = t.replaceAll("&[a-zA-Z#0-9]+;", " ");
        t = t.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        return t.trim();
    }

    /** JSON-string-unescape, want een JSON-LD-waarde kan \n en \\u-escapes hebben. */
    static String unescapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException nfe) {
                            out.append(e);
                        }
                    } else {
                        out.append(e);
                    }
                    break;
                default: out.append(e);
            }
        }
        return out.toString();
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static final String[] NL = {
            "januari", "februari", "maart", "april", "mei", "juni",
            "juli", "augustus", "september", "oktober", "november", "december"};

    private static final Pattern RE_ISO =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2}))?");

    /** "2026-10-05T20:39:00+02:00" -> "5 oktober 2026 20:39". */
    static String dutchDate(String s) {
        if (s == null || s.isEmpty()) return "";
        Matcher m = RE_ISO.matcher(s);
        if (!m.find()) return clean(s);
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        String base = day + " " + (month >= 1 && month <= 12 ? NL[month - 1] : String.valueOf(month))
                + " " + year;
        if (m.group(4) != null) base += " " + m.group(4) + ":" + m.group(5);
        return base;
    }
}
package nl.michiel.nieuws;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/**
 * Feed ophalen. Bewust met HttpURLConnection en niet met een bibliotheek: de
 * app heeft geen dependencies en dat maekt de APK klein en de build simpel.
 *
 * De plugin deed hetzelfde met curl, inclusief een Referer en een User-Agent
 * die niet leeg is. Sommige sites (Tweakers zeker) geven een 403 op een
 * standaard "Java/1.8"-agent, dus die headers zijn overgenomen.
 */
final class Fetcher {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";

    /** Alles van het plugin-script bij elke bron: 25 s. */
    private static final int TIMEOUT_MS = 25_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    static final class Result {
        final Feed.Result feed;
        final String error;

        Result(Feed.Result feed, String error) {
            this.feed = feed;
            this.error = error;
        }
    }

    static Result fetch(String url, String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept",
                    "application/rss+xml,application/atom+xml,application/xml,text/xml,*/*");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (referer != null && !referer.isEmpty()) {
                try {
                    c.setRequestProperty("Referer", new URL(referer).getProtocol()
                            + "://" + new URL(referer).getHost() + "/");
                } catch (Exception ignored) {
                    // onbruikbare referer: gewoon geen Referer sturen
                }
            }

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                return new Result(null, "HTTP " + code);
            }

            Charset cs = charsetFrom(c.getContentType());
            String enc = c.getContentEncoding();
            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(enc)) raw = new GZIPInputStream(raw);

            byte[] body = readAll(raw);
            return new Result(Feed.parse(body, cs), null);
        } catch (IOException e) {
            String m = e.getMessage();
            return new Result(null, m == null ? e.getClass().getSimpleName() : m);
        } catch (RuntimeException e) {
            return new Result(null, String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Charset charsetFrom(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    String cs = p.substring(8).replace("\"", "").trim();
                    try {
                        return Charset.forName(cs);
                    } catch (Exception ignored) {
                        // onbekende charset: ga door op UTF-8
                    }
                }
            }
        }
        return Charset.forName("UTF-8");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("feed te groot (>8 MB)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
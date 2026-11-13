package nl.michiel.nieuws;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/**
 * Artikelpagina ophalen.
 *
 * De consent-gate van DPG Media is het punt dat de plugin hier oploste en dat
 * ik in de eerste versie helemaal niet had: een gewone pagina vraagt eerst om
 * toestemming en levert dan een interstitial, geen artikel. Eén cookie volstaat
 * om daar langs te komen, en de plugin stuurt die er standaard bij.
 */
final class PageFetcher {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0 Mobile Safari/537.36";

    private static final int TIMEOUT_MS = 25_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    /**
     * Standaard de Consent-gate van DPG Media. Op een site zonder gate is dit
     * cookie onschadelijk.
     */
    private static final String CONSENT = "CONSENT=YES+";

    static final class Result {
        final String html;
        final String error;

        Result(String html, String error) {
            this.html = html;
            this.error = error;
        }
    }

    static Result fetch(final String url, final String referer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "nl-NL,nl;q=0.9");
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setRequestProperty("Cookie", CONSENT);
            if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                return new Result(null, "HTTP " + code);
            }

            Charset cs = charsetFrom(c.getContentType(), c.getContentEncoding());
            String enc = c.getContentEncoding();
            InputStream raw = c.getInputStream();
            if ("gzip".equalsIgnoreCase(enc)) raw = new GZIPInputStream(raw);
            byte[] body = readAll(raw);
            return new Result(new String(body, cs), null);
        } catch (IOException e) {
            String m = e.getMessage();
            return new Result(null, m == null ? e.getClass().getSimpleName() : m);
        } catch (RuntimeException e) {
            return new Result(null, String.valueOf(e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Charset charsetFrom(String contentType, String contentEncoding) {
        // Sommige servers zetten charset in de Content-Encoding; dat is een
        // bug aan hun kant maar komt voor.
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (Exception ignored) {
                        // onbekende charset: door naar UTF-8
                    }
                }
            }
        }
        if (contentEncoding != null) {
            for (String part : contentEncoding.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    try {
                        return Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (Exception ignored) {
                        // idem
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
            if (total > MAX_BYTES) throw new IOException("pagina te groot (>4 MB)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Standaard HTTP-cookies per host bewaren. Nu niet gebruikt, want de
     * consent-gate gaat als vaste header, maar handig als een site later een
     * echte sessiecookie wil hebben.
     */
    static CookieManager cookieManager() {
        return new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    }

    static void setConsent(CookieManager cm, URI base) {
        cm.getCookieStore().add(base, new HttpCookie("CONSENT", "YES+"));
    }
}
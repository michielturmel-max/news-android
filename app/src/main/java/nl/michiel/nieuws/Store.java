package nl.michiel.nieuws;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bronnen en archief, beide als JSON op schijf.
 *
 * De Omarchy-plugin bewaart bronnen in sources.json naast het script en het
 * archief in een archief-bestand. Hier is de opslag het app-interne
 * bestandsysteem, want dat is wat een Android-app zonder permissies mag
 * gebruiken. Gebruiker ziet ze niet en de app hoeft geen permissie.
 */
final class Store {

    private static final String PREFS = "nieuws";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_ACTIVE = "activeSource";
    private static final String KEY_COUNT = "count";
    private static final String KEY_SOURCES_CUSTOM = "sourcesTouched";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File archiveFile;

    Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.archiveFile = new File(this.ctx.getFilesDir(), "archief.json");
    }

    // ---------------------------------------------------------------- bronnen

    /** Standaardbronnen, overgenomen uit de plugin behalve de YouTube-regels. */
    /**
     * Standaardbronnen uit het plugin-script. De derde was een YouTube-kanaal
     * en die is weggevallen omdat de app geen API-sleutel heeft; Reviews is
     * daarvoor in de plaats komen en is een echte RSS-feed.
     *
     * Lobsters levert Engelse titels; daarom staat er een "en"-regel bij zodat
     * het verschil niet als fout lijkt.
     */
    private static final String DEFAULT_SOURCES =
            "[{\"name\":\"Tweakers\",\"url\":\"https://tweakers.net/feeds/nieuws.xml\"}," +
            "{\"name\":\"Tweakers Reviews\",\"url\":\"https://tweakers.net/feeds/reviews.xml\"}," +
            "{\"name\":\"Lobsters (en)\",\"url\":\"https://lobste.rs/rss\"}]";

    String sourcesJson() {
        return prefs.getString(KEY_SOURCES, DEFAULT_SOURCES);
    }

    boolean sourcesTouched() {
        return prefs.getBoolean(KEY_SOURCES_CUSTOM, false);
    }

    void setSources(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String name = o.optString("name", "").trim();
            String url = o.optString("url", "").trim();
            if (name.isEmpty()) throw new IllegalArgumentException("bron " + (i + 1) + " heeft geen naam");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("bron \"" + name + "\" heeft geen http(s)-adres");
            }
        }
        prefs.edit()
                .putString(KEY_SOURCES, a.toString())
                .putBoolean(KEY_SOURCES_CUSTOM, true)
                .apply();
    }

    void resetSources() {
        prefs.edit()
                .remove(KEY_SOURCES)
                .remove(KEY_SOURCES_CUSTOM)
                .apply();
    }

    List<Source> sources() throws Exception {
        JSONArray a = new JSONArray(sourcesJson());
        List<Source> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new Source(o.optString("name", "bron " + (i + 1)), o.optString("url", "")));
        }
        return out;
    }

    int activeSource() {
        return prefs.getInt(KEY_ACTIVE, 0);
    }

    void setActiveSource(int i) {
        prefs.edit().putInt(KEY_ACTIVE, i).apply();
    }

    int count() {
        return prefs.getInt(KEY_COUNT, 5);
    }

    void setCount(int n) {
        prefs.edit().putInt(KEY_COUNT, Math.max(1, Math.min(50, n))).apply();
    }

    // ---------------------------------------------------------------- archief

    static final class Archived {
        final String title;
        final String url;
        final String source;
        final long saved;

        Archived(String title, String url, String source, long saved) {
            this.title = title;
            this.url = url;
            this.source = source;
            this.saved = saved;
        }
    }

    private List<Archived> cache = null;

    List<Archived> archive() {
        if (cache != null) return cache;
        List<Archived> out = new ArrayList<>();
        if (archiveFile.exists()) {
            try (FileInputStream in = new FileInputStream(archiveFile)) {
                JSONArray a = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Archived(
                            o.optString("title", ""),
                            o.optString("url", ""),
                            o.optString("source", ""),
                            o.optLong("saved", 0)));
                }
            } catch (Exception e) {
                // Een kapot archief mag de app niet blokkeren; we beginnen opnieuw.
                out.clear();
            }
        }
        cache = out;
        return out;
    }

    private void flushArchive() throws Exception {
        List<Archived> list = archive();
        JSONArray a = new JSONArray();
        for (Archived e : list) {
            JSONObject o = new JSONObject();
            o.put("title", e.title);
            o.put("url", e.url);
            o.put("source", e.source);
            o.put("saved", e.saved);
            a.put(o);
        }
        try (FileOutputStream out = new FileOutputStream(archiveFile)) {
            OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write(a.toString());
            w.flush();
        }
    }

    boolean isArchived(String url) {
        for (Archived e : archive()) {
            if (e.url.equals(url)) return true;
        }
        return false;
    }

    /** @return true als het artikel er nu staat, false als het verwijderd is. */
    boolean toggleArchive(String title, String url, String source) throws Exception {
        List<Archived> list = archive();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).url.equals(url)) {
                list.remove(i);
                flushArchive();
                return false;
            }
        }
        list.add(0, new Archived(title, url, source, System.currentTimeMillis()));
        flushArchive();
        return true;
    }

    List<Archived> archiveSearch(String term) {
        String q = term == null ? "" : term.trim().toLowerCase();
        List<Archived> out = new ArrayList<>();
        for (Archived e : archive()) {
            if (q.isEmpty()
                    || e.title.toLowerCase().contains(q)
                    || e.source.toLowerCase().contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    void clearArchive() throws Exception {
        cache = new ArrayList<>();
        flushArchive();
    }
}
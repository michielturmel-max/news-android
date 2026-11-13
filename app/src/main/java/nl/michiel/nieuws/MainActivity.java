package nl.michiel.nieuws;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Enig activity. De structuur volgt het paneel van het plugin-script: een
 * bronregel bovenaan met tabs links en een hamburger rechts, daaronder de
 * koppenlijst. Tik op een kop opent het artikel in de app zelf, met de
 * volledige tekst; het hamburgermenu beheert bronnen en het archief.
 */
public class MainActivity extends Activity {

    private Store store;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private Spinner tabs;
    private ListView list;
    private TextView statusLine;
    private Button menuBtn;

    private final List<Headline> headlines = new ArrayList<>();
    private Future<?> pending;

    private boolean inArchive = false;
    private String searchTerm = "";
    private int editingSource = -1;      // -1 = nieuwe bron
    private int selectedHeadline = -1;

    // Kleuren uit het paneel: donker, accent in het oog.
    private static final int BG = 0xFF14161A;
    private static final int FG = 0xFFE8EAED;
    private static final int MUTED = 0xFF9AA0A6;
    private static final int ACCENT = 0xFF7FB2E5;
    private static final int LEAD = 0xFFB9BFC7;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        getWindow().setStatusBarColor(BG);
        setContentView(buildLayout());
        reloadSources();
        load();
    }

    // ------------------------------------------------------------------ layout

    private int dp(int n) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n,
                getResources().getDisplayMetrics());
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // --- bronregel: tabs links, hamburger rechts
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(4), dp(6));

        tabs = new Spinner(this);
        bar.addView(tabs, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        menuBtn = new Button(this);
        menuBtn.setText("☰");
        menuBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menuBtn.setBackgroundColor(0x00000000);
        menuBtn.setTextColor(FG);
        menuBtn.setPadding(dp(12), 0, dp(12), 0);
        menuBtn.setOnClickListener(this::showMenu);
        bar.addView(menuBtn);
        root.addView(bar);

        // --- statusregel
        statusLine = label("", 13, MUTED);
        statusLine.setPadding(dp(14), dp(2), dp(14), dp(8));
        root.addView(statusLine);

        // --- lijst
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setBackgroundColor(BG);
        list.setOnItemClickListener((p, v, pos, id) -> {
            selectedHeadline = pos;
            if (inArchive) {
                openUrl(headlines.get(pos).link);
            } else {
                openArticle(headlines.get(pos));
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            selectedHeadline = pos;
            if (!inArchive) toggleArchive(pos);
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        // Zelfde opties als het paneel: bronnen beheren, archief, zoeken.
        m.add(0, 1, 0, "Nieuwe bron");
        m.add(0, 2, 1, "Bron bewerken");
        m.add(0, 3, 2, "Bron verwijderen");
        m.add(0, 4, 3, "Aantal koppen: " + store.count());
        m.add(0, 5, 4, inArchive ? "Terug naar koppen" : "Archief (" + store.archive().size() + ")");
        if (inArchive) m.add(0, 6, 5, "Zoeken in archief");
        m.add(0, 7, 6, "Herladen");
        m.add(0, 8, 7, "Alles op standaard");
        pm.setOnMenuItemClickListener(this::onMenuItem);
        pm.show();
    }

    private boolean onMenuItem(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                editingSource = -1;
                showSourceDialog("Nieuwe bron", "", "");
                return true;
            case 2:
                if (tabsEmpty()) return true;
                editingSource = currentSourceIndex();
                List<Source> srcs = sourcesQuiet();
                if (editingSource < srcs.size()) {
                    Source s = srcs.get(editingSource);
                    showSourceDialog("Bron bewerken", s.name, s.url);
                }
                return true;
            case 3:
                removeCurrentSource();
                return true;
            case 4:
                askCount();
                return true;
            case 5:
                inArchive = !inArchive;
                searchTerm = "";
                load();
                return true;
            case 6:
                askSearch();
                return true;
            case 7:
                load();
                return true;
            case 8:
                store.resetSources();
                reloadSources();
                load();
                toast("Bronnen teruggezet op de standaard");
                return true;
            default:
                return false;
        }
    }

    private boolean tabsEmpty() {
        return currentSourceIndex() < 0;
    }

    // ------------------------------------------------------------------ bronnen

    private List<Source> sourcesQuiet() {
        try {
            return store.sources();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void reloadSources() {
        List<Source> srcs = sourcesQuiet();
        List<String> names = new ArrayList<>();
        for (Source s : srcs) names.add(s.name);
        ArrayAdapter<String> ad = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, names);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tabs.setAdapter(ad);

        int i = Math.min(store.activeSource(), Math.max(0, srcs.size() - 1));
        if (!srcs.isEmpty()) store.setActiveSource(i);
        tabs.setSelection(i);

        tabs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                store.setActiveSource(pos);
                // De eerste aanroep komt van setSelection() zelf en zou anders
                // twee keer ophalen bij het opstarten.
                if (first) {
                    first = false;
                    return;
                }
                load();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
    }

    private int currentSourceIndex() {
        List<Source> srcs = sourcesQuiet();
        int i = store.activeSource();
        return i >= 0 && i < srcs.size() ? i : -1;
    }

    private void showSourceDialog(String title, String name, String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        EditText nameIn = new EditText(this);
        nameIn.setHint("naam, bv. Tweakers");
        nameIn.setText(name);
        nameIn.setTextColor(FG);
        EditText urlIn = new EditText(this);
        urlIn.setHint("feed-adres, bv. https://example.nl/feed.xml");
        urlIn.setText(url);
        urlIn.setTextColor(FG);
        urlIn.setSingleLine(false);

        box.addView(label("Naam", 13, MUTED));
        box.addView(nameIn);
        box.addView(label("Feed-URL", 13, MUTED));
        box.addView(urlIn);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Opslaan", (d, w) ->
                        commitSource(nameIn.getText().toString().trim(),
                                urlIn.getText().toString().trim()))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void commitSource(String name, String url) {
        if (name.isEmpty()) {
            toast("Geef een naam op");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Het adres moet met http:// of https:// beginnen");
            return;
        }
        try {
            List<Source> srcs = sourcesQuiet();
            JSONArray a = new JSONArray();
            int idx = editingSource;
            if (idx < 0 || idx >= srcs.size()) {
                // toevoegen: achteraan
                for (Source s : srcs) a.put(obj(s.name, s.url));
                a.put(obj(name, url));
                idx = srcs.size();
            } else {
                for (int i = 0; i < srcs.size(); i++) {
                    Source s = srcs.get(i);
                    a.put(obj(i == idx ? name : s.name, i == idx ? url : s.url));
                }
            }
            store.setSources(a.toString());
            store.setActiveSource(idx);
            reloadSources();
            load();
            toast("Opgeslagen");
        } catch (Exception e) {
            toast("Niet opgeslagen: " + e.getMessage());
        }
    }

    private static JSONObject obj(String name, String url) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("url", url);
        return o;
    }

    private void removeCurrentSource() {
        List<Source> srcs = sourcesQuiet();
        int idx = currentSourceIndex();
        if (idx < 0) {
            toast("Geen bron geselecteerd");
            return;
        }
        if (srcs.size() <= 1) {
            toast("De laatste bron kan niet weg");
            return;
        }
        Source gone = srcs.get(idx);
        new AlertDialog.Builder(this)
                .setTitle("Bron verwijderen")
                .setMessage("\"" + gone.name + "\" weggooien?")
                .setPositiveButton("Verwijderen", (d, w) -> {
                    try {
                        JSONArray a = new JSONArray();
                        for (int i = 0; i < srcs.size(); i++) {
                            if (i == idx) continue;
                            Source s = srcs.get(i);
                            a.put(obj(s.name, s.url));
                        }
                        store.setSources(a.toString());
                        store.setActiveSource(Math.max(0, idx - 1));
                        reloadSources();
                        load();
                        toast("Verwijderd");
                    } catch (Exception e) {
                        toast("Fout: " + e.getMessage());
                    }
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void askCount() {
        final EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setText(String.valueOf(store.count()));
        new AlertDialog.Builder(this)
                .setTitle("Aantal koppen")
                .setView(in)
                .setPositiveButton("Ok", (d, w) -> {
                    try {
                        store.setCount(Integer.parseInt(in.getText().toString().trim()));
                        load();
                    } catch (Exception e) {
                        toast("Geen geldig getal");
                    }
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void askSearch() {
        final EditText in = new EditText(this);
        in.setHint("zoekwoord");
        in.setText(searchTerm);
        new AlertDialog.Builder(this)
                .setTitle("Zoeken in archief")
                .setView(in)
                .setPositiveButton("Zoeken", (d, w) -> {
                    searchTerm = in.getText().toString().trim();
                    load();
                })
                .setNegativeButton("Annuleren", null)
                .setNeutralButton("Wissen", (d, w) -> {
                    searchTerm = "";
                    load();
                })
                .show();
    }

    // ------------------------------------------------------------------ laden

    private void load() {
        if (pending != null) pending.cancel(true);
        if (inArchive) {
            loadArchive();
            return;
        }

        List<Source> srcs = sourcesQuiet();
        if (srcs.isEmpty()) {
            status("Geen bronnen. ☰ → Nieuwe bron.");
            headlines.clear();
            list.setAdapter(new HeadlineAdapter());
            return;
        }

        final int idx = currentSourceIndex();
        final Source src = idx >= 0 ? srcs.get(idx) : srcs.get(0);
        final int want = store.count();

        headlines.clear();
        list.setAdapter(new HeadlineAdapter());
        status("Bezig met ophalen van " + src.name + "…");

        pending = pool.submit(() -> {
            Fetcher.Result r = Fetcher.fetch(src.url, src.url);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (r.error != null) {
                    status("Fout bij " + src.name + ": " + r.error);
                } else if (r.feed.items.isEmpty()) {
                    status("Geen koppen in " + src.name
                            + (r.feed.error != null ? " (" + r.feed.error + ")" : ""));
                } else {
                    int n = Math.min(want, r.feed.items.size());
                    for (int i = 0; i < n; i++) headlines.add(r.feed.items.get(i));
                    status(src.name + " · " + n + " van " + r.feed.items.size()
                            + " · tik voor het artikel, lang drukken slaat op");
                }
                list.setAdapter(new HeadlineAdapter());
            });
        });
    }

    private void loadArchive() {
        List<Store.Archived> all = store.archive();
        List<Store.Archived> shown = store.archiveSearch(searchTerm);
        headlines.clear();
        for (Store.Archived a : shown) {
            headlines.add(new Headline(a.title, a.url, "", a.source));
        }
        list.setAdapter(new HeadlineAdapter());
        status(shown.size() + " van " + all.size() + " opgeslagen artikelen"
                + (searchTerm.isEmpty() ? " · ☰ om te zoeken" : " · zoek: " + searchTerm));
    }

    // ------------------------------------------------------------------ archief

    private void toggleArchive(int pos) {
        Headline h = headlines.get(pos);
        try {
            boolean nowSaved = store.toggleArchive(h.title, h.link, currentSourceName());
            toast(nowSaved ? "Opgeslagen in archief" : "Uit archief verwijderd");
            list.setAdapter(new HeadlineAdapter());
            load();
        } catch (Exception e) {
            toast("Kon niet opslaan: " + e.getMessage());
        }
    }

    private String currentSourceName() {
        List<Source> srcs = sourcesQuiet();
        int i = currentSourceIndex();
        return i >= 0 ? srcs.get(i).name : "";
    }

    // ------------------------------------------------------------------ artikel

    /**
     * Haalt de volledige tekst op en toont die in een scrollvenster. Dit is
     * wat de plugin deed en wat de eerste versie niet deed; het verschil zat
     * in het volledige artikel tegenover de feed-samenvatting.
     */
    private void openArticle(Headline h) {
        final View box = articlePlaceholder(h.title);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(h.title)
                .setView(box)
                .setPositiveButton("Openen", (d, w) -> openUrl(h.link))
                .setNeutralButton("Bewaren", (d, w) -> saveCurrent())
                .setNegativeButton("Sluiten", null)
                .create();
        dlg.show();

        final TextView body = (TextView) box.findViewWithTag("body");
        final TextView meta = (TextView) box.findViewWithTag("meta");
        body.setText("Laden…");

        pending = pool.submit(() -> {
            PageFetcher.Result page = PageFetcher.fetch(h.link, h.link);
            Article.Parsed art = page.error != null
                    ? new Article.Parsed()
                    : Article.parse(page.html);
            if (page.error != null) {
                art.ok = false;
                art.reason = page.error;
            }
            final Article.Parsed fa = art;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!fa.ok) {
                    meta.setTextColor(MUTED);
                    meta.setText("Volledige tekst niet beschikbaar: " + fa.reason);
                    body.setText(h.summary.isEmpty()
                            ? "Geen samenvatting in de feed. Tik op Openen voor de website."
                            : h.summary);
                    return;
                }
                StringBuilder m = new StringBuilder();
                if (!fa.author.isEmpty()) m.append(fa.author).append(" · ");
                m.append(fa.date);
                if (!fa.comments.isEmpty()) m.append(" · ").append(fa.comments);
                meta.setTextColor(MUTED);
                meta.setText(m.toString());

                StringBuilder txt = new StringBuilder();
                for (Article.Block b : fa.blocks) {
                    if (b.isHeading()) {
                        txt.append("\n").append(b.text).append("\n");
                    } else {
                        txt.append(b.text).append("\n\n");
                    }
                }
                body.setText(txt.toString().trim());
                if (!fa.title.isEmpty() && !fa.title.equals(h.title)) {
                    dlg.setTitle(fa.title);
                }
            });
        });
    }

    private View articlePlaceholder(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        TextView meta = label("", 12, MUTED);
        meta.setTag("meta");
        box.addView(meta);

        TextView body = label("", 16, FG);
        body.setTag("body");
        body.setLineSpacing(dp(4), 1.15f);
        box.addView(body);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(sc);
        return wrap;
    }

    private void saveCurrent() {
        int pos = selectedHeadline;
        if (pos < 0 || pos >= headlines.size()) return;
        Headline h = headlines.get(pos);
        try {
            boolean nowSaved = store.toggleArchive(h.title, h.link, currentSourceName());
            toast(nowSaved ? "Opgeslagen in archief" : "Uit archief verwijderd");
        } catch (Exception e) {
            toast("Kon niet opslaan: " + e.getMessage());
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Geen browser: " + e.getMessage());
        }
    }

    private void status(String s) {
        statusLine.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pending != null) pending.cancel(true);
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ adapter

    private final class HeadlineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return headlines.size();
        }

        @Override
        public Headline getItem(int i) {
            return headlines.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) v = newItemView();
            Headline h = headlines.get(i);

            TextView t = v.findViewWithTag("title");
            TextView d = v.findViewWithTag("meta");

            t.setText(h.title);
            if (inArchive) {
                d.setText(h.summary);
            } else if (!h.date.isEmpty()) {
                d.setText(h.date);
            } else {
                d.setText("");
            }
            d.setVisibility(d.getText().length() == 0 ? View.GONE : View.VISIBLE);
            return v;
        }

        private View newItemView() {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(8), dp(14), dp(8));

            TextView t = new TextView(MainActivity.this);
            t.setTag("title");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(FG);
            t.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            t.setLineSpacing(0, 1.1f);

            TextView d = new TextView(MainActivity.this);
            d.setTag("meta");
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            d.setTextColor(MUTED);
            d.setPadding(0, dp(3), 0, 0);

            box.addView(t);
            box.addView(d);
            return box;
        }
    }
}
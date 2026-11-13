package nl.michiel.nieuws;

/** Eén kop uit een feed. */
final class Headline {
    final String title;
    final String link;
    final String date;
    final String summary;

    Headline(String title, String link, String date, String summary) {
        this.title = title;
        this.link = link;
        this.date = date;
        this.summary = summary;
    }
}
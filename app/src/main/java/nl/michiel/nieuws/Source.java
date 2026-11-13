package nl.michiel.nieuws;

/** Een bron uit sources.json. YouTube-kanalen zijn bewust weggelaten. */
final class Source {
    final String name;
    final String url;

    Source(String name, String url) {
        this.name = name;
        this.url = url;
    }

    @Override
    public String toString() {
        return name;
    }
}
package org.openstreetmap.josm.plugins.chainpoi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;

/**
 * АЗС «Нефтьмагистраль»: карта сети на neftm.ru.
 *
 * <p>Станции лежат в HTML одной страницы: адрес, координаты, номер АЗС
 * и список топлива в {@code data-checklist}. Отдельного API нет.
 */
public final class NeftmagistralProvider implements ChainProvider {

    private static final String URL = "https://neftm.ru/";
    private static final String CACHE_PREFIX = "chainpoi-neftmagistral";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "neftmagistral";
    private static final String CHAIN = "Нефтьмагистраль";

    private static final Pattern ITEM = Pattern.compile(
            "<div[^>]*class=\"[^\"]*map-filter__station-item[^\"]*\"[^>]*>",
            Pattern.DOTALL);
    private static final Pattern ADDRESS = Pattern.compile(
            "<p[^>]*class=\"[^\"]*map-filter__station-address[^\"]*\"[^>]*>(.*?)</p>",
            Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("id=\"gs(\\d+)\"");
    private static final Pattern LAT = Pattern.compile("data-lat=\"([^\"]+)\"");
    private static final Pattern LNG = Pattern.compile("data-lng=\"([^\"]+)\"");
    private static final Pattern CHECKLIST = Pattern.compile("data-checklist=\"([^\"]*)\"");
    private static final Pattern STATION_NUMBER = Pattern.compile(
            "(?iu)^АЗС\\s*№?\\s*(\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Consumer<String> progress = s -> { };
    private volatile boolean allowDownload = true;

    @Override
    public String id() {
        return CHAIN_ID;
    }

    @Override
    public String name() {
        return CHAIN;
    }

    public void setProgress(Consumer<String> progress) {
        this.progress = progress == null ? s -> { } : progress;
    }

    public void setAllowDownload(boolean allow) {
        this.allowDownload = allow;
    }

    public boolean hasFreshCache(Bounds bbox) {
        return AreaCache.hasFresh(CACHE_PREFIX, CACHE_NAME, bbox, TTL_MS);
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        return AreaCache.fetch(CACHE_PREFIX, CACHE_NAME, CHAIN_ID, CHAIN, "fuel",
                bbox, TTL_MS, () -> allowDownload ? downloadAll() : null, true);
    }

    private List<Store> downloadAll() throws Exception {
        progress.accept("Нефтьмагистраль: загрузка списка АЗС");
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.3")
                .header("Accept", "text/html")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Нефтьмагистраль: HTTP " + resp.statusCode());
        }
        List<Store> out = new ArrayList<>();
        Matcher item = ITEM.matcher(resp.body());
        while (item.find()) {
            String tag = item.group();
            int limit = Math.min(resp.body().length(), item.end() + 5000);
            Matcher addressMatcher = ADDRESS.matcher(resp.body().substring(item.end(), limit));
            if (!addressMatcher.find()) {
                continue;
            }
            String address = text(addressMatcher.group(1));
            String id = group(ID, tag);
            String latText = group(LAT, tag);
            String lonText = group(LNG, tag);
            if (id == null || latText == null || lonText == null) {
                continue;
            }
            double lat;
            double lon;
            try {
                lat = Double.parseDouble(latText);
                lon = Double.parseDouble(lonText);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (lat == 0 || lon == 0) {
                continue;
            }
            Map<String, String> tags = tags();
            String ref = stationNumber(address);
            if (ref != null) {
                tags.put("ref", ref);
            }
            String checklist = group(CHECKLIST, tag);
            if (checklist != null) {
                for (String fuel : checklist.split("\\s+")) {
                    addFuel(tags, fuel);
                }
            }
            out.add(new Store(CHAIN_ID, CHAIN, id, address, lat, lon, "fuel", tags));
        }
        progress.accept("Нефтьмагистраль: найдено " + out.size() + " АЗС");
        return out;
    }

    private static void addFuel(Map<String, String> tags, String token) {
        switch (token) {
            case "ai80":
                FuelTags.add(tags, "АИ-80");
                break;
            case "ai92":
            case "ai92e":
                FuelTags.add(tags, "АИ-92");
                break;
            case "ai95":
            case "ai95e":
                FuelTags.add(tags, "АИ-95");
                break;
            case "ai98":
                FuelTags.add(tags, "АИ-98");
                break;
            case "ai100":
                FuelTags.add(tags, "АИ-100");
                break;
            case "dt":
            case "dtevro":
                FuelTags.add(tags, "ДТ");
                break;
            case "kpg":
                FuelTags.add(tags, "КПГ");
                break;
            case "sug":
                FuelTags.add(tags, "СУГ");
                break;
            default:
                break;
        }
    }

    private static String stationNumber(String address) {
        Matcher m = STATION_NUMBER.matcher(address);
        return m.find() ? m.group(1) : null;
    }

    private static String group(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String text(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "Neftmagistral");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "Neftmagistral");
        tags.put("brand:wikidata", "Q62544323");
        return tags;
    }
}

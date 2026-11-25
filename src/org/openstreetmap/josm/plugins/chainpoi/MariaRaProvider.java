package org.openstreetmap.josm.plugins.chainpoi;

import java.io.StringReader;
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

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * «Мария-Ра»: карта сети на maria-ra.ru.
 *
 * <p>Все точки лежат прямо в HTML страницы «Карта сети» в виде
 * {@code JSON.parse('[...]')}: NAME, COORDINATE, часы работы, SECTION.
 */
public final class MariaRaProvider implements ChainProvider {

    private static final String PAGE = "https://www.maria-ra.ru/o-kompanii/karta-seti/";
    private static final String CACHE_PREFIX = "chainpoi-maria-ra";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "maria-ra";
    private static final String CHAIN = "Мария-Ра";

    private static final Pattern OBJECTS = Pattern.compile(
            "\\$objects\\s*=\\s*JSON\\.parse\\('(.*?)'\\);", Pattern.DOTALL);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Consumer<String> progress = s -> { };
    private volatile boolean allowDownload;

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
        return AreaCache.fetch(CACHE_PREFIX, CACHE_NAME, CHAIN_ID, CHAIN, "supermarket",
                bbox, TTL_MS, () -> allowDownload ? downloadAll() : null, true);
    }

    private List<Store> downloadAll() throws Exception {
        progress.accept("Мария-Ра: загрузка карты сети");
        HttpRequest req = HttpRequest.newBuilder(URI.create(PAGE))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Мария-Ра: HTTP " + resp.statusCode());
        }
        Matcher m = OBJECTS.matcher(resp.body());
        if (!m.find()) {
            throw new IllegalStateException("Мария-Ра: не найден список магазинов в HTML");
        }
        String raw = m.group(1).replace("\\'", "'");
        List<Store> out = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            JsonArray objects = reader.readArray();
            int index = 0;
            for (JsonValue v : objects) {
                JsonObject o = v.asJsonObject();
                String address = o.getString("NAME", "").trim();
                String[] ll = o.getString("COORDINATE", "").split(",");
                if (ll.length != 2) {
                    continue;
                }
                double lat;
                double lon;
                try {
                    lat = Double.parseDouble(ll[0].trim());
                    lon = Double.parseDouble(ll[1].trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                Map<String, String> tags = new LinkedHashMap<>();
                tags.put("shop", "supermarket");
                tags.put("name", CHAIN);
                tags.put("name:ru", CHAIN);
                tags.put("name:en", "Maria-Ra");
                tags.put("brand", CHAIN);
                tags.put("brand:ru", CHAIN);
                tags.put("brand:en", "Maria-Ra");
                tags.put("brand:wikidata", "Q4281631");
                String hours = openingHours(o.getString("STARTED_WORK", ""),
                        o.getString("END_WORK", ""));
                if (hours != null) {
                    tags.put("opening_hours", hours);
                }
                index++;
                out.add(new Store(CHAIN_ID, CHAIN, "MR-" + index, address, lat, lon,
                        "supermarket", tags));
            }
        }
        progress.accept("Мария-Ра: найдено " + out.size() + " магазинов");
        return out;
    }

    private static String openingHours(String start, String end) {
        String a = hhmm(start);
        String b = hhmm(end);
        if (a == null || b == null || ("00:00".equals(a) && "00:00".equals(b))) {
            return null;
        }
        return "Mo-Su " + a + "-" + b;
    }

    private static String hhmm(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (!s.matches("^\\d{1,2}:\\d{2}$")) {
            return null;
        }
        String[] p = s.split(":");
        return String.format("%02d:%s", Integer.parseInt(p[0]), p[1]);
    }
}

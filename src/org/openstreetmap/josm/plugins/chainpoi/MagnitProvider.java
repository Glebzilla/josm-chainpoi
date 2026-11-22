package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;

/**
 * Магазины сети «Магнит» из внутреннего API magnit.ru.
 *
 * <p>API открыт, без авторизации: POST /webgate/v1/stores-facade/search/detail
 * с прямоугольником и списком форматов.
 */
public final class MagnitProvider implements ChainProvider {

    private static final String ENDPOINT = "https://magnit.ru/webgate/v1/stores-facade/search/detail";

    /** Все форматы, которые отдаёт API. */
    private static final List<String> ALL_TYPES = Arrays.asList(
            "MM", "GM", "DG", "MO", "ME", "MC", "DARKSTORE", "MM_MINI", "ZARYAD",
            "MAGNIT_MARKET", "DISTR_PHARMACY");

    /**
     * Что считать магазином какой сети. Остальные форматы (DARKSTORE — Мигом,
     * ZARYAD, MO, ME) пропускаются, чтобы не создать неверный объект.
     */
    private static final Map<String, String> SHOP_TYPE = Map.of(
            "MM", "supermarket",
            "MC", "supermarket",
            "GM", "supermarket",
            "MM_MINI", "convenience",
            "DG", "chemist",
            "MAGNIT_MARKET", "supermarket",
            "DISTR_PHARMACY", "pharmacy");

    private static final String CHAIN = "Магнит";
    private static final String CHAIN_ID = "magnit";
    private static final String KOSMETIK = "М.Косметик";
    private static final String KOSMETIK_ID = "magnit-kosmetik";
    private static final String BRAND_KOSMETIK = "Магнит Косметик";
    private static final String PHARMACY = "Магнит Аптека";
    private static final String PHARMACY_ID = "magnit-pharmacy";
    private static final String BRAND_PHARMACY = "Магнит Аптека";

    /** Форматы М.Косметик: отдельная сеть, отдельная разметка. */
    private static boolean isCosmeticType(String type) {
        return "DG".equals(type);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String id() {
        return "magnit";
    }

    @Override
    public String name() {
        return CHAIN;
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        List<Store> out = new ArrayList<>();
        int offset = 0;
        int size = 50;
        while (offset < 2000) {
            JsonObject resp = post(bbox, offset, size);
            JsonArray data = resp.getJsonArray("data");
            if (data == null || data.isEmpty()) {
                break;
            }
            for (JsonValue v : data) {
                Store s = toStore(v.asJsonObject());
                if (s != null) {
                    out.add(s);
                }
            }
            offset += size;
            int total = resp.getInt("totalCount", -1);
            if (total >= 0 && offset >= total) {
                break;
            }
        }
        return out;
    }

    private JsonObject post(Bounds b, int offset, int size) throws Exception {
        JsonObject geo = Json.createObjectBuilder()
                .add("typeName", "box")
                .add("leftTopPoint", Json.createObjectBuilder()
                        .add("latitude", b.getMaxLat())
                        .add("longitude", b.getMinLon()))
                .add("rightBottomPoint", Json.createObjectBuilder()
                        .add("latitude", b.getMinLat())
                        .add("longitude", b.getMaxLon()))
                .build();

        JsonObject body = Json.createObjectBuilder()
                .add("filters", Json.createObjectBuilder()
                        .add("geo", geo)
                        .add("storeTypeListV2", Json.createArrayBuilder(ALL_TYPES)))
                .add("pagination", Json.createObjectBuilder()
                        .add("offset", offset)
                        .add("size", size))
                .add("sorting", Json.createObjectBuilder()
                        .add("sortBy", "SORT_BY_CITY")
                        .add("sortType", "SORT_TYPE_ASC"))
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String encoding = resp.headers().firstValue("Content-Encoding").orElse("");
        InputStream bodyStream = resp.body();
        if (encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            bodyStream = new GZIPInputStream(bodyStream);
        }
        try (InputStream in = bodyStream;
             JsonReader reader = Json.createReader(in)) {
            return reader.readObject();
        }
    }

    private Store toStore(JsonObject o) {
        String type = o.getString("storeTypeV2", "");
        String shop = SHOP_TYPE.get(type);
        if (shop == null) {
            return null;
        }
        JsonObject coords = o.getJsonObject("coordinates");
        if (coords == null) {
            return null;
        }
        double lat = coords.getJsonNumber("latitude").doubleValue();
        double lon = coords.getJsonNumber("longitude").doubleValue();

        String code = "";
        JsonObject ext = o.getJsonObject("externalId");
        if (ext != null) {
            code = ext.getString("storeCode", "");
        }

        boolean cosmetic = isCosmeticType(type);
        boolean pharmacy = "DISTR_PHARMACY".equals(type);
        Map<String, String> tags = new LinkedHashMap<>();
        if (pharmacy) {
            tags.put("amenity", "pharmacy");
        } else {
            tags.put("shop", shop);
        }
        if (cosmetic) {
            tags.put("name", KOSMETIK);
            tags.put("name:ru", KOSMETIK);
            tags.put("brand", BRAND_KOSMETIK);
        } else if (pharmacy) {
            tags.put("name", PHARMACY);
            tags.put("name:ru", PHARMACY);
            tags.put("brand", BRAND_PHARMACY);
        } else {
            tags.put("name", CHAIN);
            tags.put("name:ru", CHAIN);
            tags.put("name:en", "Magnit");
            tags.put("brand", CHAIN);
        }
        if (pharmacy) {
            tags.put("brand:wikidata", "Q120262563");
        } else {
            tags.put("brand:wikidata", "Q940518");
            tags.put("operator", "АО «Тандер»");
        }
        String hours = openingHours(o);
        if (hours != null) {
            tags.put("opening_hours", hours);
        }
        String chainId = cosmetic ? KOSMETIK_ID : pharmacy ? PHARMACY_ID : CHAIN_ID;
        String chainName = cosmetic ? KOSMETIK : pharmacy ? PHARMACY : CHAIN;
        return new Store(chainId, chainName, code, o.getString("address", ""),
                lat, lon, type, tags);
    }

    /**
     * Собирает opening_hours из недельного расписания.
     */
    private static String openingHours(JsonObject store) {
        JsonArray list = store.getJsonArray("timetableList");
        if (list == null) {
            return null;
        }
        for (JsonValue v : list) {
            JsonObject t = v.asJsonObject();
            if (!"WORKING_TIMETABLE_TYPE_STORE".equals(t.getString("key", ""))) {
                continue;
            }
            JsonObject val = t.getJsonObject("value");
            if (val == null) {
                return null;
            }
            if (val.getBoolean("is24hOpen", false)) {
                return "24/7";
            }
            JsonObject ws = val.getJsonObject("weeklySchedule");
            if (ws == null) {
                return null;
            }
            String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            String[] abbr = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};
            String[] spec = new String[7];
            for (int i = 0; i < days.length; i++) {
                JsonObject day = ws.getJsonObject(days[i]);
                if (day == null) {
                    return null;
                }
                if (day.getBoolean("isDayOff", false)) {
                    spec[i] = null;
                    continue;
                }
                JsonObject ds = day.getJsonObject("dailySchedule");
                if (ds == null) {
                    return null;
                }
                spec[i] = ds.getString("openingTime", "") + "-" + ds.getString("closingTime", "");
            }
            boolean allSame = spec[0] != null;
            for (int i = 1; i < spec.length && allSame; i++) {
                allSame = spec[i] != null && spec[i].equals(spec[0]);
            }
            if (allSame) {
                return "Mo-Su " + spec[0];
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < spec.length; i++) {
                if (spec[i] == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(abbr[i]).append(' ').append(spec[i]);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }
}

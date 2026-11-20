import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/** Печатает типы магазинов и адреса, чтобы понять, чем MO/ME отличаются от DG. */
public class TestTypes {

    public static void main(String[] args) throws Exception {
        String body = "{\"filters\":{\"geo\":{\"typeName\":\"box\","
                + "\"leftTopPoint\":{\"latitude\":82.00,\"longitude\":19.00},"
                + "\"rightBottomPoint\":{\"latitude\":41.00,\"longitude\":180.00}},"
                + "\"storeTypeListV2\":[\"ME\",\"MO\"]},"
                + "\"pagination\":{\"offset\":0,\"size\":100},"
                + "\"sorting\":{\"sortBy\":\"SORT_BY_CITY\",\"sortType\":\"SORT_TYPE_ASC\"}}";

        HttpClient http = HttpClient.newBuilder().build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://magnit.ru/webgate/v1/stores-facade/search/detail"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 JOSM-chainpoi/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String enc = resp.headers().firstValue("Content-Encoding").orElse("");
        InputStream in = resp.body();
        if (enc.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            in = new GZIPInputStream(in);
        }
        JsonObject root;
        try (InputStream s = in; JsonReader r = Json.createReader(s)) {
            root = r.readObject();
        }
        System.out.println("total=" + root.getInt("totalCount", -1));
        for (JsonValue v : root.getJsonArray("data")) {
            JsonObject o = v.asJsonObject();
            System.out.println(o.getString("storeTypeV2", "?") + " | "
                    + o.getString("storeType", "?") + " | "
                    + o.getString("address", "") + " | "
                    + o.getString("status", ""));
        }
    }
}

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/** Печатает сырой JSON первого магазина, чтобы увидеть все поля API. */
public class TestDump {

    public static void main(String[] args) throws Exception {
        String body = "{\"filters\":{\"geo\":{\"typeName\":\"box\","
                + "\"leftTopPoint\":{\"latitude\":45.46,\"longitude\":40.84},"
                + "\"rightBottomPoint\":{\"latitude\":45.44,\"longitude\":40.86}},"
                + "\"storeTypeListV2\":[\"MM\",\"GM\",\"DG\",\"MO\",\"ME\",\"MC\",\"DARKSTORE\",\"MM_MINI\",\"ZARYAD\"]},"
                + "\"pagination\":{\"offset\":0,\"size\":5},"
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

        Path out = Path.of(System.getProperty("java.io.tmpdir"), "magnit-store.json");
        Files.writeString(out, root.getJsonArray("data").getJsonObject(0).toString(),
                StandardCharsets.UTF_8);
        System.out.println("keys: " + root.keySet());
        System.out.println("written: " + out);
        for (JsonValue v : root.getJsonArray("data")) {
            JsonObject o = v.asJsonObject();
            System.out.println("--- " + o.getString("storeTypeV2", "?") + " "
                    + o.getJsonObject("externalId") + " ---");
            System.out.println("  address=" + o.getString("address", ""));
        }
    }
}

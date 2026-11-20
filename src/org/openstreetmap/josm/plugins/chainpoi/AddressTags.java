package org.openstreetmap.josm.plugins.chainpoi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Geometry;

/**
 * Адресные теги для магазина.
 *
 * <p>Берутся из здания, внутри которого стоит точка (там адрес уже проверен
 * мапперами), иначе разбираются из строки адреса сети.
 */
public final class AddressTags {

    /** Суффиксы типов улиц, как их пишут в OSM: «Расшеватская улица». */
    private static final String[][] STREET_TYPES = {
            {"ул", "улица"}, {"улица", "улица"},
            {"пр-т", "проспект"}, {"просп", "проспект"}, {"проспект", "проспект"},
            {"пер", "переулок"}, {"переулок", "переулок"},
            {"ш", "шоссе"}, {"шоссе", "шоссе"},
            {"б-р", "бульвар"}, {"бульвар", "бульвар"},
            {"наб", "набережная"}, {"набережная", "набережная"},
            {"пл", "площадь"}, {"площадь", "площадь"},
            {"проезд", "проезд"}, {"туп", "тупик"}, {"тупик", "тупик"},
    };

    private static final Pattern HOUSE = Pattern.compile("^(?:д|дом)\\.?\\s*(\\S+)$");

    public final String street;
    public final String housenumber;

    private AddressTags(String street, String housenumber) {
        this.street = street;
        this.housenumber = housenumber;
    }

    public static AddressTags derive(Store store, DataSet ds) {
        AddressTags fromBuilding = fromBuilding(store.lat, store.lon, ds);
        if (fromBuilding != null) {
            return fromBuilding;
        }
        return parse(store.address);
    }

    /** Адрес здания, внутри которого лежит точка. */
    private static AddressTags fromBuilding(double lat, double lon, DataSet ds) {
        if (ds == null) {
            return null;
        }
        Node probe = new Node(new LatLon(lat, lon));
        for (Way w : ds.getWays()) {
            if (w.isDeleted() || w.isIncomplete() || !w.isClosed()) {
                continue;
            }
            if (!w.hasKey("building") && !w.hasKey("building:part")) {
                continue;
            }
            if (!w.hasKey("addr:street") && !w.hasKey("addr:housenumber")) {
                continue;
            }
            try {
                if (!Geometry.nodeInsidePolygon(probe, w.getNodes())) {
                    continue;
                }
            } catch (RuntimeException e) {
                continue;
            }
            return new AddressTags(w.get("addr:street"), w.get("addr:housenumber"));
        }
        return null;
    }

    /** Разбор строки вида «Краснодарский край, ст-ца ..., ул Расшеватская, д 95». */
    static AddressTags parse(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        String street = null;
        String house = null;
        for (String raw : address.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            String low = part.toLowerCase(Locale.ROOT);
            if (street == null) {
                for (String[] type : STREET_TYPES) {
                    String prefix = type[0];
                    if (!low.startsWith(prefix + " ") && !low.startsWith(prefix + ". ")) {
                        continue;
                    }
                    String name = part.substring(prefix.length()).trim();
                    if (name.startsWith(".")) {
                        name = name.substring(1).trim();
                    }
                    if (!name.isEmpty()) {
                        street = name + " " + type[1];
                    }
                    break;
                }
            }
            if (house == null) {
                Matcher m = HOUSE.matcher(low);
                if (m.matches()) {
                    house = m.group(1);
                } else if (low.matches("\\d+[а-яa-z]?")) {
                    house = part;
                }
            }
        }
        if (street == null && house == null) {
            return null;
        }
        return new AddressTags(street, house);
    }

    /** Теги адреса, которых не хватает в карте тегов. */
    public void putInto(Map<String, String> tags) {
        if (street != null && !tags.containsKey("addr:street")) {
            tags.put("addr:street", street);
        }
        if (housenumber != null && !tags.containsKey("addr:housenumber")) {
            tags.put("addr:housenumber", housenumber);
        }
    }

    public static Map<String, String> emptyMap() {
        return new LinkedHashMap<>();
    }
}

package org.openstreetmap.josm.plugins.chainpoi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Один магазин, полученный от сети.
 */
public final class Store {

    /** Идентификатор сети, например {@code magnit}. */
    public final String chainId;
    /** Человекочитаемое имя сети, например {@code Магнит}. */
    public final String chainName;
    /** Код магазина внутри сети. */
    public final String code;
    /** Адрес так, как его отдаёт сеть. */
    public final String address;
    public final double lat;
    public final double lon;
    /** Формат магазина в терминах сети, например {@code MM} или {@code MC}. */
    public final String format;
    /** Готовые теги OSM. */
    public final Map<String, String> tags;

    public Store(String chainId, String chainName, String code, String address,
                 double lat, double lon, String format, Map<String, String> tags) {
        this.chainId = chainId;
        this.chainName = chainName;
        this.code = code;
        this.address = address;
        this.lat = lat;
        this.lon = lon;
        this.format = format;
        this.tags = new LinkedHashMap<>(tags);
    }

    public String displayName() {
        String n = tags.get("name");
        return n == null ? chainName : n;
    }

    /** М.Косметик — отдельная сеть, хоть и из той же группы. */
    public boolean isCosmetic() {
        return "magnit-kosmetik".equals(chainId);
    }

    /** Как магазин называется в обиходе: «Магнит», «Магнит «Моя цена»». */
    public String formatLabel() {
        if (isCosmetic()) {
            return chainName;
        }        switch (format) {
            case "MC":
                return "Магнит «Моя цена»";
            case "GM":
                return "Магнит Семейный";
            case "MM_MINI":
                return "Магнит Мини";
            default:
                return chainName;
        }
    }

    @Override
    public String toString() {
        return chainName + " (" + code + ") " + address;
    }
}

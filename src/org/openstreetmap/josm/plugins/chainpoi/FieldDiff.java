package org.openstreetmap.josm.plugins.chainpoi;

/**
 * Одно расхождение между данными сети и объектом OSM.
 */
public final class FieldDiff {

    public enum Kind {
        /** Тега в OSM нет. */
        MISSING,
        /** Тег есть, но значение другое. */
        DIFFERENT
    }

    public final String key;
    /** Значение в OSM, {@code null} если тега нет. */
    public final String osmValue;
    /** Значение из данных сети. */
    public final String chainValue;
    public final Kind kind;

    /** Отмечено ли поле к применению. По умолчанию все отмечены. */
    public boolean accepted = true;

    public FieldDiff(String key, String osmValue, String chainValue, Kind kind) {
        this.key = key;
        this.osmValue = osmValue;
        this.chainValue = chainValue;
        this.kind = kind;
    }

    public String osmText() {
        return osmValue == null ? "—" : osmValue;
    }

    public String chainText() {
        return chainValue == null ? "—" : chainValue;
    }

    /** Что произойдёт, если применить это поле. */
    public String changeText() {
        if (kind == Kind.MISSING) {
            return "добавить: " + chainText();
        }
        return osmText() + " → " + chainText();
    }
}

package org.openstreetmap.josm.plugins.chainpoi;

import java.util.ArrayList;
import java.util.List;

import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Таблица результатов сверки: одна строка — один магазин сети или объект в OSM.
 */
public class ReviewTableModel extends AbstractTableModel {

    /** Строка таблицы. */
    public static final class Row {
        /** Магазин сети; {@code null} для строки «в OSM есть, у сети нет». */
        public final Store store;
        public Match match;
        public boolean selected;

        public Row(Store store, Match match) {
            this.store = store;
            this.match = match;
            this.selected = match != null
                    && (match.status == Match.Status.MISSING || match.status == Match.Status.DIFF);
        }

        public String title() {
            return store != null ? store.formatLabel() : "Магнит (в OSM)";
        }

        public String address() {
            if (store != null) {
                return store.address;
            }
            OsmPrimitive p = match == null ? null : match.primitive;
            if (p == null) {
                return "";
            }
            String street = p.get("addr:street");
            String house = p.get("addr:housenumber");
            if (street == null && house == null) {
                return "";
            }
            return (street == null ? "" : street) + (house == null ? "" : ", " + house);
        }

        public String objectRef() {
            OsmPrimitive p = match == null ? null : match.primitive;
            if (p == null) {
                return "";
            }
            return ref(p);
        }
    }

    private static final String[] COLUMNS = {"", "Статус", "Сеть", "Адрес", "м", "Объект OSM"};

    private final List<Row> rows = new ArrayList<>();

    public void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) {
            rows.addAll(newRows);
        }
        fireTableDataChanged();
    }

    public List<Row> getRows() {
        return rows;
    }

    public Row getRow(int index) {
        return index < 0 || index >= rows.size() ? null : rows.get(index);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 0;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Row r = rows.get(row);
        switch (column) {
            case 0:
                return r.selected;
            case 1:
                return r.match == null ? "" : r.match.text();
            case 2:
                return r.title();
            case 3:
                return r.address();
            case 4:
                return r.match == null || r.match.distance < 0
                        ? "" : String.valueOf(Math.round(r.match.distance));
            default:
                return r.objectRef();
        }
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column == 0) {
            rows.get(row).selected = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, column);
        }
    }

    public static String ref(OsmPrimitive p) {
        if (p == null) {
            return "";
        }
        if (p.getId() <= 0) {
            return "новый";
        }
        if (p instanceof Node) {
            return "node/" + p.getId();
        }
        if (p instanceof Way) {
            return "way/" + p.getId();
        }
        if (p instanceof Relation) {
            return "rel/" + p.getId();
        }
        return String.valueOf(p.getId());
    }
}

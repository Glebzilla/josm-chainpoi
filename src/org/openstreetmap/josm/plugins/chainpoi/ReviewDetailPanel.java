package org.openstreetmap.josm.plugins.chainpoi;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.TableModelEvent;
import javax.swing.table.AbstractTableModel;

import org.openstreetmap.josm.data.osm.DataSet;

/**
 * Подробности по выбранному магазину: что не так и что будет изменено.
 */
public class ReviewDetailPanel extends JPanel {

    /** Действия, которые панель просит выполнить у родителя. */
    public interface Listener {
        void onAccept(ReviewTableModel.Row row);

        void onSkip(ReviewTableModel.Row row);

        void onNavigate(int delta);

        void onShow(ReviewTableModel.Row row);

        void onPanorama(ReviewTableModel.Row row);

        void onClose(ReviewTableModel.Row row);
    }

    private final transient Listener listener;
    private final JLabel title = new JLabel(" ");
    private final JLabel info = new JLabel(" ");
    private final JLabel warnings = new JLabel(" ");
    private final DiffModel diffModel = new DiffModel();
    private final JTable diffTable = new JTable(diffModel);
    private final JLabel noDiffs = new JLabel("Расхождений нет");
    private static final String CARD_TABLE = "table";
    private static final String CARD_NONE = "none";
    private transient java.awt.CardLayout diffCards;
    private transient JPanel diffArea;
    private final JButton prev = new JButton("◀");
    private final JButton next = new JButton("▶");
    private final JButton accept = new JButton("Принять");
    private final JButton skip = new JButton("Пропустить");
    private final JButton show = new JButton("Карта");
    private final JButton panorama = new JButton("Панорамы");
    private final JButton close = new JButton("Закрылся");

    private ReviewTableModel.Row row;

    public ReviewDetailPanel(Listener listener) {
        super(new BorderLayout(0, 2));
        this.listener = listener;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));

        title.setFont(title.getFont().deriveFont(Font.BOLD));
        info.setFont(info.getFont().deriveFont(Font.PLAIN, info.getFont().getSize2D() - 1f));

        JPanel head = new JPanel();
        head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
        title.setAlignmentX(0f);
        info.setAlignmentX(0f);
        warnings.setAlignmentX(0f);
        head.add(title);
        head.add(info);
        head.add(warnings);
        add(head, BorderLayout.NORTH);

        diffTable.setTableHeader(null);
        diffTable.setRowHeight(18);
        diffTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        diffTable.getColumnModel().getColumn(0).setMaxWidth(26);
        diffTable.getColumnModel().getColumn(1).setPreferredWidth(105);
        JScrollPane scroll = new JScrollPane(diffTable);
        scroll.setPreferredSize(new Dimension(100, 100));
        scroll.setMinimumSize(new Dimension(60, 60));
        noDiffs.setForeground(java.awt.Color.GRAY);
        diffCards = new java.awt.CardLayout();
        JPanel diffArea = new JPanel(diffCards);
        diffArea.add(scroll, CARD_TABLE);
        diffArea.add(noDiffs, CARD_NONE);
        add(diffArea, BorderLayout.CENTER);
        this.diffArea = diffArea;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(prev);
        buttons.add(next);
        buttons.add(accept);
        buttons.add(skip);
        buttons.add(show);
        buttons.add(panorama);
        buttons.add(close);
        add(buttons, BorderLayout.SOUTH);

        prev.setToolTipText("Предыдущий объект");
        next.setToolTipText("Следующий объект");
        accept.setToolTipText("Применить отмеченные изменения к этому объекту");
        skip.setToolTipText("Не менять этот объект");
        show.setToolTipText("Показать объект на карте (масштаб около 200 м)");
        panorama.setToolTipText("Яндекс.Карты со слоем панорам у магазина; из карты данные не берём — только фотографии панорам");
        close.setToolTipText("Отметить магазин закрытым: shop → disused:shop");

        prev.addActionListener(e -> fireNavigate(-1));
        next.addActionListener(e -> fireNavigate(1));
        accept.addActionListener(e -> {
            if (row != null && row.match.canApply()) {
                listener.onAccept(row);
            }
        });
        skip.addActionListener(e -> {
            if (row != null) {
                listener.onSkip(row);
            }
        });
        show.addActionListener(e -> {
            if (row != null) {
                listener.onShow(row);
            }
        });
        panorama.addActionListener(e -> {
            if (row != null) {
                listener.onPanorama(row);
            }
        });
        close.addActionListener(e -> {
            if (row != null) {
                listener.onClose(row);
            }
        });
        diffModel.addTableModelListener(e -> {
            if (e.getType() == TableModelEvent.UPDATE) {
                accept.setEnabled(row != null && row.match.canApply() && hasAccepted());
            }
        });

        clear();
    }

    public void setRow(ReviewTableModel.Row row, DataSet ds) {
        this.row = row;
        if (row == null || row.match == null) {
            clear();
            return;
        }
        Match m = row.match;
        title.setText(row.title() + (row.store != null && !row.store.code.isEmpty()
                ? " · " + row.store.code : ""));

        StringBuilder sb = new StringBuilder(row.address());
        String ref = ReviewTableModel.ref(m.primitive);
        if (!ref.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("   ·   ");
            }
            sb.append("OSM: ").append(ref);
            if (m.distance >= 0) {
                sb.append(", ").append(Math.round(m.distance)).append(" м");
            }
        } else if (m.status == Match.Status.MISSING) {
            if (sb.length() > 0) {
                sb.append("   ·   ");
            }
            sb.append("в OSM не найден");
        }
        if (row.store != null && ds != null) {
            AddressTags hint = Matcher.addressHint(row.store, ds);
            if (hint != null && hint.street != null) {
                sb.append('\n').append("по зданию: ").append(hint.street);
                if (hint.housenumber != null) {
                    sb.append(", ").append(hint.housenumber);
                }
            }
        }
        String branch = m.primitive == null ? null : m.primitive.get("branch");
        if (branch != null && !branch.isEmpty()) {
            sb.append('\n').append("branch: ").append(branch);
        }
        info.setText("<html><body style='width:320px'>"
                + escape(sb.toString()).replace("\n", "<br>") + "</body></html>");

        if (m.warnings.isEmpty()) {
            warnings.setText(" ");
        } else {
            warnings.setText("<html><body style='width:300px;color:#b35c00'>"
                    + escape(String.join("; ", m.warnings)) + "</body></html>");
        }

        boolean showDiffCheckbox = m.status == Match.Status.DIFF || m.status == Match.Status.BAD;
        List<FieldDiff> fields = displayDiffs(row);
        diffModel.set(fields, showDiffCheckbox);
        boolean hasFields = !fields.isEmpty();
        diffCards.show(diffArea, hasFields ? CARD_TABLE : CARD_NONE);
        noDiffs.setText(m.status == Match.Status.OK ? "Все теги совпадают" : "Нечего показывать");
        accept.setVisible(m.status != Match.Status.ORPHAN);
        accept.setEnabled(m.canApply() && hasAccepted());
        close.setVisible(m.status == Match.Status.ORPHAN);
        skip.setEnabled(m.status != Match.Status.OK);
        show.setEnabled(m.primitive != null);
        panorama.setEnabled(m.primitive != null || row.store != null);
        revalidate();
        repaint();
    }

    public void clear() {
        row = null;
        title.setText("Магазин не выбран");
        info.setText("Выберите строку в таблице");
        warnings.setText(" ");
        diffModel.set(new ArrayList<>(), false);
        diffCards.show(diffArea, CARD_NONE);
        noDiffs.setText("Выберите строку в таблице");
        accept.setEnabled(false);
        skip.setEnabled(false);
        show.setEnabled(false);
        panorama.setEnabled(false);
        close.setVisible(false);
    }

    private boolean hasAccepted() {
        if (row == null || row.match == null) {
            return false;
        }
        if (row.match.status == Match.Status.MISSING) {
            return true;
        }
        for (FieldDiff d : row.match.diffs) {
            if (d.accepted) {
                return true;
            }
        }
        return false;
    }

    private static List<FieldDiff> displayDiffs(ReviewTableModel.Row row) {
        List<FieldDiff> out = new ArrayList<>();
        Match m = row.match;
        if (m.status == Match.Status.MISSING && row.store != null) {
            for (Map.Entry<String, String> e : Matcher.tagsForCreate(row.store).entrySet()) {
                out.add(new FieldDiff(e.getKey(), null, e.getValue(), FieldDiff.Kind.MISSING));
            }
        } else if (m.status == Match.Status.ORPHAN && m.primitive != null) {
            out.add(new FieldDiff("shop", m.primitive.get("shop"), null, FieldDiff.Kind.DIFFERENT));
        } else {
            out.addAll(m.diffs);
        }
        return out;
    }

    private void fireNavigate(int delta) {
        listener.onNavigate(delta);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Список полей: галочка, ключ, значение в OSM, значение у сети. */
    private static final class DiffModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"", "поле", "в OSM", "у сети"};
        private List<FieldDiff> rows = new ArrayList<>();
        private boolean editable;

        void set(List<FieldDiff> list, boolean editable) {
            this.rows = list == null ? new ArrayList<>() : list;
            this.editable = editable;
            fireTableDataChanged();
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
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return editable && columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            FieldDiff d = rows.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return d.accepted;
                case 1:
                    return d.key;
                case 2:
                    return d.osmText();
                default:
                    return d.chainText();
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                rows.get(rowIndex).accepted = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}

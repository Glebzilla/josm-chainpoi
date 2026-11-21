package org.openstreetmap.josm.plugins.chainpoi;

import java.util.Map;

import org.openstreetmap.josm.actions.upload.UploadHook;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;

/**
 * Перед показом окна загрузки дополняет источник данных ссылками на сайты
 * сетей, если плагин применял их данные.
 *
 * <p>Срабатывает до открытия диалога, поэтому строку видно в поле «Источник»
 * и в тегах changeset ещё до отправки.
 */
public final class ChainPoiUploadHook implements UploadHook {

    @Override
    public void modifyChangesetTags(Map<String, String> tags) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) {
            return;
        }
        String own = ds.getChangeSetTags().get(Sources.TAG);
        if (own == null || own.trim().isEmpty()) {
            return;
        }
        tags.put(Sources.TAG, own);

        StringBuilder source = new StringBuilder();
        String current = tags.get("source");
        if (current != null) {
            source.append(current.trim());
        }
        for (String url : own.split(";")) {
            String u = url.trim();
            if (u.isEmpty() || source.indexOf(u) >= 0) {
                continue;
            }
            if (source.length() > 0) {
                source.append("; ");
            }
            source.append(u);
        }
        if (source.length() > 0) {
            tags.put("source", source.toString());
        }
    }
}

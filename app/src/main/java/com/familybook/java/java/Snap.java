package com.familybook.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Firebase DataSnapshot-এর মতো ছোট wrapper। ভেতরে JSON থাকে (Supabase row/jsonb থেকে)।
 * child(), getValue(), exists(), hasChild(), getChildren(), getChildrenCount() একই আচরণ দেয়।
 */
final class Snap {
    static final Snap NONE = new Snap(null, null);

    private final Object value;
    private final String key;

    private Snap(Object value, String key) {
        this.value = value;
        this.key = key;
    }

    /** JSON object থেকে snap; null হলে NONE */
    static Snap of(JSONObject o) {
        return o == null ? NONE : new Snap(o, null);
    }

    Snap child(String k) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (k != null && o.has(k) && !o.isNull(k)) {
                return new Snap(o.opt(k), k);
            }
        }
        return new Snap(null, k);
    }

    boolean exists() {
        return value != null && value != JSONObject.NULL;
    }

    boolean hasChild(String k) {
        return child(k).exists();
    }

    boolean hasChildren() {
        return getChildrenCount() > 0;
    }

    long getChildrenCount() {
        if (value instanceof JSONObject) return ((JSONObject) value).length();
        if (value instanceof JSONArray) return ((JSONArray) value).length();
        return 0L;
    }

    List<Snap> getChildren() {
        List<Snap> out = new ArrayList<>();
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.add(new Snap(o.opt(k), k));
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                out.add(new Snap(a.opt(i), String.valueOf(i)));
            }
        }
        return out;
    }

    String getKey() {
        return key;
    }

    <T> T getValue(Class<T> c) {
        if (!exists() || value instanceof JSONObject || value instanceof JSONArray) return null;
        if (c == String.class) {
            return c.cast(String.valueOf(value));
        }
        if (c == Boolean.class) {
            if (value instanceof Boolean) return c.cast(value);
            return c.cast(Boolean.valueOf(String.valueOf(value)));
        }
        if (c == Long.class) {
            if (value instanceof Number) return c.cast(((Number) value).longValue());
            try {
                return c.cast(Long.parseLong(String.valueOf(value)));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** ভেতরের JSON object (না থাকলে খালি) */
    JSONObject json() {
        return value instanceof JSONObject ? (JSONObject) value : new JSONObject();
    }
}

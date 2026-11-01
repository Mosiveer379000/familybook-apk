package com.familybook.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Edit" স্ক্রিন: সব ছবি একটার নিচে একটা। প্রতিটার Edit বাটন (Effects/Crop), "..." (উপরে/নিচে নেওয়া)
 * আর ✕ (বাদ দেওয়া)। DONE বা ← চাপলে মূল স্ক্রিনে ফেরে।
 */
public final class PhotoListDialog {
    private PhotoListDialog() { }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** পোস্ট এডিটে SAVE চাপলে নতুন লেখা পাঠায় */
    public interface TextSave { void save(String text); }

    public static void show(final Context ctx, final List<PostPhoto> photos, final Runnable onClose) {
        build(ctx, photos, onClose, null, null);
    }

    /**
     * পোস্ট এডিটের পুরো এডিটর: উপরে পোস্টের লেখা, নিচে সব ছবি (Effects/Crop, ক্রম বদল, বাদ, ক্যাপশন)।
     * SAVE চাপলে onSave চলে; ← চাপলে কিছুই সেভ হয় না।
     */
    public static void showForEdit(final Context ctx, final List<PostPhoto> photos,
                                   final String text, final PostEditExtras extras, final TextSave onSave) {
        build(ctx, photos, null, text == null ? "" : text, onSave, extras);
    }

    private static void build(final Context ctx, final List<PostPhoto> photos, final Runnable onClose,
                              final String initText, final TextSave onSave) {
        build(ctx, photos, onClose, initText, onSave, null);
    }

    private static void build(final Context ctx, final List<PostPhoto> photos, final Runnable onClose,
                              final String initText, final TextSave onSave, final PostEditExtras extras) {
        final boolean editMode = onSave != null;
        final EditText postText = editMode ? new EditText(ctx) : null;
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        // উপরের বার
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        View back = Icons.view(ctx, Icons.NAME_BACK, "\u2190", 26, Color.BLACK);
        back.setOnClickListener(v -> dlg.dismiss());
        top.addView(back, new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)));
        TextView title = new TextView(ctx);
        title.setText(editMode ? "Edit post" : "Edit");
        title.setTextSize(21);
        title.setTextColor(Color.BLACK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(ctx, 22);
        top.addView(title, tl);
        TextView done = new TextView(ctx);
        done.setText(editMode ? "SAVE" : "DONE");
        done.setTextSize(17);
        done.setTextColor(editMode ? 0xFF0866FF : Color.BLACK);
        done.setTypeface(editMode ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        done.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8));
        done.setOnClickListener(v -> {
            if (editMode) {
                String t = postText.getText().toString().trim();
                dlg.dismiss();
                onSave.save(t);
            } else {
                dlg.dismiss();
            }
        });
        top.addView(done, new LinearLayout.LayoutParams(-2, -2));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(ctx, 58)));
        View line = new View(ctx);
        line.setBackgroundColor(0xFFCED0D4);
        root.addView(line, new LinearLayout.LayoutParams(-1, 2));

        ScrollView sv = new ScrollView(ctx);
        final LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        if (editMode) {
            postText.setHint("Say something about this post…");
            postText.setHintTextColor(0xFF8A8D91);
            postText.setTextColor(Color.BLACK);
            postText.setTextSize(18);
            postText.setBackground(null);
            postText.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16));
            postText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            postText.setMinLines(2);
            postText.setText(initText);
            content.addView(postText, new LinearLayout.LayoutParams(-1, -2));
            if (extras != null) content.addView(extrasPanel(ctx, extras), new LinearLayout.LayoutParams(-1, -2));
            View g0 = new View(ctx);
            g0.setBackgroundColor(0xFFF0F2F5);
            content.addView(g0, new LinearLayout.LayoutParams(-1, dp(ctx, 8)));
        }
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final Map<PostPhoto, Bitmap> cache = new HashMap<>();
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (final PostPhoto ph : new ArrayList<>(photos)) {
                list.addView(card(ctx, dlg, photos, ph, cache, render[0], editMode),
                        new LinearLayout.LayoutParams(-1, -2));
                View gap = new View(ctx);
                gap.setBackgroundColor(0xFFF0F2F5);
                list.addView(gap, new LinearLayout.LayoutParams(-1, dp(ctx, 8)));
            }
        };
        render[0].run();

        dlg.setOnDismissListener(d -> { if (onClose != null) onClose.run(); });
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dlg.setContentView(root, new ViewGroup.LayoutParams(-1, -1));
        dlg.show();
    }

    private static View card(final Context ctx, final Dialog dlg, final List<PostPhoto> photos,
                             final PostPhoto ph, final Map<PostPhoto, Bitmap> cache, final Runnable render,
                             final boolean keepOpen) {
        FrameLayout f = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setMaxHeight(dp(ctx, 560));
        iv.setBackgroundColor(0xFFE4E6EB);
        Bitmap b = cache.get(ph);
        if (b == null && ph.comp != null) {
            b = ph.decode(900);
            if (b != null) cache.put(ph, b);
        }
        if (b != null) {
            iv.setImageBitmap(b);
            f.addView(iv, new FrameLayout.LayoutParams(-1, -2));
        } else {
            iv.setImageDrawable(new ColorDrawable(0xFFE4E6EB));
            f.addView(iv, new FrameLayout.LayoutParams(-1, dp(ctx, 220)));
        }

        // Edit বাটন (বামে উপরে)
        if (ph.comp != null) {
            LinearLayout edit = new LinearLayout(ctx);
            edit.setOrientation(LinearLayout.HORIZONTAL);
            edit.setGravity(Gravity.CENTER_VERTICAL);
            edit.setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
            edit.setBackground(pill(ctx, 0xCC6B6B6B, 10));
            ImageView ic = new ImageView(ctx);
            ic.setImageDrawable(Icons.pencil(ctx, dp(ctx, 22), Color.WHITE));
            edit.addView(ic, new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)));
            TextView t = new TextView(ctx);
            t.setText("Edit");
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.leftMargin = dp(ctx, 10);
            edit.addView(t, tl);
            edit.setOnClickListener(v -> PhotoEditorDialog.show(ctx, ph, () -> {
                cache.remove(ph);
                render.run();
            }));
            FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            ep.setMargins(dp(ctx, 12), dp(ctx, 12), 0, 0);
            f.addView(edit, ep);
        }

        // ✕ (ডানে উপরে) আর "..." (তার বামে)
        View x = round(ctx, Icons.NAME_CLOSE, "\u2715", 18);
        x.setOnClickListener(v -> {
            photos.remove(ph);
            cache.remove(ph);
            if (photos.isEmpty() && !keepOpen) dlg.dismiss(); else render.run();
        });
        FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42), Gravity.TOP | Gravity.END);
        xp.setMargins(0, dp(ctx, 12), dp(ctx, 12), 0);
        f.addView(x, xp);

        final View more = round(ctx, Icons.NAME_MORE, "\u2022\u2022\u2022", 12);
        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, more);
            pm.getMenu().add(0, 1, 0, "উপরে নিন");
            pm.getMenu().add(0, 2, 1, "নিচে নিন");
            pm.setOnMenuItemClickListener(item -> {
                int i = photos.indexOf(ph);
                int j = item.getItemId() == 1 ? i - 1 : i + 1;
                if (i >= 0 && j >= 0 && j < photos.size()) {
                    Collections.swap(photos, i, j);
                    render.run();
                }
                return true;
            });
            pm.show();
        });
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(ctx, 48), dp(ctx, 42), Gravity.TOP | Gravity.END);
        mp.setMargins(0, dp(ctx, 12), dp(ctx, 62), 0);
        f.addView(more, mp);

        // প্রতিটি ছবির নিচে আলাদা ক্যাপশন
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(f, new LinearLayout.LayoutParams(-1, -2));
        EditText et = new EditText(ctx);
        et.setHint("Add a caption\u2026");
        et.setHintTextColor(0xFF8A8D91);
        et.setTextColor(Color.BLACK);
        et.setTextSize(18);
        et.setBackground(null);
        et.setPadding(dp(ctx, 16), dp(ctx, 18), dp(ctx, 16), dp(ctx, 22));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMaxLines(5);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        et.setText(ph.caption == null ? "" : ph.caption);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) { ph.caption = e.toString(); }
        });
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));
        return wrap;
    }

    // ------------------------------------------------------------ Feeling / Location / Music সারি

    private interface ResCb { void on(ActivityResult res); }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** যেকোনো অ্যাক্টিভিটি থেকে (আগে থেকে register ছাড়াই) অন্য স্ক্রিন খুলে ফলাফল নেয় */
    private static void launch(Context ctx, Intent i, final ResCb cb) {
        Activity act = activityOf(ctx);
        if (!(act instanceof ComponentActivity)) return;
        final java.util.concurrent.atomic.AtomicReference<ActivityResultLauncher<Intent>> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        ActivityResultLauncher<Intent> l = ((ComponentActivity) act).getActivityResultRegistry().register(
                "post_edit_" + System.nanoTime(), new ActivityResultContracts.StartActivityForResult(), res -> {
                    ActivityResultLauncher<Intent> me = ref.get();
                    if (me != null) me.unregister();
                    cb.on(res);
                });
        ref.set(l);
        l.launch(i);
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    private static View extrasPanel(final Context ctx, final PostEditExtras ex) {
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            box.removeAllViews();
            boolean hasF = !empty(ex.feeling) || !empty(ex.activity);
            String f = !empty(ex.feeling) ? "feeling " + ex.feeling : ex.activity;
            box.addView(extraRow(ctx, "\uD83D\uDE0A", hasF ? f : "Feeling / Activity", hasF,
                    v -> launch(ctx, new Intent(ctx, FeelingActivity.class), res -> {
                        if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
                        String kind = res.getData().getStringExtra(FeelingActivity.EXTRA_KIND);
                        String text = res.getData().getStringExtra(FeelingActivity.EXTRA_TEXT);
                        if ("feeling".equals(kind)) { ex.feeling = text; ex.activity = null; }
                        else { ex.activity = text; ex.feeling = null; }
                        refresh[0].run();
                    }),
                    v -> { ex.feeling = null; ex.activity = null; refresh[0].run(); }));

            box.addView(extraRow(ctx, "\uD83D\uDCCD", !empty(ex.location) ? ex.location : "Location",
                    !empty(ex.location),
                    v -> {
                        Intent i = new Intent(ctx, LocationActivity.class);
                        i.putExtra(LocationActivity.EXTRA_LOCATION, ex.location);
                        launch(ctx, i, res -> {
                            if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
                            String loc = res.getData().getStringExtra(LocationActivity.EXTRA_LOCATION);
                            ex.location = empty(loc) ? null : loc;   // খালি = সরানো
                            refresh[0].run();
                        });
                    },
                    v -> { ex.location = null; refresh[0].run(); }));

            boolean hasM = !empty(ex.musicUrl);
            box.addView(extraRow(ctx, "\uD83C\uDFB5",
                    hasM ? (empty(ex.musicTitle) ? "Music" : ex.musicTitle) : "Music", hasM,
                    v -> {
                        if (hasM) trimMusic(ctx, ex, ex.musicTitle, ex.musicArtist, ex.musicCover,
                                ex.musicUrl, ex.musicStart, refresh[0]);
                        else pickMusic(ctx, ex, refresh[0]);
                    },
                    v -> {
                        ex.musicTitle = ex.musicArtist = ex.musicCover = ex.musicUrl = null;
                        ex.musicStart = 0;
                        refresh[0].run();
                    }));

            if (ex.tagsKnown) {
                final String tl = ex.tagLabel();
                box.addView(extraRow(ctx, "\uD83C\uDFF7\uFE0F", tl != null ? "with " + tl : "Tag / Collab",
                        tl != null,
                        v -> {
                            Intent i = new Intent(ctx, TagCollabActivity.class);
                            i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS, ex.tagIds);
                            i.putStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES, ex.tagNames);
                            i.putExtra(TagCollabActivity.EXTRA_COLLAB_ID, ex.collabId);
                            i.putExtra(TagCollabActivity.EXTRA_COLLAB_NAME, ex.collabName);
                            launch(ctx, i, res -> {
                                if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
                                Intent d = res.getData();
                                ArrayList<String> ids = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_IDS);
                                ArrayList<String> names = d.getStringArrayListExtra(TagCollabActivity.EXTRA_TAG_NAMES);
                                ex.tagIds = ids == null ? new ArrayList<>() : ids;
                                ex.tagNames = names == null ? new ArrayList<>() : names;
                                ex.collabId = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_ID);
                                ex.collabName = d.getStringExtra(TagCollabActivity.EXTRA_COLLAB_NAME);
                                refresh[0].run();
                            });
                        },
                        v -> {
                            ex.tagIds = new ArrayList<>(); ex.tagNames = new ArrayList<>();
                            ex.collabId = null; ex.collabName = null;
                            refresh[0].run();
                        }));
            }
        };
        refresh[0].run();
        return box;
    }

    private static void pickMusic(final Context ctx, final PostEditExtras ex, final Runnable refresh) {
        launch(ctx, new Intent(ctx, MusicPickerActivity.class), res -> {
            if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
            Intent d = res.getData();
            trimMusic(ctx, ex, d.getStringExtra(MusicPickerActivity.EXTRA_TITLE),
                    d.getStringExtra(MusicPickerActivity.EXTRA_ARTIST),
                    d.getStringExtra(MusicPickerActivity.EXTRA_COVER),
                    d.getStringExtra(MusicPickerActivity.EXTRA_URL), 0, refresh);
        });
    }

    private static void trimMusic(final Context ctx, final PostEditExtras ex, final String title,
                                  final String artist, final String cover, final String url,
                                  int start, final Runnable refresh) {
        Intent i = new Intent(ctx, MusicTrimActivity.class);
        i.putExtra(MusicPickerActivity.EXTRA_TITLE, title);
        i.putExtra(MusicPickerActivity.EXTRA_ARTIST, artist);
        i.putExtra(MusicPickerActivity.EXTRA_COVER, cover);
        i.putExtra(MusicPickerActivity.EXTRA_URL, url);
        i.putExtra(MusicTrimActivity.EXTRA_START, start);
        launch(ctx, i, res -> {
            int code = res.getResultCode();
            if (code == MusicTrimActivity.RESULT_CHANGE) {
                pickMusic(ctx, ex, refresh);
            } else if (code == MusicTrimActivity.RESULT_DELETE) {
                ex.musicTitle = ex.musicArtist = ex.musicCover = ex.musicUrl = null;
                ex.musicStart = 0;
                refresh.run();
            } else if (code == Activity.RESULT_OK && res.getData() != null) {
                ex.musicTitle = title;
                ex.musicArtist = artist;
                ex.musicCover = cover;
                ex.musicUrl = url;
                ex.musicStart = res.getData().getIntExtra(MusicTrimActivity.EXTRA_START, 0);
                refresh.run();
            }
        });
    }

    private static View extraRow(Context ctx, String icon, String label, boolean set,
                                 View.OnClickListener onClick, View.OnClickListener onClear) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12));
        TextView ic = new TextView(ctx);
        ic.setText(icon);
        ic.setTextSize(20);
        row.addView(ic, new LinearLayout.LayoutParams(dp(ctx, 34), -2));
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextSize(16);
        tv.setTextColor(set ? Color.BLACK : 0xFF65676B);
        tv.setTypeface(set ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        if (set) {
            TextView x = new TextView(ctx);
            x.setText("\u2715");
            x.setTextSize(16);
            x.setTextColor(0xFF65676B);
            x.setGravity(Gravity.CENTER);
            x.setOnClickListener(onClear);
            row.addView(x, new LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 36)));
        }
        row.setOnClickListener(onClick);
        return row;
    }

    private static View round(Context ctx, String iconName, String fallback, float size) {
        View v = Icons.view(ctx, iconName, fallback, size, Color.WHITE);
        if (v instanceof ImageView) v.setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10));
        v.setBackground(pill(ctx, 0xAA000000, 22));
        return v;
    }

    private static GradientDrawable pill(Context ctx, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }
}

package com.familybook.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * নোটিফিকেশনে ট্যাপ করলে:
 *   "collab_request" -> CollabApproveDialog.show(this, postId, actorName, false, done);
 *   "tag_request"    -> CollabApproveDialog.show(this, postId, actorName, true,  done);
 * Approve (সবুজ) -> status = accepted -> পোস্ট আপনার প্রোফাইলে যোগ হবে
 * Decline -> প্রোফাইলে যোগ হবে না, আর পোস্টের "with ..." লাইন থেকেও আপনার নাম সরে যাবে
 */
public final class CollabApproveDialog {

    public interface Done {
        void onDone(boolean accepted);
    }

    private CollabApproveDialog() { }

    /** পুরনো কল (কোলাব) */
    public static void show(final Context ctx, final String postId, final String actorName, final Done done) {
        show(ctx, postId, actorName, false, done);
    }

    public static void show(final Context ctx, final String postId, final String actorName,
                            final boolean isTag, final Done done) {
        final float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad, pad, pad);

        TextView msg = new TextView(ctx);
        String who = (actorName == null || actorName.isEmpty()) ? "Someone" : actorName;
        msg.setText(isTag
                ? who + " tagged you in a post. Add it to your profile?"
                : who + " added you as a collaborator in this post. Add it to your profile?");
        msg.setTextSize(16);
        msg.setTextColor(Color.BLACK);
        box.addView(msg);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = Math.round(18 * d);
        row.setLayoutParams(rp);

        AlertDialog dlg = new AlertDialog.Builder(ctx).setView(box).setCancelable(true).create();

        TextView decline = button(ctx, d, "Decline", 0xFFE4E6EB, 0xFF000000);
        TextView approve = button(ctx, d, "Approve", 0xFF31A24C, 0xFFFFFFFF);
        decline.setOnClickListener(v -> respond(ctx, postId, isTag, false, done, dlg));
        approve.setOnClickListener(v -> respond(ctx, postId, isTag, true, done, dlg));

        row.addView(decline);
        row.addView(approve);
        box.addView(row);
        dlg.show();
    }

    private static TextView button(Context ctx, float d, String text, int bg, int fg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int ph = Math.round(18 * d);
        t.setPadding(ph, 0, ph, 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(10 * d);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(44 * d));
        lp.setMarginStart(Math.round(10 * d));
        t.setLayoutParams(lp);
        return t;
    }

    private static void respond(final Context ctx, String postId, final boolean isTag,
                                final boolean accept, final Done done, AlertDialog dlg) {
        dlg.dismiss();
        JSONObject b = new JSONObject();
        try {
            b.put("p_post_id", postId);
            b.put("p_accept", accept);
        } catch (Exception e) {
            return;
        }
        SupabaseRest.call("POST", isTag ? "/rest/v1/rpc/respond_tag" : "/rest/v1/rpc/respond_collab", b.toString(), null, true, r -> {
            if (!r.ok()) {
                Toast.makeText(ctx, "Failed: " + SupabaseRest.errorText(r), Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(ctx,
                    accept ? "Approved — it will show on your profile" : "Declined",
                    Toast.LENGTH_SHORT).show();
            if (done != null) done.onDone(accept);
        });
    }
}

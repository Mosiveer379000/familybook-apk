package com.familybook.app;

import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Phone number / Add email ফর্ম (একই পেজ)।
 * Intent extra "type": "phone" | "email"
 * Save → profiles.info.contact.{phone|email} ও {phone|email}_priv; সফল হলে RESULT_OK।
 * ফাঁকা রেখে Save করলে (আগে মান থাকলে) মুছে যায়।
 */
public class ContactFieldActivity extends BaseActivity {

    private boolean phone;
    private EditText et;
    private TextView tvPriv, btnSave;
    private ImageView ivPriv;
    private String priv = ContactStore.ME;      // ডিফল্ট: Only me
    private String oldValue = "";
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) { finish(); return; }
        setContentView(R.layout.activity_contact_field);

        phone = !"email".equals(getIntent().getStringExtra("type"));
        et = findViewById(R.id.etCf);
        tvPriv = findViewById(R.id.tvCfPriv);
        ivPriv = findViewById(R.id.ivCfPriv);
        btnSave = findViewById(R.id.btnCfSave);

        ((ImageView) findViewById(R.id.ivCfIcon)).setImageResource(phone ? R.drawable.ic_ct_phone : R.drawable.ic_ct_mail);
        ((TextView) findViewById(R.id.tvCfTitle)).setText(phone ? "Phone number" : "Add email");
        ((TextView) findViewById(R.id.tvCfLabel)).setText(phone ? "Phone number" : "Email :");
        et.setHint(phone ? "+100000000000" : "example@family.com");
        et.setInputType(phone ? InputType.TYPE_CLASS_PHONE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        findViewById(R.id.btnCfClose).setOnClickListener(v -> finish());
        findViewById(R.id.chipCfPriv).setOnClickListener(v ->
                ContactStore.choose(this, priv, p -> { priv = p; showPriv(); }));
        btnSave.setOnClickListener(v -> save());
        showPriv();

        // আগের মান ও প্রাইভেসি বসানো
        ContactStore.load(Me.uid(), (c, links, err) -> {
            if (isFinishing()) return;
            String key = phone ? "phone" : "email";
            oldValue = c.optString(key, "").trim();
            if (!oldValue.isEmpty()) {
                et.setText(oldValue);
                et.setSelection(et.getText().length());
            }
            priv = ContactStore.norm(c.optString(key + "_priv", ""), ContactStore.ME);
            showPriv();
        });
    }

    private void showPriv() {
        tvPriv.setText(ContactStore.label(priv));
        ivPriv.setImageResource(ContactStore.icon(priv));
    }

    private void save() {
        if (saving) return;
        String raw = et.getText().toString().trim();
        String value;
        if (raw.isEmpty()) {
            if (oldValue.isEmpty()) {
                TopToast.show(this, phone ? "Enter a phone number" : "Enter an email address");
                return;
            }
            value = "";                                   // মুছে ফেলা
        } else if (phone) {
            value = raw.replaceAll("[\\s\\-()]", "");
            if (!value.matches("^\\+?[0-9]{7,15}$")) {
                TopToast.show(this, "Enter a valid phone number");
                return;
            }
        } else {
            value = raw;
            if (!Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
                TopToast.show(this, "Enter a valid email address");
                return;
            }
        }
        saving = true;
        btnSave.setAlpha(0.6f);
        try {
            String key = phone ? "phone" : "email";
            JSONObject ch = new JSONObject().put(key, value).put(key + "_priv", priv);
            ContactStore.update(ch, e -> {
                if (isFinishing()) return;
                saving = false;
                btnSave.setAlpha(1f);
                if (e != null) { TopToast.show(this, e); return; }
                TopToast.show(this, value.isEmpty() ? "Removed" : "Saved!");
                setResult(RESULT_OK);
                finish();
            });
        } catch (Exception ex) {
            saving = false;
            btnSave.setAlpha(1f);
            TopToast.show(this, "Could not save");
        }
    }
}

package com.familybook.app;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

/**
 * সাধারণ EditText। Google Autofill / Save password পুরোপুরি চালু থাকে।
 */
public class PlainEditText extends AppCompatEditText {
    public PlainEditText(Context c) { super(c); }
    public PlainEditText(Context c, AttributeSet a) { super(c, a); }
    public PlainEditText(Context c, AttributeSet a, int s) { super(c, a, s); }
}

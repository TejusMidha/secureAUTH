package com.secureauth.simulator.ui;

import android.text.Editable;
import android.text.TextWatcher;

/** A TextWatcher where only "text changed" matters, so call sites stay one line. */
public abstract class AfterTextChangedListener implements TextWatcher {

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public abstract void afterTextChanged(Editable s);
}

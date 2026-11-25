package org.cocfarm.marble;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACK = 0xff101510;
    static final int PANEL = 0xff1b2519;
    static final int EDGE = 0xff43513a;
    static final int TEXT = 0xfff2f5ea;
    static final int MUTED = 0xffa9b6a0;
    static final int LIME = 0xffd9f774;
    static final int WARN = 0xffffca8b;

    private Ui() {}

    static int dp(Context context, int n) {
        return Math.round(context.getResources().getDisplayMetrics().density * n);
    }

    static GradientDrawable surface(Context context, int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        if (stroke != 0) shape.setStroke(dp(context, 1), stroke);
        return shape;
    }

    static TextView text(Context context, String value, int size, int color, boolean mono) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create(mono ? "monospace" : "sans-serif-condensed", Typeface.NORMAL));
        if (mono) text.setLetterSpacing(.035f);
        return text;
    }

    static Button button(Context context, String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setTextColor(primary ? BACK : TEXT);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(context, 9), 0, dp(context, 9), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x38000000 : 0x40d9f774),
                surface(context, primary ? LIME : PANEL, primary ? 0 : EDGE, 5), null));
        button.setOnClickListener(action);
        return button;
    }

    static LinearLayout stack(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    static void line(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), margin);
        parent.addView(child, params);
    }

    static void cell(LinearLayout row, View view, int width, int height, int margin) {
        Context context = row.getContext();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width == 0 ? 0 : dp(context, width), dp(context, height), width == 0 ? 1 : 0);
        params.leftMargin = dp(context, margin);
        row.addView(view, params);
    }
}

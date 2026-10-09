package com.mojing.app.test;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Test-APK-only Activity: no dependency on libraries supplied by the target APK. */
public final class AcceptanceRecognitionActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 56, 24, 24);
        TextView title = new TextView(this);
        title.setText("本地识别验收");
        title.setTextSize(22f);
        layout.addView(title);
        addResultButton(layout, "acceptance-recognition-return", "返回识别文字 丙", RESULT_OK, " 丙 ");
        addResultButton(layout, "acceptance-recognition-empty", "返回空识别", RESULT_OK, "  ");
        addResultButton(layout, "acceptance-recognition-cancel", "取消本次识别", RESULT_CANCELED, null);
        setContentView(layout);
    }

    private void addResultButton(LinearLayout layout, String description, String label, int code, String text) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setOnClickListener(view -> {
            Intent result = null;
            if (text != null) {
                ArrayList<String> values = new ArrayList<>();
                values.add(text);
                result = new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, values);
            }
            setResult(code, result);
            finish();
        });
        layout.addView(button);
    }
}

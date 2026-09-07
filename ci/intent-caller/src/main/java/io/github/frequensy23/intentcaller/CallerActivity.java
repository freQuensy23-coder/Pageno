package io.github.frequensy23.intentcaller;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

public class CallerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("PDF caller remains in its own task");
        setContentView(view);
        if (state == null) open(getIntent());
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        open(intent);
    }
    private void open(Intent request) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setClassName("io.github.frequensy23.pageno",
            "com.gitlab.mudlej.MjPdfReader.ui.reader.MainActivity");
        view.setDataAndType(Uri.parse("content://io.github.frequensy23.intentcaller.pdf/test.pdf"), "application/pdf");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if ("plain".equals(request.getStringExtra("mode"))) startActivity(view);
        else startActivityForResult(view, 500);
    }
}

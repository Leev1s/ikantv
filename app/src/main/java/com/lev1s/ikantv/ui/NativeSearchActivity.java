package com.lev1s.ikantv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.lev1s.ikantv.R;

/** 原生搜片页：输入关键词 → 打开 NativeGridActivity 的搜索模式（模板 B 结果 + 游标翻页）。 */
public class NativeSearchActivity extends NativeBrowseActivity {

    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_native_search);

        TabBar.attach(this, findViewById(R.id.search_tabs), TabBar.Tab.SEARCH);
        input = findViewById(R.id.search_input);
        TextView btn = findViewById(R.id.search_btn);
        btn.setOnClickListener(v -> submit());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        input.requestFocus();
    }

    private void submit() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "先输入片名再搜", Toast.LENGTH_SHORT).show();
            input.requestFocus();
            return;
        }
        Intent intent = new Intent(this, NativeGridActivity.class);
        intent.putExtra(NativeGridActivity.EXTRA_KIND, NativeGridActivity.KIND_SEARCH);
        intent.putExtra(NativeGridActivity.EXTRA_QUERY, q);
        intent.putExtra(NativeGridActivity.EXTRA_TITLE, "搜索：" + q);
        startActivity(intent);
    }
}

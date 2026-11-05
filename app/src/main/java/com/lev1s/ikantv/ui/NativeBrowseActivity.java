package com.lev1s.ikantv.ui;

import android.app.Activity;
import android.view.KeyEvent;

/** 浏览页统一的遥控器首页快捷键。 */
public abstract class NativeBrowseActivity extends Activity {
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                TabBar.goHome(this);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}

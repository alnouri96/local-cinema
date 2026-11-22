package com.archos.mediacenter.video.leanback.cinema;

import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

/** Optional key delivery while Android has attached this input method to an editor. */
public final class TvRemoteImeService extends InputMethodService {
    private static TvRemoteImeService current;

    @Override public boolean onEvaluateInputViewShown() { return false; }

    @Override public void onStartInput(android.view.inputmethod.EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        current = this;
    }

    @Override public void onFinishInput() {
        if (current == this) current = null;
        super.onFinishInput();
    }

    @Override public void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    static boolean edit(int move, int delete, String text) {
        if (current == null) return false;
        var connection = current.getCurrentInputConnection();
        if (connection == null) return false;
        if (move == 0 && delete == 0) return text.isEmpty() || connection.commitText(text, 1);
        var snapshot = connection.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
        if (snapshot == null || snapshot.text == null || snapshot.selectionEnd < 0) return false;
        int start = Math.max(0, Math.min(snapshot.text.length(), snapshot.selectionEnd + move));
        int end = Math.min(snapshot.text.length(), start + delete);
        connection.beginBatchEdit();
        try {
            if (!connection.setSelection(snapshot.startOffset + start, snapshot.startOffset + end)) return false;
            return delete == 0 && text.isEmpty() || connection.commitText(text, 1);
        } finally {
            connection.endBatchEdit();
        }
    }

    static boolean send(int key) {
        if (current == null) return false;
        var connection = current.getCurrentInputConnection();
        if (connection == null) return false;
        long time = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        boolean accepted = connection.sendKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN,
                key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        connection.sendKeyEvent(new KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
                key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        return accepted;
    }
}

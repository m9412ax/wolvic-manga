package com.igalia.wolvic.manga;

/**
 * Transient state used while the keybinding UI is capturing a single keypress.
 * VRBrowserActivity feeds every key event here while {@link #active} is true, so
 * the capture screen can display the raw keycode the user pressed (helpful for
 * discovering the correct controller key values).
 */
public class MangaKeyCapture {

    public interface KeyCallback {
        void onKey(int keyCode);
    }

    public static boolean active = false;
    public static int capturedKey = 0;
    public static KeyCallback callback = null;

    public static void start(KeyCallback cb) {
        active = true;
        capturedKey = 0;
        callback = cb;
    }

    public static void stop() {
        active = false;
        callback = null;
    }

    /** Returns true if the key was consumed by capture mode. */
    public static boolean feed(int keyCode) {
        if (!active) {
            return false;
        }
        capturedKey = keyCode;
        if (callback != null) {
            callback.onKey(keyCode);
        }
        return true;
    }
}

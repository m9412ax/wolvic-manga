package com.igalia.wolvic.manga;

import android.util.Log;

import java.util.LinkedHashSet;

/**
 * State of the "add shortcut" flow and of an in-progress key capture.
 *
 * <p>This is deliberately static rather than owned by the settings View: the
 * settings widget rebuilds its entire view tree on several lifecycle events,
 * which used to silently stop an armed capture. Keeping the state here means a
 * rebuilt panel can re-render exactly the same flow and the capture keeps
 * running. VRBrowserActivity feeds every key event here while {@link #active}.</p>
 */
public final class MangaKeyCapture {

    /** Called on the UI thread whenever the captured chord changes. */
    public interface Refresh {
        void onChange();
    }

    /** True while the "add shortcut" editor is open. */
    public static boolean addFlow = false;
    /** True between "start capture" and "stop capture". */
    public static boolean active = false;
    /** Set while the pointer hovers the "stop capture" button. The confirm
     *  trigger must end the capture instead of being recorded as a key. */
    public static boolean captureSuspended = false;
    private static long lastStickTime = 0L;
    private static int lastStickKey = 0;
    /** All keys pressed during the capture, in press order (a chord). */
    public static final LinkedHashSet<Integer> keys = new LinkedHashSet<>();
    /** Currently selected action and scope for the new binding. */
    public static String flowAction = MangaSettings.ACTION_NEXT_PAGE;
    public static int flowScope = MangaSettings.SCOPE_READER;

    /** Registered by the currently visible panel; replaced on every rebuild. */
    private static Refresh refresh = null;

    private MangaKeyCapture() {
    }

    public static void setRefresh(Refresh r) {
        refresh = r;
    }

    /** Enter the add-shortcut editor. Capture stays disarmed until the user
     *  explicitly presses the top "start capture" button. */
    public static void beginAddFlow() {
        addFlow = true;
        active = false;
        keys.clear();
        flowAction = MangaSettings.ACTION_NEXT_PAGE;
        flowScope = MangaSettings.SCOPE_READER;
        notifyChanged();
    }

    /** Re-arm the capture, discarding the previously captured chord. */
    public static void restartCapture() {
        keys.clear();
        active = true;
        captureSuspended = false;
        lastStickKey = 0;
        notifyChanged();
    }

    /** End the capture but stay in the add-shortcut editor. */
    public static void stopCapture() {
        active = false;
        captureSuspended = false;
        notifyChanged();
    }

    /** Fully leave the add-shortcut flow (save / cancel / settings closed). */
    public static void reset() {
        addFlow = false;
        active = false;
        captureSuspended = false;
        lastStickKey = 0;
        keys.clear();
        refresh = null;
    }

    /** Legacy stop used by callers that just want to disarm capture. */
    public static void stop() {
        reset();
    }

    /** Returns true when the key was consumed by capture mode. While the
     *  pointer is on the "stop capture" button we swallow (but do NOT record)
     *  the confirm trigger, so stopping never pollutes the chord. */
    public static boolean feed(int keyCode) {
        if (!active) {
            return false;
        }
        if (captureSuspended) {
            Log.i("MangaCapture", "swallowed key=" + keyCode + " (stop button hovered)");
            return true;
        }
        keys.add(keyCode);
        Log.i("MangaCapture", "feed key=" + keyCode + " chord=" + keys);
        notifyChanged();
        return true;
    }

    /** Converts a thumbstick axis sample into one of the four virtual stick
     *  keys. Only the dominant axis counts, and each direction is recorded
     *  once per push so holding the stick does not spam the chord. */
    public static void feedStick(float dx, float dy) {
        if (!active || captureSuspended) {
            return;
        }
        float ax = Math.abs(dx);
        float ay = Math.abs(dy);
        if (Math.max(ax, ay) < 0.22f) {
            return;
        }
        final int key = ax >= ay
                ? (dx > 0 ? MangaSettings.KEY_STICK_RIGHT : MangaSettings.KEY_STICK_LEFT)
                : (dy > 0 ? MangaSettings.KEY_STICK_DOWN : MangaSettings.KEY_STICK_UP);
        long now = System.currentTimeMillis();
        if (key == lastStickKey && now - lastStickTime < 700L) {
            return;
        }
        lastStickKey = key;
        lastStickTime = now;
        feed(key);
    }

    public static int[] capturedKeys() {
        int[] out = new int[keys.size()];
        int i = 0;
        for (Integer k : keys) {
            out[i++] = k;
        }
        return out;
    }

    private static void notifyChanged() {
        Refresh r = refresh;
        if (r != null) {
            r.onChange();
        }
    }
}

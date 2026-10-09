package app.morphe.extension.reddit.patches;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class EnableTextSelectionPatch {

    /** @return If this patch was included during patching. */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    // region Compose rich text (post body, comment body).

    /** True while the original rich text composable is being invoked from the selection wrapper. */
    private static final ThreadLocal<Boolean> IN_WRAPPER = new ThreadLocal<>();

    /**
     * Injection point. Called at the start of the Compose rich text renderer.
     *
     * @return If the renderer should be re-invoked inside a SelectionContainer.
     */
    public static boolean shouldWrapRichText() {
        try {
            return Settings.ENABLE_TEXT_SELECTION.get() && IN_WRAPPER.get() == null;
        } catch (Exception ex) {
            Logger.printException(() -> "shouldWrapRichText failure", ex);
            return false;
        }
    }

    /**
     * Injection point. Builds the composable content passed to SelectionContainer.
     * The content calls the original rich text renderer with the original arguments.
     *
     * A dynamic proxy of the app's own kotlin Function2 is used (the app's Compose runtime
     * checks its own, possibly renamed, function interface), so the extension does not need
     * to reference any Kotlin type at compile time.
     */
    public static Object createRichTextContent(Class<?> owner, String methodName, Object[] args) {
        try {
            ClassLoader loader = owner.getClassLoader();
            Class<?> function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader);
            return Proxy.newProxyInstance(loader, new Class<?>[]{function2},
                    new RichTextContentHandler(owner, methodName, args));
        } catch (Exception ex) {
            Logger.printException(() -> "createRichTextContent failure", ex);
            throw new RuntimeException(ex);
        }
    }

    private static final class RichTextContentHandler implements InvocationHandler {
        private final Class<?> owner;
        private final String methodName;
        private final Object[] args;

        RichTextContentHandler(Class<?> owner, String methodName, Object[] args) {
            this.owner = owner;
            this.methodName = methodName;
            this.args = args;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] methodArgs) {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == methodArgs[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "MorpheRichTextContent";
                }
            }
            if ("invoke".equals(method.getName()) && methodArgs != null && methodArgs.length == 2) {
                return runOriginal(methodArgs[0]);
            }
            return null;
        }

        private Object runOriginal(Object composer) {
            Object[] callArgs = args.clone();
            // Trailing Integer arguments are the Compose $changed/$default flags,
            // the argument before them is the Composer.
            int composerIndex = callArgs.length - 1;
            while (composerIndex >= 0 && callArgs[composerIndex] instanceof Integer) {
                composerIndex--;
            }
            if (composerIndex < 0 || composerIndex + 1 >= callArgs.length) {
                throw new IllegalStateException("Unexpected rich text arguments");
            }
            // Keep $changed and $default untouched. Setting the lowest bit of $changed would make
            // the composable skip applying its default parameter values (null style -> crash).
            callArgs[composerIndex] = composer;

            IN_WRAPPER.set(Boolean.TRUE);
            try {
                findMethod(owner, methodName, callArgs).invoke(null, callArgs);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new RuntimeException(cause);
            } catch (Exception ex) {
                Logger.printException(() -> "rich text selection wrapper failure", ex);
                throw new RuntimeException(ex);
            } finally {
                IN_WRAPPER.remove();
            }
            return null;
        }
    }

    private static Method cachedMethod;

    private static synchronized Method findMethod(Class<?> owner, String name, Object[] args)
            throws NoSuchMethodException {
        if (cachedMethod != null) return cachedMethod;

        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(name)
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != args.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                Object arg = args[i];
                Class<?> type = types[i];
                if (type == boolean.class) {
                    matches = arg instanceof Boolean;
                } else if (type == int.class) {
                    matches = arg instanceof Integer;
                } else {
                    matches = arg == null || type.isInstance(arg);
                }
                if (!matches) break;
            }
            if (matches) {
                method.setAccessible(true);
                cachedMethod = method;
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    // endregion

    // region Comment collapse.

    /**
     * Injection point. Comment collapse (long press) conflicts with text selection.
     */
    public static boolean shouldBlockCommentCollapse() {
        return Settings.ENABLE_TEXT_SELECTION.get();
    }

    // region Temporary diagnostics: writes a log file to Documents.

    private static final boolean DEBUG_LOG = true;

    private static OutputStream debugLogStream;
    private static boolean debugLogFailed;

    private static synchronized void debugLog(String message) {
        if (!DEBUG_LOG || debugLogFailed) return;
        try {
            if (debugLogStream == null) {
                Context context = Utils.getContext();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "morphe_text_selection_debug_" + System.currentTimeMillis() + ".txt");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
                Uri uri = context.getContentResolver().insert(
                        MediaStore.Files.getContentUri("external"), values);
                if (uri == null) {
                    debugLogFailed = true;
                    return;
                }
                debugLogStream = context.getContentResolver().openOutputStream(uri, "wa");
            }
            String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " " + message + "\n";
            debugLogStream.write(line.getBytes(StandardCharsets.UTF_8));
            debugLogStream.flush();
        } catch (Exception ex) {
            debugLogFailed = true;
            Logger.printException(() -> "debugLog failure", ex);
        }
    }

    /** SelectionManagers that currently have a selection. */
    private static final Set<Object> SELECTION_MANAGERS_WITH_SELECTION =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Injection point. Called when the selection container clears its selection.
     */
    public static void onSelectionRelease(Object selectionManager) {
        try {
            SELECTION_MANAGERS_WITH_SELECTION.remove(selectionManager);
            if (!DEBUG_LOG || !Settings.ENABLE_TEXT_SELECTION.get()) return;

            StringBuilder builder = new StringBuilder("SELECTION RELEASED, callers:");
            StackTraceElement[] frames = new Throwable().getStackTrace();
            for (int i = 1; i < frames.length && i <= 8; i++) {
                builder.append("\n    at ").append(frames[i]);
            }
            debugLog(builder.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "onSelectionRelease failure", ex);
        }
    }

    /**
     * Injection point. Called when a selection container changes its selection.
     */
    public static void onSelectionChanged(Object selectionManager, Object selection) {
        try {
            if (selection == null) {
                SELECTION_MANAGERS_WITH_SELECTION.remove(selectionManager);
            } else {
                SELECTION_MANAGERS_WITH_SELECTION.add(selectionManager);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSelectionChanged failure", ex);
        }
    }

    /**
     * Injection point. Called at the start of FocusManager.clearFocus().
     * Reddit's post detail screen clears focus on every touch down (to dismiss the keyboard),
     * which makes the selection container lose focus and drop the selection before a scroll
     * gesture can even start. While there is a selection, focus clears caused by touch input
     * are ignored. A direct tap on the selected text still clears it.
     *
     * @return If clearing focus should be skipped.
     */
    public static boolean shouldBlockFocusClear() {
        try {
            if (SELECTION_MANAGERS_WITH_SELECTION.isEmpty() || !Settings.ENABLE_TEXT_SELECTION.get()) {
                return false;
            }

            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                if ("dispatchTouchEvent".equals(frame.getMethodName())) {
                    debugLog("blocked focus clear caused by touch input");
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "shouldBlockFocusClear failure", ex);
        }
        return false;
    }

    // endregion

    /** Fraction of the normal touch slop used to tell a tap from a drag when clearing the selection. */
    private static final float SELECTION_TAP_SLOP_FACTOR = 0.3f;

    /** Max time between begin and the slop read for them to belong together. */
    private static final long SELECTION_TAP_WINDOW_NANOS = 100_000_000L;

    private static long selectionTapDetectionStart;

    /**
     * Injection point. Called right before the selection container checks whether a touch
     * was a tap (clears the selection) or a drag (keeps the selection).
     */
    public static void beginSelectionTapDetection() {
        selectionTapDetectionStart = Settings.ENABLE_TEXT_SELECTION.get() ? System.nanoTime() : 0;
        if (selectionTapDetectionStart != 0) debugLog("tap detection started (finger down)");
    }

    /**
     * Injection point. Called with the touch slop used by the drag detection above.
     * A smaller slop makes small drags count as drags, so only a direct tap clears the selection.
     */
    public static float adjustSelectionTapSlop(float slop) {
        long start = selectionTapDetectionStart;
        if (start == 0) return slop;

        selectionTapDetectionStart = 0;
        if (System.nanoTime() - start > SELECTION_TAP_WINDOW_NANOS) {
            debugLog("tap detection: slop read too late, unchanged " + slop);
            return slop;
        }
        debugLog("tap detection: slop " + slop + " -> " + (slop * SELECTION_TAP_SLOP_FACTOR));
        return slop * SELECTION_TAP_SLOP_FACTOR;
    }

    // endregion

    // region Legacy View based rich text.

    /**
     * Injection point. RichTextView.setRichTextItems() calls this after adding a child view
     * and running its c(View, boolean) hook.
     */
    public static void makeSelectable(View view) {
        try {
            if (!Settings.ENABLE_TEXT_SELECTION.get() || !(view instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) view;

            textView.setOnTouchListener(null);
            textView.setOnLongClickListener(null);

            textView.setTextIsSelectable(true);
            // setTextIsSelectable() replaces the movement method with ArrowKeyMovementMethod.
            textView.setMovementMethod(SelectableLinkMovementMethod.INSTANCE);
        } catch (Exception ex) {
            Logger.printException(() -> "makeSelectable failure", ex);
        }
    }

    /**
     * LinkMovementMethod.canSelectArbitrarily() is false, which prevents long press
     * selection in TextView. Keeps link clicks working while allowing selection.
     */
    private static final class SelectableLinkMovementMethod extends LinkMovementMethod {
        static final SelectableLinkMovementMethod INSTANCE = new SelectableLinkMovementMethod();

        @Override
        public boolean canSelectArbitrarily() {
            return true;
        }

        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            int action = event.getActionMasked();
            boolean noActiveSelection = widget.getSelectionStart() == widget.getSelectionEnd();
            if (noActiveSelection && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN)) {
                Layout layout = widget.getLayout();
                if (layout != null) {
                    int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
                    int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
                    int offset = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x);
                    ClickableSpan[] links = buffer.getSpans(offset, offset, ClickableSpan.class);
                    if (links.length != 0) {
                        if (action == MotionEvent.ACTION_UP) {
                            links[0].onClick(widget);
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // endregion
}

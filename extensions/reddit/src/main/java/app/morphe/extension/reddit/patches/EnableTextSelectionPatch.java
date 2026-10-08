package app.morphe.extension.reddit.patches;

import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

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
            callArgs[composerIndex] = composer;
            // Force the original composable to run (lowest bit of the first $changed).
            callArgs[composerIndex + 1] = ((Integer) callArgs[composerIndex + 1]) | 1;

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

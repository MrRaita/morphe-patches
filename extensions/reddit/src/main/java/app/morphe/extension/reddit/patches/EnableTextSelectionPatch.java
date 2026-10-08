// TASLAK - derlenmedi, test edilmedi.
// Hedef: extensions/reddit/src/main/java/app/morphe/extension/reddit/patches/EnableTextSelectionPatch.java
// Ayrica gerekli: Settings.java -> ENABLE_TEXT_SELECTION = new BooleanSetting("morphe_enable_text_selection", TRUE, true);
//                 LayoutPreferenceCategory (+getSettingsStatus), strings.xml (title/summary).
package app.morphe.extension.reddit.patches;

import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class EnableTextSelectionPatch {

    /** @return If this patch was included during patching. */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. RichTextView.setRichTextItems() cocuk View'i ekleyip c(View, boolean)
     * kancasini cagirdiktan hemen sonra calisir.
     */
    public static void makeSelectable(View view) {
        try {
            if (!Settings.ENABLE_TEXT_SELECTION.get() || !(view instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) view;

            // Reddit'in uzun basmayi ust View'a yonlendiren / dokunusu yutan dinleyicilerini kaldir.
            // TODO: yorumlarda tek dokunus (thread daraltma vb.) davranisi bu dinleyicilere bagli,
            //       dokunusu ust View'a iletmenin yolu cihazda dogrulanmali.
            textView.setOnTouchListener(null);
            textView.setOnLongClickListener(null);

            textView.setTextIsSelectable(true);
            // setTextIsSelectable hareket yontemini ArrowKey'e cevirir; linkler icin geri al.
            textView.setMovementMethod(SelectableLinkMovementMethod.INSTANCE);
        } catch (Exception ex) {
            Logger.printException(() -> "makeSelectable failure", ex);
        }
    }

    /**
     * LinkMovementMethod canSelectArbitrarily()=false dondurur; bu da TextView'de
     * uzun basarak secimi engeller. Link tiklamasini koruyup secimi serbest birakir.
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
            // Link degilse TextView'in kendi secim mantigina birak.
            return false;
        }
    }
}

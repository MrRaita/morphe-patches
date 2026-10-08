// TASLAK - derlenmedi, test edilmedi.
// Hedef: patches/src/main/kotlin/app/morphe/patches/reddit/layout/textselection/Fingerprints.kt
package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * com.reddit.richtext.RichTextView.setRichTextItems(List): her cocuk View eklendikten sonra
 * bos "c(View, boolean)" kancasi cagrilir. Yorumlarda alt sinif (GesturableRichTextView) bu
 * kancada kendi dokunma/uzun basma dinleyicilerini kurar; enjeksiyon bu cagridan SONRA yapilmali.
 *
 * Sinif adi R8 tarafindan korunuyor (com.reddit.richtext.*), metot adlari (c, a...) karisik.
 */
internal object RichTextViewSetItemsFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/richtext/RichTextView;",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            definingClass = "Lcom/reddit/richtext/RichTextView;",
            parameters = listOf("Landroid/view/View;", "Z"),
            returnType = "V"
        )
    )
)

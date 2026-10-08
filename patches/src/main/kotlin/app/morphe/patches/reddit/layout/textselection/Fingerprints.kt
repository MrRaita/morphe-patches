package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Legacy (View based) rich text: com.reddit.richtext.RichTextView.setRichTextItems(List).
 * After each child view is added the empty hook c(View, boolean) is called. Comment subclasses
 * install their gesture listeners in that hook, so injection must happen after the call.
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

/**
 * Compose rich text renderer (post body and comment body). Matched by the Compose group key
 * of its CompositionLocalProvider lambda, which survives R8 renaming.
 */
internal object ComposeRichTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        literal(1546229805L)
    )
)

/**
 * Compose foundation SelectionContainer(modifier, content), matched by its restart group key.
 * Expected shape: (int changed, int default, Composer, Modifier, ComposableLambdaImpl) -> void.
 */
internal object ComposeSelectionContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("I", "I", "L", "L", "Landroidx/compose/runtime/internal/a;"),
    filters = listOf(
        literal(1949207773L)
    )
)

/**
 * Compose runtime rememberComposableLambda(key, block, composer).
 */
internal object ComposeComposableLambdaFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroidx/compose/runtime/internal/a;",
    parameters = listOf("I", "L", "L"),
    filters = listOf(
        newInstance("Landroidx/compose/runtime/internal/a;")
    )
)

/**
 * Compose comment tree event handler for collapsing a thread.
 */
internal object CollapseThreadEventHandlerFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
    filters = listOf(
        newInstance($$"Lcom/reddit/comments/events/handler/OnClickCollapseThreadEventHandler$handle$1;")
    )
)

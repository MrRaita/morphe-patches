// TASLAK - derlenmedi, test edilmedi.
// Hedef: .../patches/reddit/layout/textselection/EnableTextSelectionPatch.kt
package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/EnableTextSelectionPatch;"

@Suppress("unused")
val enableTextSelectionPatch = bytecodePatch(
    name = "Enable text selection",
    description = "Adds an option to make post and comment text selectable and copyable."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        RichTextViewSetItemsFingerprint.let {
            it.method.apply {
                val callIndex = it.instructionMatches.first().index
                // invoke-virtual { this, view, flag }, RichTextView->c(View;Z)V
                val viewRegister = getInstruction<FiveRegisterInstruction>(callIndex).registerD

                addInstructions(
                    callIndex + 1,
                    "invoke-static { v$viewRegister }, $EXTENSION_CLASS->makeSelectable(Landroid/view/View;)V"
                )
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

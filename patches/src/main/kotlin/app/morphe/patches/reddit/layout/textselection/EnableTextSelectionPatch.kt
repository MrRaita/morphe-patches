package app.morphe.patches.reddit.layout.textselection

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/EnableTextSelectionPatch;"

// Arbitrary Compose group key for the wrapper lambda.
private const val WRAPPER_LAMBDA_KEY = 0x4D4F5250

@Suppress("unused")
val enableTextSelectionPatch = bytecodePatch(
    name = "Enable text selection",
    description = "Adds an option to make post and comment text selectable and copyable. " +
        "Disables comment collapsing."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        // region Legacy View based rich text.

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

        // endregion

        // region Compose rich text: wrap the renderer in SelectionContainer.

        val lambdaMethod = ComposeComposableLambdaFingerprint.method
        val selectionMethod = ComposeSelectionContainerFingerprint.method
        val richTextMethod = ComposeRichTextFingerprint.method

        fun methodReference(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod) =
            "${method.definingClass}->${method.name}(" +
                method.parameterTypes.joinToString("") + ")${method.returnType}"

        val paramTypes = richTextMethod.parameterTypes.map { it.toString() }
        // Trailing int parameters are the Compose $changed/$default flags, the parameter
        // before them is the Composer.
        val composerIndex = paramTypes.indexOfLast { it != "I" }
        if (composerIndex < 0 || composerIndex == paramTypes.size - 1) {
            throw PatchException("Unexpected Compose rich text signature: $paramTypes")
        }
        val composerRegister = "p$composerIndex"
        val lambdaParamTypes = lambdaMethod.parameterTypes.map { it.toString() }
        val blockType = lambdaParamTypes[1]

        val packArguments = buildString {
            paramTypes.forEachIndexed { index, type ->
                appendLine("const/16 v1, $index")
                when (type) {
                    // Parameter registers are above v15, so range invokes are required.
                    "Z" -> {
                        appendLine("invoke-static/range { p$index .. p$index }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;")
                        appendLine("move-result-object v2")
                        appendLine("aput-object v2, v0, v1")
                    }
                    "I" -> {
                        appendLine("invoke-static/range { p$index .. p$index }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;")
                        appendLine("move-result-object v2")
                        appendLine("aput-object v2, v0, v1")
                    }
                    "J", "D", "F", "B", "S", "C" ->
                        throw PatchException("Unsupported primitive parameter type: $type")
                    else -> appendLine("aput-object p$index, v0, v1")
                }
            }
        }

        richTextMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->shouldWrapRichText()Z
                move-result v0
                if-eqz v0, :morphe_original

                const/16 v0, ${paramTypes.size}
                new-array v0, v0, [Ljava/lang/Object;
                $packArguments
                const-class v1, ${richTextMethod.definingClass}
                const-string v2, "${richTextMethod.name}"
                invoke-static { v1, v2, v0 }, $EXTENSION_CLASS->createRichTextContent(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v1
                check-cast v1, $blockType
                const v2, $WRAPPER_LAMBDA_KEY
                move-object/from16 v3, $composerRegister
                invoke-static { v2, v1, v3 }, ${methodReference(lambdaMethod)}
                move-result-object v1

                const/16 v0, 48
                const/4 v2, 0x1
                move-object/from16 v3, $composerRegister
                const/4 v4, 0x0
                invoke-static { v0, v2, v3, v4, v1 }, ${methodReference(selectionMethod)}
                return-void

                :morphe_original
                nop
            """
        )

        // endregion

        // region Disable comment collapsing on click / long press (Compose comment tree).

        CommentClickEventHandlerFingerprint.method.apply {
            val unitField = implementation!!.instructions
                .filterIsInstance<ReferenceInstruction>()
                .firstOrNull {
                    it.opcode == Opcode.SGET_OBJECT &&
                        (it.reference as? FieldReference)?.definingClass == "Lkotlin/Unit;"
                }?.reference ?: throw PatchException("Could not find Unit instance field")

            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->shouldBlockCommentCollapse()Z
                    move-result v0
                    if-eqz v0, :morphe_collapse_original
                    sget-object v0, $unitField
                    return-object v0
                    :morphe_collapse_original
                    nop
                """
            )
        }

        // endregion

        // region Selection tap detection: a drag (even a small one) must not clear the selection.

        SelectionTapDetectionFingerprint.let {
            it.method.addInstructions(
                it.instructionMatches.first().index,
                "invoke-static { }, $EXTENSION_CLASS->beginSelectionTapDetection()V"
            )
        }

        PointersUpSlopDetectionFingerprint.let {
            it.method.apply {
                val callIndex = it.instructionMatches.first().index
                val moveResult = getInstruction<OneRegisterInstruction>(callIndex + 1)
                if (moveResult.opcode != Opcode.MOVE_RESULT) {
                    throw PatchException("Unexpected instruction after touch slop call")
                }
                val slopRegister = moveResult.registerA

                addInstructions(
                    callIndex + 2,
                    """
                        invoke-static/range { v$slopRegister .. v$slopRegister }, $EXTENSION_CLASS->adjustSelectionTapSlop(F)F
                        move-result v$slopRegister
                    """
                )
            }
        }

        // endregion

        // region Temporary diagnostics: report who clears the selection. Failing here is not fatal.

        try {
            SelectionReleaseFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->onSelectionRelease()V"
            )
        } catch (e: PatchException) {
            // Diagnostics only.
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

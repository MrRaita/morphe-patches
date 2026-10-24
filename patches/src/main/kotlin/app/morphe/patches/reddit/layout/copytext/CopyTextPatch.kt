/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3665
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.copytext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFreeRegister
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/CopyTextPatch;"

private const val EXTENSION_RICH_TEXT_CONTENT_CLASS =
    $$"Lapp/morphe/extension/reddit/patches/CopyTextPatch$RichTextContent;"

private const val EXTENSION_EXPANDED_TEXT_CONTENT_CLASS =
    $$"Lapp/morphe/extension/reddit/patches/CopyTextPatch$ExpandedTextContent;"

private const val EXTENSION_SELECTION_MANAGER_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/CopyTextPatch$SelectionManagerInterface;"

// Arbitrary Compose group key of the lambda passed to SelectionContainer.
private const val SELECTION_CONTENT_LAMBDA_KEY = 0x4D4F5250

/**
 * A Compose composable that is wrapped in a SelectionContainer, and the extension code used for it.
 */
private class SelectionContainerWrapper(
    val composable: Fingerprint,
    val shouldWrapMethod: String,
    val createContentMethod: String,
    val contentClass: String,
    val invokeStub: Fingerprint,
    val invokeMethodName: String
)

/**
 * Wraps the Compose rich text renderer, used by post and comment bodies.
 */
private val richTextWrapper = SelectionContainerWrapper(
    composable = ComposeRichTextFingerprint,
    shouldWrapMethod = "shouldWrapRichText",
    createContentMethod = "createRichTextContent",
    contentClass = EXTENSION_RICH_TEXT_CONTENT_CLASS,
    invokeStub = InvokeRichTextFingerprint,
    invokeMethodName = "patch_invokeRichText"
)

/**
 * Wraps the expanded title and body text of the media viewer.
 */
private val expandedTextWrapper = SelectionContainerWrapper(
    composable = ComposeExpandedTextFingerprint,
    shouldWrapMethod = "shouldWrapExpandedText",
    createContentMethod = "createExpandedTextContent",
    contentClass = EXTENSION_EXPANDED_TEXT_CONTENT_CLASS,
    invokeStub = InvokeExpandedTextFingerprint,
    invokeMethodName = "patch_invokeExpandedText"
)

/**
 * Replaces the start of the composable: if the setting is on, the composable is invoked again
 * inside a Compose SelectionContainer, with its original arguments.
 */
private fun BytecodePatchContext.wrapInSelectionContainer(wrapper: SelectionContainerWrapper) {
    // Match everything first, so nothing is changed if any of it is missing.
    val targetMethod = wrapper.composable.method
    val invokeStubMethod = wrapper.invokeStub.method
    val paramTypes = targetMethod.parameterTypes.map { it.toString() }

    // rememberComposableLambda(key, block, composer)
    val composableLambdaMethod = ComposeComposableLambdaFingerprint.method
    val blockType = composableLambdaMethod.parameterTypes[1]
    val composerIndex = paramTypes.indexOf(composableLambdaMethod.parameterTypes[2].toString())
    val composerRegister = "p$composerIndex"

    // Parameter registers are above v15, which is why range invokes and
    // move-object/from16 are used.
    val packArguments = buildString {
        paramTypes.forEachIndexed { index, type ->
            appendLine("const/16 v1, $index")
            when (type) {
                "Z" -> appendLine(
                    "invoke-static/range { p$index .. p$index }, " +
                        "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"
                )
                "I" -> appendLine(
                    "invoke-static/range { p$index .. p$index }, " +
                        "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"
                )
                "J", "D", "F", "B", "S", "C" ->
                    throw PatchException("Unsupported primitive parameter type: $type")
                else -> appendLine("aput-object p$index, v0, v1")
            }
            if (type == "Z" || type == "I") {
                appendLine("move-result-object v2")
                appendLine("aput-object v2, v0, v1")
            }
        }
    }

    targetMethod.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $EXTENSION_CLASS->${wrapper.shouldWrapMethod}()Z
            move-result v0
            if-eqz v0, :morphe_original

            const/16 v0, ${paramTypes.size}
            new-array v0, v0, [Ljava/lang/Object;
            $packArguments
            invoke-static { v0 }, $EXTENSION_CLASS->${wrapper.createContentMethod}([Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v1
            check-cast v1, $blockType
            const v2, $SELECTION_CONTENT_LAMBDA_KEY
            move-object/from16 v3, $composerRegister
            invoke-static { v2, v1, v3 }, $composableLambdaMethod
            move-result-object v1

            const/16 v0, 48
            const/4 v2, 0x1
            move-object/from16 v3, $composerRegister
            const/4 v4, 0x0
            invoke-static { v0, v2, v3, v4, v1 }, ${ComposeSelectionContainerFingerprint.method}
            return-void

            :morphe_original
            nop
        """
    )

    // Add a method that invokes the composable with arguments packed by the code above.
    // The composer argument is replaced with the composer of the SelectionContainer content.
    // The registers v0 to v(n-1) hold the arguments, v(n) is the array index.
    val indexRegister = paramTypes.size
    val unpackArguments = buildString {
        paramTypes.forEachIndexed { index, type ->
            if (index == composerIndex) {
                appendLine("move-object/from16 v$index, p1")
                return@forEachIndexed
            }

            appendLine("const/16 v$indexRegister, $index")
            appendLine("aget-object v$index, p0, v$indexRegister")
            when (type) {
                "Z" -> appendLine(
                    """
                        check-cast v$index, Ljava/lang/Boolean;
                        invoke-virtual/range { v$index .. v$index }, Ljava/lang/Boolean;->booleanValue()Z
                        move-result v$index
                    """
                )
                "I" -> appendLine(
                    """
                        check-cast v$index, Ljava/lang/Integer;
                        invoke-virtual/range { v$index .. v$index }, Ljava/lang/Integer;->intValue()I
                        move-result v$index
                    """
                )
                else -> appendLine("check-cast v$index, $type")
            }
        }
    }

    val invokeTargetMethod = ImmutableMethod(
        targetMethod.definingClass,
        wrapper.invokeMethodName,
        listOf(
            ImmutableMethodParameter("[Ljava/lang/Object;", null, null),
            ImmutableMethodParameter("Ljava/lang/Object;", null, null)
        ),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(paramTypes.size + 3)
    ).toMutable().apply {
        addInstructions(
            0,
            """
                $unpackArguments
                invoke-static/range { v0 .. v${paramTypes.size - 1} }, $targetMethod
                return-void
            """
        )
    }
    wrapper.composable.classDef.methods.add(invokeTargetMethod)

    invokeStubMethod.addInstructions(
        0,
        """
            invoke-static { p0, p1 }, $invokeTargetMethod
            return-void
        """
    )

    // Make the SelectionContainer content a Compose lambda.
    mutableClassDefBy(wrapper.contentClass).apply {
        interfaces.add("Lkotlin/jvm/functions/Function2;")
        methods.add(
            ImmutableMethod(
                type,
                "invoke",
                listOf(
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null)
                ),
                "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                null,
                null,
                MutableMethodImplementation(4)
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0, p1 }, $type->render(Ljava/lang/Object;)V
                        const/4 v0, 0x0
                        return-object v0
                    """
                )
            }
        )
    }
}

@Suppress("unused")
val copyTextPatch = bytecodePatch(
    name = "Copy text",
    description = "Adds an option to make post and comment text selectable and copyable. " +
        "Disables collapsing comments. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Copy text' does not work with Reddit 2026.23.0 and earlier"
            )
        }

        // region Wrap the rich text renderer and the media viewer text in a SelectionContainer.

        wrapInSelectionContainer(richTextWrapper)

        // The media viewer is not in every supported version.
        try {
            wrapInSelectionContainer(expandedTextWrapper)
        } catch (e: PatchException) {
            Logger.getLogger(this::class.java.name).warning(
                "Media viewer text cannot be copied: ${e.message}"
            )
        }

        // endregion

        // region Diagnostics: which media viewer text composables are used.

        try {
            ExpandableTitleAndBodyTextFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->debugExpandableText(Z)V"
            )
            CollapsedTitleAndBodyTextFingerprint.method.addInstruction(
                0,
                "invoke-static { }, $EXTENSION_CLASS->debugCollapsedText()V"
            )
        } catch (e: PatchException) {
            Logger.getLogger(this::class.java.name).warning("Diagnostics hooks not applied: ${e.message}")
        }

        // Diagnostics of the Compose selection internals.
        try {
            listOf(
                SelectionTouchModeGestureFingerprint to "selectionGestureInstalled",
                SelectionFirstPressFingerprint to "touchSelectionFirstPress",
                SelectionSuggestLongPressFingerprint to "suggestSelectionForLongPress"
            ).forEach { (fingerprint, name) ->
                fingerprint.method.addInstructions(
                    0,
                    """
                        const-string v0, "$name"
                        invoke-static { v0 }, $EXTENSION_CLASS->debugProbe(Ljava/lang/String;)V
                    """
                )
            }

            SelectionRegistrarSortFingerprint.method.apply {
                val returnIndex = implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstruction(
                    returnIndex,
                    "invoke-static { v$register }, $EXTENSION_CLASS->debugSelectables(Ljava/util/ArrayList;)V"
                )
            }
        } catch (e: PatchException) {
            Logger.getLogger(this::class.java.name).warning("Selection diagnostics hooks not applied: ${e.message}")
        }

        // endregion

        // region Disable collapsing comments, expanding a collapsed comment keeps working.

        CommentClickEventHandlerFingerprint.let {
            it.method.apply {
                val branchIndex = it.instructionMatches[3].index
                val collapsedRegister = getInstruction<OneRegisterInstruction>(branchIndex).registerA
                val freeRegister = findFreeRegister(branchIndex, collapsedRegister)
                val unitField = getInstruction<ReferenceInstruction>(it.instructionMatches.last().index).reference

                addInstructionsWithLabels(
                    branchIndex,
                    """
                        invoke-static/range { v$collapsedRegister .. v$collapsedRegister }, $EXTENSION_CLASS->shouldBlockCommentCollapse(Z)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :morphe_collapse_allowed
                        sget-object v$freeRegister, $unitField
                        return-object v$freeRegister
                        :morphe_collapse_allowed
                        nop
                    """
                )
            }
        }

        // endregion

        // region A slight drag must not be treated as a tap that clears the selection.

        SelectionTapDetectionFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.first().index,
                "invoke-static { }, $EXTENSION_CLASS->beginSelectionTapDetection()V"
            )
        }

        PointersUpSlopDetectionFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = this.getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->adjustSelectionTapSlop(F)F
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region Keep the selection while touching and scrolling, a tap clears it.

        SelectionReleaseFingerprint.let {
            // The extension clears selections by calling the release method.
            it.classDef.apply {
                interfaces.add(EXTENSION_SELECTION_MANAGER_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_release",
                        listOf(),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(1)
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0 }, ${it.method}
                                return-void
                            """
                        )
                    }
                )
            }

            it.method.addInstruction(
                0,
                "invoke-static { p0 }, $EXTENSION_CLASS->onSelectionRelease($EXTENSION_SELECTION_MANAGER_INTERFACE)V"
            )
        }

        SelectionChangedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0, p1 }, $EXTENSION_CLASS->onSelectionChanged(${EXTENSION_SELECTION_MANAGER_INTERFACE}Ljava/lang/Object;)V"
        )

        ComposeDispatchTouchEventFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->onComposeTouchEvent(Landroid/view/MotionEvent;)V"
        )

        FocusClearFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->shouldBlockFocusClear()Z
                move-result v0
                if-eqz v0, :morphe_clear_focus
                return-void
                :morphe_clear_focus
                nop
            """
        )

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

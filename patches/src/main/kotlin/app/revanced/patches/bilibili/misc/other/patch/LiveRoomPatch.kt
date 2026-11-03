package app.revanced.patches.bilibili.misc.other.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

@Patch(
    name = "Disable live room double click",
    description = "禁用直播间双击点赞",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object LiveRoomPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        context.findClass("Lcom/bilibili/bililive/room/ui/roomv3/player/container/LiveRoomPlayerContainerView;")
            ?.mutableClass?.run {
                methods.find { it.name == "onDoubleTap" }?.addInstructionsWithLabels(
                    0, """
                    invoke-static {p0}, Lapp/revanced/bilibili/patches/LiveRoomPatch;->onDoubleTap(${this.type})Z
                    move-result v0
                    if-eqz v0, :jump
                    const/4 v0, 0x1
                    return v0
                    :jump
                    nop
                """.trimIndent()
                )
                val clock = "Landroid/os/SystemClock;->elapsedRealtime()J"
                val info = methods.filter { it.returnType == "V" }.firstNotNullOfOrNull { m ->
                    val ops = m.implementation?.instructions ?: return@firstNotNullOfOrNull null
                    val index = ops.indexOfFirst { it is Instruction35c && it.reference.toString() == clock }
                    if (index < 0) return@firstNotNullOfOrNull null
                    val result = ops.getOrNull(index + 1)
                    check(result is OneRegisterInstruction && result.opcode == Opcode.MOVE_RESULT_WIDE)
                    Triple(m, index, result.registerA)
                }
                if (info != null) {
                    val (m, index, reg) = info
                    // Preserve branches to the clock call. Its result registers are free only
                    // before move-result-wide, which restores both halves for the original code.
                    m.replaceInstruction(index, "invoke-static {}, Lapp/revanced/bilibili/patches/LiveRoomPatch;->disableLiveRoomDoubleClick()Z")
                    m.addInstructionsWithLabels(
                        index + 1, """
                        move-result v$reg
                        if-eqz v$reg, :clock
                        return-void
                        :clock
                        invoke-static {}, $clock
                    """.trimIndent()
                    )
                }
            } ?: throw PatchException("not found LiveRoomPlayerContainerView class")
    }
}

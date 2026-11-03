package app.revanced.patches.bilibili.misc.json.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Patch(
    name = "Keep trailing home feed card",
    description = "保留双列首页推荐中落单的最后一张卡片",
    compatiblePackages = [CompatiblePackage(name = "tv.danmaku.bili", versions = ["9.17.0"])]
)
object KeepHomeFeedCardsPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        // The 9.17 reducer calls this helper when refreshing and appending. It counts
        // consecutive small cards from the end and removes one solely on odd parity.
        // The separate loading-footer cleanup must still run.
        val helper = context.findClass("Lcom/bilibili/pegasus/vm/w;")?.mutableClass?.methods?.singleOrNull { m ->
            if (m.returnType != "V" || m.parameterTypes != listOf("Ljava/util/List;")) return@singleOrNull false
            val refs = m.implementation?.instructions?.filterIsInstance<ReferenceInstruction>()
                ?.map { it.reference.toString() }.orEmpty()
            "Lcom/bilibili/pegasus/HolderStyle;->isSmallCard()Z" in refs &&
                    "Lkotlin/collections/CollectionsKt;->removeLastOrNull(Ljava/util/List;)Ljava/lang/Object;" in refs
        } ?: throw PatchException("Cannot identify 9.17 home feed tail trimming")
        helper.addInstructions(0, "return-void")
    }
}

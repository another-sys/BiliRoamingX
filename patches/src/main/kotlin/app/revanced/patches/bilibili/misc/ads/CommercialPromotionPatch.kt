package app.revanced.patches.bilibili.misc.ads

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.patches.bilibili.utils.*
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Patch(
    name = "Commercial promotion filters",
    description = "过滤 9.17 的开屏、暂停广告和商业导航项",
    compatiblePackages = [CompatiblePackage(name = "tv.danmaku.bili", versions = ["9.17.0"])]
)
object CommercialPromotionPatch : BytecodePatch() {
    private const val filter = "Lapp/revanced/bilibili/patches/PromotionFilter;"
    private const val settings = "Lapp/revanced/bilibili/patches/SettingsTransfer;"

    override fun execute(context: BytecodeContext) {
        // Both official nullable factories accept "no ad", including cached cold-start orders.
        val splash = "Ltv/danmaku/bili/splash/ad/core/SplashManager;".toClassDef(context).proxy(context)
        for (result in listOf("ad/model/SplashOrder;", "ad/page/BaseSplash;")) {
            splash.methods.single { it.returnType == "Ltv/danmaku/bili/splash/$result" }.addInstructionsWithLabels(0, """
                invoke-static {}, $filter->blockSplash()Z
                move-result v0
                if-eqz v0, :original
                const/4 v0, 0x0
                return-object v0
                :original
                nop
            """.trimIndent())
        }
        // The pause-ad coroutine reads a non-null list. Leave the normal pause event alone.
        val pause = "Lcom/bapis/bilibili/app/viewunite/v1/KPauseAds;".toClassDef(context).proxy(context)
        pause.methods.single { it.name == "getList" && it.returnType == "Ljava/util/List;" }.addInstructionsWithLabels(0, """
            invoke-static {}, $settings->blockUpRcmdAds()Z
            move-result v0
            if-eqz v0, :original
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v0
            return-object v0
            :original
            nop
        """.trimIndent())

        // Pinned 9170300 KMP model: name and URI (see HomeTabItemData.toString).
        val tab = "LZt1/m;".toClassDef(context).proxy(context)
        check(listOf("b", "c").all { n -> tab.fields.any { it.name == n && it.type == "Ljava/lang/String;" } })
        tab.interfaces.add("Lapp/revanced/bilibili/patches/PromotionEntry;")
        tab.methods.add(Method(tab.type, "brxHideEntry", "Z", AccessFlags.PUBLIC.value,
            implementation = MethodImplementation(registerCount = 3)).toMutable().apply {
            addInstructions("""
                iget-object v0, p0, ${tab.type}->b:Ljava/lang/String;
                iget-object v1, p0, ${tab.type}->c:Ljava/lang/String;
                invoke-static {v0, v1}, $filter->hidden(Ljava/lang/String;Ljava/lang/String;)Z
                move-result v0
                return v0
            """.trimIndent())
        })
        // Apply on deserialization and normal construction (including disk-cached tabs).
        val data = "LZt1/j;".toClassDef(context).proxy(context)
        val constructors = data.methods.filter { it.name == "<init>" && it.parameterTypes.lastOrNull() == "LZt1/q;" }
        check(constructors.size == 2)
        constructors.forEach { ctor ->
            val lists = ctor.parameterTypes.indices.filter { ctor.parameterTypes[it] == "Ljava/util/List;" }
            check(lists.size == 4)
            ctor.addInstructions(0, lists.joinToString("\n") {
                val reg = "p${it + 1}"
                "invoke-static/range {$reg .. $reg}, $filter->filterEntries(Ljava/util/List;)Ljava/util/List;\nmove-result-object $reg"
            })
        }
        val viewReply = "Lcom/bapis/bilibili/app/viewunite/v1/KViewReply;".toClassDef(context).proxy(context)
        viewReply.methods.single { it.name == "getCm" && it.returnType.endsWith("/KCM;") }.addInstructionsWithLabels(0, """
            invoke-static {}, $settings->blockUpRcmdAds()Z
            move-result v0
            if-eqz v0, :original
            const/4 v0, 0x0
            return-object v0
            :original
            nop
        """.trimIndent())

        // Filter typed mention groups before the video detail UI chooses a card to render.
        val mentionPkg = "Lcom/bilibili/playerbizcommon3/videomentioned/"
        val mentionType = "Lcom/bapis/bilibili/app/viewunite/common/KMentionType;"
        val group = "${mentionPkg}g;".toClassDef(context).proxy(context)
        check(group.fields.any { it.name == "c" && it.type == mentionType })
        group.interfaces.add("Lapp/revanced/bilibili/patches/PromotionEntry;")
        group.methods.add(Method(group.type, "brxHideEntry", "Z", AccessFlags.PUBLIC.value,
            implementation = MethodImplementation(registerCount = 2)).toMutable().apply {
            addInstructions("""
                iget-object v0, p0, ${group.type}->c:$mentionType
                invoke-virtual {v0}, $mentionType->getName()Ljava/lang/String;
                move-result-object v0
                invoke-static {v0}, $filter->hiddenMention(Ljava/lang/String;)Z
                move-result v0
                return v0
            """.trimIndent())
        })
        val mentionData = "${mentionPkg}VideoMentionedData;".toClassDef(context).proxy(context)
        mentionData.methods.filter { it.name == "<init>" && it.parameterTypes.lastOrNull() == "Ljava/util/Map;" }.also {
            check(it.size == 2)
        }.forEach { ctor ->
            val reg = "p${1 + ctor.parameterTypes.indexOf("Ljava/util/List;").also { check(it >= 0) }}"
            ctor.addInstructions(0, "invoke-static/range {$reg .. $reg}, $filter->filterEntries(Ljava/util/List;)Ljava/util/List;\nmove-result-object $reg")
        }

        // At this point v3 holds either AccountMine.sections_v2 or the built-in offline menu.
        val mine = "Ltv/danmaku/bili/ui/main2/mine/HomeUserCenterFragment;".toClassDef(context).proxy(context)
        val bind = mine.methods.single { it.name == "Wf" && it.parameterTypes.lastOrNull()?.endsWith("/AccountMine;") == true }
        val anchor = bind.implementation!!.instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                ref.definingClass == mine.type && ref.name == "ig"
            } == true
        }
        check(anchor > 0)
        bind.addInstructions(anchor + 1, "invoke-static {v3}, $filter->filterMine(Ljava/util/List;)Ljava/util/List;\nmove-result-object v3")

        // Mine V2 creates a local fallback subscription banner even without a server payload.
        // Return before the composable opens any groups, starts effects, or adds a layout node.
        val vip = "Ltv/danmaku/bili/ui/main2/mineV2/ui/MineVipModuleComposeKt;".toClassDef(context).proxy(context)
        vip.methods.single { it.name == "b" && it.parameterTypes.firstOrNull() == "Landroidx/compose/ui/Modifier;" }
            .addInstructionsWithLabels(0, """
                invoke-static {}, $filter->hideVipBanner()Z
                move-result v0
                if-eqz v0, :original
                return-void
                :original
                nop
            """.trimIndent())
    }
}

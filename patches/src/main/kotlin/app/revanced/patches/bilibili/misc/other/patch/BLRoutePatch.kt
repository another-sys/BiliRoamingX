package app.revanced.patches.bilibili.misc.other.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patches.bilibili.misc.other.fingerprints.BLRouteBuilderFingerprint
import app.revanced.patches.bilibili.misc.other.fingerprints.RouteRequestFingerprint
import app.revanced.patches.bilibili.utils.cloneMutable
import app.revanced.patches.bilibili.utils.isPublic
import app.revanced.patches.bilibili.utils.isStatic
import app.revanced.util.exception

import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private object VideoDetailPreloadFingerprint : MethodFingerprint(
    strings = listOf("player_preload", "parser preload data to json obj error", "VideoDetailIntentParser"),
    customFingerprint = { method, _ -> method.name == "<init>" }
)

@Patch(
    name = "BL route intercept",
    description = "哔哩哔哩页面路由修改",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object BLRoutePatch : BytecodePatch(setOf(BLRouteBuilderFingerprint, RouteRequestFingerprint, VideoDetailPreloadFingerprint)) {
    override fun execute(context: BytecodeContext) {
        BLRouteBuilderFingerprint.result?.mutableClass?.methods?.find { m ->
            m.name == "<init>" && m.parameterTypes.let { it.size == 1 && it[0] == "Landroid/net/Uri;" }
        }?.addInstructions(
            0, """
            invoke-static {p1}, Lapp/revanced/bilibili/patches/BLRoutePatch;->intercept(Landroid/net/Uri;)Landroid/net/Uri;
            move-result-object p1
        """.trimIndent()
        ) // Recent clients merged Builder into RouteRequest.
        val result = RouteRequestFingerprint.result
        result?.mutableClass?.methods?.find { m ->
            m.name == "<init>" && m.parameterTypes == listOf("Landroid/net/Uri;")
        }?.addInstructions(
            0, """
            invoke-static {p1}, Lapp/revanced/bilibili/patches/BLRoutePatch;->intercept(Landroid/net/Uri;)Landroid/net/Uri;
            move-result-object p1
        """.trimIndent()
        ) ?: throw RouteRequestFingerprint.exception
        (VideoDetailPreloadFingerprint.result ?: throw VideoDetailPreloadFingerprint.exception).run {
            val index = scanResult.stringsScanResult!!.matches.first { it.string == "player_preload" }.index + 1
            val call = mutableMethod.implementation!!.instructions[index]
            if (call is FiveRegisterInstruction && (call as? ReferenceInstruction)?.reference.toString() ==
                "Landroid/os/Bundle;->getString(Ljava/lang/String;)Ljava/lang/String;") {
                mutableMethod.replaceInstruction(index,
                    "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                            "Lapp/revanced/bilibili/patches/BLRoutePatch;->videoDetailPreload(Landroid/os/Bundle;Ljava/lang/String;)Ljava/lang/String;")
            } else throw VideoDetailPreloadFingerprint.exception
        }
        val routeToMethod = context.classes.asSequence().flatMap { it.methods }.first { m ->
            m.accessFlags.let { it.isStatic() && it.isPublic() }
                    && m.parameterTypes == listOf(
                result.classDef.type, "Landroid/content/Context;"
            )
        }
        val utilsClass = context.findClass("Lapp/revanced/bilibili/utils/Utils;")!!.mutableClass
        val utilsRouteToMethod = utilsClass.methods.first { it.name == "routeTo" }
        utilsRouteToMethod.also { utilsClass.methods.remove(it) }
            .cloneMutable(registerCount = 3, clearImplementation = true).apply {
                addInstructions(
                    """
                    new-instance v0, ${result.classDef}
                    invoke-direct {v0, p0}, ${result.classDef}-><init>(Landroid/net/Uri;)V
                    invoke-static {v0, p1}, $routeToMethod
                    return-void
                """.trimIndent()
                )
            }.also { utilsClass.methods.add(it) }
    }
}

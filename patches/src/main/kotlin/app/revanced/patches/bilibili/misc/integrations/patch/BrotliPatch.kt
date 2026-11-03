package app.revanced.patches.bilibili.misc.integrations.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patches.bilibili.misc.integrations.fingerprints.BrotliFingerprint
import app.revanced.patches.bilibili.utils.removeFinal
import app.revanced.util.exception

@Patch(
    name = "Brotli",
    description = "集成Brotli",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object BrotliPatch : BytecodePatch(setOf(BrotliFingerprint)) {
    override fun execute(context: BytecodeContext) {
        BrotliFingerprint.result?.classDef?.run {
            // 9.17 makes the host decoder final; the integration adapter subclasses it.
            context.findClass(this.type)!!.mutableClass.apply {
                accessFlags = accessFlags.removeFinal()
            }
            val brotliClass = context.findClass("Lapp/revanced/bilibili/api/BrotliInputStream;")!!
            brotliClass.mutableClass.setSuperClass(this.type)
            brotliClass.mutableClass.methods.first { it.name == "<init>" }.replaceInstruction(
                0, """
                invoke-direct {p0, p1}, ${this.type}-><init>(Ljava/io/InputStream;)V
            """.trimIndent()
            )
        } ?: throw BrotliFingerprint.exception
    }
}

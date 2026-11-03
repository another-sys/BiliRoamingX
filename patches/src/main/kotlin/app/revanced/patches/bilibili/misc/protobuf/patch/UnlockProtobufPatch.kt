package app.revanced.patches.bilibili.misc.protobuf.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.bilibili.utils.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Patch(
    name = "Unlock ProtoBuf",
    description = "公开ProtoBuf实体构建方法",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object UnlockProtobufPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        val methodNameRegex = Regex("""^((set|add|remove|clear|merge|getMutable)\w+)|<init>$""")
        val promoted = mutableSetOf<String>()
        context.classes.filter { it.superclass == "Lcom/google/protobuf/GeneratedMessageLite;" }
            .flatMap { it.proxy(context).methods }.forEach { m ->
                // step 1, private to public
                if (m.accessFlags.isPrivate()
                    && !m.accessFlags.isStatic()
                    && m.name.matches(methodNameRegex)
                ) {
                    m.accessFlags = m.accessFlags.toPublic()
                    // Constructors must continue to use invoke-direct.
                    if (m.name != "<init>") promoted.add(m.toString())
                }
            }
        // R8 now moves calls into builders and ordinary helper methods. Updating
        // only the first instruction of synthetic methods causes ICCE at startup.
        context.classes.forEach { clazz ->
            clazz.methods.filter { method ->
                method.implementation?.instructions?.any { inst ->
                    (inst.opcode == Opcode.INVOKE_DIRECT || inst.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                            (inst as ReferenceInstruction).reference.toString() in promoted
                } == true
            }.forEach { original ->
                val method = clazz.proxy(context).methods.first { it == original }
                method.implementation!!.instructions.toList().forEachIndexed { index, inst ->
                    when {
                        inst is BuilderInstruction35c && inst.opcode == Opcode.INVOKE_DIRECT &&
                                inst.reference.toString() in promoted -> method.replaceInstruction(
                            index, "invoke-virtual {${inst.args()}}, ${inst.reference}"
                        )
                        inst is BuilderInstruction3rc && inst.opcode == Opcode.INVOKE_DIRECT_RANGE &&
                                inst.reference.toString() in promoted -> method.replaceInstruction(
                            index, "invoke-virtual/range {v${inst.startRegister} .. v${inst.startRegister + inst.registerCount - 1}}, ${inst.reference}"
                        )
                    }
                }
            }
        }
        // add back XXX method copy from executeXXX method
        context.classes.filter {
            it.type.startsWith("Lcom/bapis") && it.type.endsWith("Moss;")
        }.forEach { c ->
            c.proxy(context).run {
                val newMethods = mutableListOf<MutableMethod>()
                methods.forEach { m ->
                    if (m.name.startsWith("execute") && m.name.length > 7 && m.returnType != "V") {
                        val noPrefixName = m.name.substringAfter("execute").replaceFirstChar { it.lowercaseChar() }
                        m.cloneMutable(name = noPrefixName).takeIf { mm ->
                            methods.none { it == mm }
                        }?.let { newMethods.add(it) }
                    }
                }
                methods.addAll(newMethods)
            }
        }
    }
}

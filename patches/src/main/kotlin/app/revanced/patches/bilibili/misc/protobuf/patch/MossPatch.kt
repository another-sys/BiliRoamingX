package app.revanced.patches.bilibili.misc.protobuf.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patches.bilibili.utils.cloneMutable
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.revanced.patches.bilibili.misc.protobuf.fingerprints.MossOkHttpInterceptorFingerprint
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patches.bilibili.misc.protobuf.fingerprints.MossMiddlewareGaiaFingerprint
import app.revanced.patches.bilibili.misc.protobuf.fingerprints.MossServiceFingerprint
import app.revanced.util.exception
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

@Patch(
    name = "Moss",
    description = "gRPC 通信引擎服务 hook",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object MossPatch : BytecodePatch(setOf(MossServiceFingerprint, MossMiddlewareGaiaFingerprint, MossOkHttpInterceptorFingerprint)) {
    override fun execute(context: BytecodeContext) {
        MossServiceFingerprint.result?.mutableClass?.methods?.let { methods ->
            methods.find { it.name == "blockingUnaryCall" }?.run {
                // Wrap the complete implementation: newer engines return from several
                // branches, so replacing only the last invoke loses response hooks.
                val original = this
                val wrapper = cloneMutable(registerCount = 7, clearImplementation = true)
                original.name += "_Origin"
                wrapper.addInstructionsWithLabels(0, """
                    invoke-static {p2}, Lapp/revanced/bilibili/patches/protobuf/MossPatch;->hookBlockingBefore(Lcom/google/protobuf/GeneratedMessageLite;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :proceed
                    sget-object v1, Lapp/revanced/bilibili/meta/HookFlags;->STOP_EXECUTION:Ljava/lang/Object;
                    if-ne v0, v1, :custom
                    const/4 v0, 0x0
                    return-object v0
                    :custom
                    check-cast v0, Lcom/google/protobuf/GeneratedMessageLite;
                    return-object v0
                    :proceed
                    const/4 v1, 0x0
                    const/4 v2, 0x0
                    invoke-virtual {p0, p1, p2, p3}, $original
                    move-result-object v1
                    goto :after
                    move-exception v2
                    :after
                    invoke-static {p2, v1, v2}, Lapp/revanced/bilibili/patches/protobuf/MossPatch;->hookBlockingAfter(Lcom/google/protobuf/GeneratedMessageLite;Lcom/google/protobuf/GeneratedMessageLite;Lcom/bilibili/lib/moss/api/MossException;)Lcom/google/protobuf/GeneratedMessageLite;
                    move-result-object v0
                    return-object v0
                """.trimIndent())
                val impl = wrapper.implementation!!
                val invokeIndex = impl.instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.name == original.name
                }
                val catchIndex = impl.instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
                impl.addCatch(
                    "Lcom/bilibili/lib/moss/api/MossException;",
                    impl.newLabelForIndex(invokeIndex),
                    impl.newLabelForIndex(invokeIndex + 2),
                    impl.newLabelForIndex(catchIndex)
                )
                methods.add(wrapper)
            } ?: throw PatchException("Missing Moss blockingUnaryCall")
            val gainType = MossMiddlewareGaiaFingerprint.result?.classDef?.type
            methods.filter { it.name == "asyncUnaryCall" || it.name == "asyncServerStreamingCall" }.forEach {
                it.addInstructionsWithLabels(
                    0, """
                    ${
                        if (gainType != null) {
                            """
                                instance-of v0, p3, $gainType
                                if-nez v0, :jump
                            """.trimIndent()
                        } else ""
                    }
                    invoke-static {p2, p3}, Lapp/revanced/bilibili/patches/protobuf/MossPatch;->hookAsyncBefore(Lcom/google/protobuf/GeneratedMessageLite;Lcom/bilibili/lib/moss/api/MossResponseHandler;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :jump
                    sget-object v1, Lapp/revanced/bilibili/meta/HookFlags;->STOP_EXECUTION:Ljava/lang/Object;
                    if-eq v0, v1, :stop_execution
                    goto :modify_handler
                    :stop_execution
                    return-void
                    :modify_handler
                    check-cast v0, Lcom/bilibili/lib/moss/api/MossResponseHandler;
                    move-object p3, v0
                    :jump
                    nop
                """.trimIndent()
                )
            }
        } ?: throw MossServiceFingerprint.exception
        val interceptor = MossOkHttpInterceptorFingerprint.result?.mutableMethod
            ?: throw MossOkHttpInterceptorFingerprint.exception
        val instructions = interceptor.implementation!!.instructions
        val index = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_INTERFACE && it.getReference<MethodReference>()?.toString() ==
                    "Lokhttp3/Interceptor\$Chain;->proceed(Lokhttp3/Request;)Lokhttp3/Response;"
        }
        if (index < 0) throw PatchException("Missing Moss OkHttp proceed call")
        val request = (instructions[index] as Instruction35c).registerD
        interceptor.addInstructions(index, """
            invoke-static {v$request}, Lapp/revanced/bilibili/patches/protobuf/MossPatch;->hookBeforeRequestOkHttp(Lokhttp3/Request;)Lokhttp3/Request;
            move-result-object v$request
        """.trimIndent())
    }
}

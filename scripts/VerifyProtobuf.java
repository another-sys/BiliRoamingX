import java.io.File;
import java.util.HashSet;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;

/** Detect invalid direct calls left behind after making protobuf setters public. */
public class VerifyProtobuf {
    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        var virtualMethods = new HashSet<String>();
        for (var entry : dex.getDexEntryNames()) {
            for (var cls : dex.getEntry(entry).getDexFile().getClasses()) {
                if (!"Lcom/google/protobuf/GeneratedMessageLite;".equals(cls.getSuperclass())) continue;
                for (var method : cls.getMethods()) {
                    if (!method.getName().startsWith("<") &&
                            !AccessFlags.PRIVATE.isSet(method.getAccessFlags()) &&
                            !AccessFlags.STATIC.isSet(method.getAccessFlags())) {
                        virtualMethods.add(method.toString());
                    }
                }
            }
        }
        if (virtualMethods.isEmpty()) throw new IllegalStateException("No protobuf methods found");
        int errors = 0;
        for (var entry : dex.getDexEntryNames()) {
            for (var cls : dex.getEntry(entry).getDexFile().getClasses()) {
                for (var method : cls.getMethods()) {
                    var implementation = method.getImplementation();
                    if (implementation == null) continue;
                    for (var instruction : implementation.getInstructions()) {
                        if ((instruction.getOpcode() == Opcode.INVOKE_DIRECT ||
                                instruction.getOpcode() == Opcode.INVOKE_DIRECT_RANGE) &&
                                virtualMethods.contains(((ReferenceInstruction) instruction).getReference().toString())) {
                            if (errors++ < 10) System.err.println("Invalid protobuf dispatch in " + method);
                        }
                    }
                }
            }
        }
        if (errors != 0) throw new IllegalStateException(errors + " invalid protobuf direct calls");
        System.out.println("Protobuf dispatch verified: " + virtualMethods.size() + " virtual methods");
    }
}

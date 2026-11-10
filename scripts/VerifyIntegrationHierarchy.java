import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;

/** Catch host classes/methods made final by obfuscation before shipping an APK. */
public class VerifyIntegrationHierarchy {
    private static String signature(Method method) {
        return method.getName() + "(" + String.join("", method.getParameterTypes())
                + ")" + method.getReturnType();
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        var classes = new HashMap<String, ClassDef>();
        for (var entry : dex.getDexEntryNames()) {
            for (var cls : dex.getEntry(entry).getDexFile().getClasses()) {
                classes.put(cls.getType(), cls);
            }
        }
        int checked = 0, errors = 0;
        for (var cls : classes.values()) {
            if (!cls.getType().startsWith("Lapp/revanced/bilibili/")) continue;
            checked++;
            var overrides = new HashSet<String>();
            for (var method : cls.getVirtualMethods()) overrides.add(signature(method));
            var seen = new HashSet<String>();
            for (var parent = classes.get(cls.getSuperclass()); parent != null;
                    parent = classes.get(parent.getSuperclass())) {
                if (!seen.add(parent.getType())) throw new IllegalStateException("Cyclic superclass: " + cls);
                if (AccessFlags.FINAL.isSet(parent.getAccessFlags())) {
                    System.err.println(cls.getType() + " extends final " + parent.getType());
                    errors++;
                }
                for (var method : parent.getVirtualMethods()) {
                    if (AccessFlags.FINAL.isSet(method.getAccessFlags()) && overrides.contains(signature(method))) {
                        System.err.println(cls.getType() + " overrides final " + method);
                        errors++;
                    }
                }
            }
        }
        if (checked == 0) throw new IllegalStateException("No integration classes found");
        var updateProvider = classes.get("Lapp/revanced/bilibili/update/UpdateFileProvider;");
        if (updateProvider == null || !"Landroidx/core/content/FileProvider;".equals(updateProvider.getSuperclass())) {
            throw new IllegalStateException("Missing dedicated update FileProvider");
        }
        boolean providerConstructor = false;
        for (var method : updateProvider.getDirectMethods()) {
            if (method.getName().equals("<init>") && method.getParameterTypes().isEmpty()
                    && AccessFlags.PUBLIC.isSet(method.getAccessFlags())) providerConstructor = true;
        }
        if (!providerConstructor) throw new IllegalStateException("Update provider cannot be instantiated by Android");
        if (errors != 0) throw new IllegalStateException(errors + " invalid integration inheritance relationships");
        System.out.println("Integration hierarchy verified: " + checked + " classes");
    }
}

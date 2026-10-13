import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Build-host workaround for GraalVM 25.2's points-to analysis on Windows.
 *
 * TypeFlowSimplifier.handleInvoke devirtualizes calls whose target method is @Delete'd
 * and asserts there is exactly one callee. Analyzing on a Windows host can leave several
 * (Windows-specific JDK classes add implementations), which aborts the build with
 * "@Delete methods should have a single callee." This patch sends that case down the
 * method's normal (non-@Delete) path instead: the call stays an ordinary virtual call,
 * and the deleted targets still fail at run time if ever reached. Single-callee @Delete
 * calls are unchanged.
 *
 * Shape of the patched code (javac output of handleInvoke):
 *   ifeq ELSE                       // not @Delete -> normal handling
 *   ... size() iconst_1 if_icmpne X  // <- retarget X to ELSE
 *   ... ldc "@Delete methods should have a single callee." ... guarantee(...)
 *
 * Usage: java -cp asm.jar;asm-tree.jar PointstoPatch.java <pointsto.jar> <outDir>
 * Writes outDir/com/oracle/graal/pointsto/results/TypeFlowSimplifier.class for
 * -J--patch-module=org.graalvm.nativeimage.pointsto=outDir. Fails unless exactly one
 * site matches, so a GraalVM update can't silently mis-patch.
 */
public final class PointstoPatch {
    private static final String CLASS = "com/oracle/graal/pointsto/results/TypeFlowSimplifier";
    private static final String MESSAGE = "@Delete methods should have a single callee.";

    public static void main(String[] args) throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(args[0]); InputStream in = jar.getInputStream(jar.getJarEntry(CLASS + ".class"))) {
            original = in.readAllBytes();
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        int patched = 0;
        for (MethodNode m : node.methods) {
            patched += patch(m);
        }
        if (patched != 1) {
            throw new IllegalStateException("expected exactly one @Delete guarantee site, patched " + patched);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path out = Path.of(args[1]).resolve(CLASS + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, writer.toByteArray());
        System.out.println("patched " + CLASS + " -> " + out);
    }

    private static int patch(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (!(insn instanceof LdcInsnNode ldc) || !MESSAGE.equals(ldc.cst)) {
                continue;
            }
            // The size() != 1 check right before the message.
            JumpInsnNode sizeCheck = null;
            for (AbstractInsnNode c = insn; c != null; c = c.getPrevious()) {
                if (c instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IF_ICMPNE) {
                    sizeCheck = j;
                    break;
                }
            }
            // The @Delete test before that: ifeq ELSE.
            JumpInsnNode deleteTest = null;
            for (AbstractInsnNode c = sizeCheck; c != null; c = c.getPrevious()) {
                if (c instanceof JumpInsnNode j && j.getOpcode() == Opcodes.IFEQ) {
                    deleteTest = j;
                    break;
                }
            }
            if (sizeCheck == null || deleteTest == null) {
                throw new IllegalStateException("unexpected bytecode shape around the @Delete guarantee");
            }
            LabelNode normalPath = deleteTest.label;
            sizeCheck.label = normalPath;
            return 1;
        }
        return 0;
    }
}

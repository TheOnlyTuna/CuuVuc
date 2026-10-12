package vn.cuuvuc.validation.io;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.net.URISyntaxException;
import java.util.jar.JarFile;

/** Chỉ chèn chốt đo vào RegionFile của runtime riêng; không sửa JAR Paper hay core. */
public final class RegionIoAgent implements ClassFileTransformer {
    private static final String REGION = "net/minecraft/world/level/chunk/storage/RegionFile";
    private static final String CHUNK = "net/minecraft/world/level/ChunkPos";
    private static final String HOOK = "vn/cuuvuc/validation/io/IoCheckpoint";

    public static void premain(String arguments, Instrumentation instrumentation) throws IOException, URISyntaxException {
        Path runtime = Path.of("").toAbsolutePath().normalize();
        if (!runtime.getFileName().toString().startsWith("run-b01-io"))
            throw new IllegalStateException("IO agent chỉ được chạy trong run-b01-io*");
        Files.createDirectories(runtime.resolve("io-probe"));
        Path agent = Path.of(RegionIoAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agent.resolveSibling("b01-io-agent-1.0-hooks.jar").toFile()));
        instrumentation.addTransformer(new RegionIoAgent());
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                      ProtectionDomain domain, byte[] bytes) {
        if (!REGION.equals(name)) return null;
        try {
            return instrument(name, bytes);
        } catch (RuntimeException | LinkageError failure) {
            try { Files.writeString(Path.of("io-probe", "transform-failed.txt"), failure.toString(), StandardCharsets.UTF_8); }
            catch (IOException ioFailure) { failure.addSuppressed(ioFailure); }
            throw failure;
        }
    }

    private static byte[] instrument(String name, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                                       String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                if (!method.equals("write") || !descriptor.equals("(L" + CHUNK + ";Ljava/nio/ByteBuffer;)V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private void mark(String phase) {
                        visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGION, "getPath", "()Ljava/nio/file/Path;", false);
                        visitVarInsn(Opcodes.ALOAD, 1); super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHUNK, "x", "()I", false);
                        visitVarInsn(Opcodes.ALOAD, 1); super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHUNK, "z", "()I", false);
                        visitLdcInsn(phase);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "checkpoint", "(ILjava/nio/file/Path;IILjava/lang/String;)V", false);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String called,
                                                          String desc, boolean isInterface) {
                        if (owner.equals("java/nio/channels/FileChannel") && called.equals("write")
                                && desc.equals("(Ljava/nio/ByteBuffer;J)I")) {
                            visitInsn(Opcodes.ICONST_M1); mark("PAYLOAD_BEFORE_WRITE");
                            super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                            visitInsn(Opcodes.DUP);
                            mark("PAYLOAD_AFTER_WRITE");
                        } else if (owner.equals(REGION) && called.equals("writeHeader")) {
                            visitInsn(Opcodes.ICONST_M1); mark("HEADER_BEFORE_WRITE");
                            super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                            visitInsn(Opcodes.ICONST_M1); mark("HEADER_AFTER_WRITE");
                        } else super.visitMethodInsn(opcode, owner, called, desc, isInterface);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        try { Files.writeString(Path.of("io-probe", "transformed.txt"), name + "\n", StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Không ghi được bằng chứng transform RegionFile", failure); }
        return writer.toByteArray();
    }

}

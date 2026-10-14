package vn.cuuvuc.validation.measure;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

/** Connector đo bytecode runtime riêng, giữ nguyên JAR trên đĩa và mọi nhánh nghiệp vụ. */
public final class MeasureAgent implements ClassFileTransformer {
    private static final String HOOK="vn/cuuvuc/validation/measure/MeasureHook";
    private final boolean full;
    public MeasureAgent(boolean full){this.full=full;}
    public static void premain(String argument,Instrumentation instrumentation) throws IOException,URISyntaxException,ReflectiveOperationException {
        if(!Path.of("").toAbsolutePath().getFileName().toString().startsWith("run-b02-"))throw new IllegalStateException("Agent chỉ cho run-b02-*");
        String mode=System.getProperty("b02.mode");if(!"full".equals(mode)&&!"control".equals(mode))throw new IllegalArgumentException("Cần b02.mode=full/control");
        Path agent=Path.of(MeasureAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agent.resolveSibling("b02-agent-1.0-hooks.jar").toFile()));
        Class.forName("vn.cuuvuc.validation.measure.MeasureHook",true,null).getMethod("initialize").invoke(null);
        instrumentation.addTransformer(new MeasureAgent(mode.equals("full")));
    }
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> changed,ProtectionDomain domain,byte[] bytes) {
        if(!name.equals("vn/cuuvuc/core/ContractWorld")&&!name.equals("vn/cuuvuc/core/PlayerTasks")&&
            !(full&&(name.equals("org/bukkit/craftbukkit/CraftWorld")||name.equals("net/minecraft/server/MinecraftServer")||name.equals("vn/cuuvuc/core/GameDatabase"))))return null;
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                MethodVisitor delegate=super.visitMethod(access,method,descriptor,signature,exceptions);
                boolean accepted=name.endsWith("/ContractWorld")&&method.equals("checkAndSave");
                boolean save=full&&name.endsWith("/ContractWorld")&&method.equals("lambda$checkAndSave$1");
                boolean reply=name.endsWith("/PlayerTasks")&&method.equals("lambda$reply$0");
                boolean tick=full&&name.endsWith("/MinecraftServer")&&method.equals("tickServer")&&descriptor.equals("(Ljava/util/function/BooleanSupplier;)V");
                boolean world=full&&name.endsWith("/CraftWorld")&&method.equals("save")&&descriptor.equals("(Z)V");
                boolean database=full&&name.endsWith("/GameDatabase")&&(method.equals("submitContract")||method.equals("completeContract"))&&descriptor.equals("(Ljava/util/UUID;Ljava/util/UUID;)Ljava/lang/String;");
                if(!accepted&&!save&&!reply&&!tick&&!world&&!database)return delegate;
                try{Files.writeString(Path.of("benchmark",name.replace('/','_')+"-"+method+".hook"),descriptor);}catch(IOException failure){throw new IllegalStateException("Không ghi được marker hook",failure);}
                return new AdviceAdapter(Opcodes.ASM9,delegate,access,method,descriptor){
                    private int started;
                    private void nano(){invokeStatic(Type.getType(System.class),new org.objectweb.asm.commons.Method("nanoTime","()J"));}
                    private void hook(String called,String desc){super.visitMethodInsn(INVOKESTATIC,HOOK,called,desc,false);}
                    @Override protected void onMethodEnter(){
                        if(tick||world){started=newLocal(Type.LONG_TYPE);nano();storeLocal(started);}
                    }
                    @Override public void visitMethodInsn(int opcode,String owner,String called,String desc,boolean itf){
                        if(accepted&&owner.equals("java/util/concurrent/ConcurrentHashMap")&&called.equals("putIfAbsent")){
                            super.visitMethodInsn(opcode,owner,called,desc,itf);dup();loadArg(0);loadArg(1);loadArg(2);
                            hook("accepted","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/UUID;)V");return;
                        }
                        if(save&&owner.equals("org/bukkit/World")&&called.equals("save")&&desc.equals("()V")){
                            loadArg(2);hook("saveBegin","(Ljava/util/UUID;)V");super.visitMethodInsn(opcode,owner,called,desc,itf);
                            loadArg(2);hook("saveEnd","(Ljava/util/UUID;)V");return;
                        }
                        if(reply&&owner.equals("org/bukkit/entity/Player")&&called.equals("sendPlainMessage")){
                            loadArg(0);loadArg(2);hook("replied","(Ljava/util/UUID;Ljava/lang/String;)V");
                        }
                        super.visitMethodInsn(opcode,owner,called,desc,itf);
                    }
                    @Override protected void onMethodExit(int opcode){
                        if(opcode==ATHROW)return;
                        if(tick){loadLocal(started);nano();hook("tick","(JJ)V");}
                        if(world){loadLocal(started);nano();loadArg(0);hook("delegate","(JJZ)V");}
                        if(database){loadArg(0);visitLdcInsn(method.equals("submitContract")?"SUBMIT":"APPROVE");hook("databaseReturn","(Ljava/util/UUID;Ljava/lang/String;)V");}
                    }
                };
            }
        },ClassReader.EXPAND_FRAMES);return writer.toByteArray();
    }
}

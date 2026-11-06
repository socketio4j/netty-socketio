package com.socketio4j.socketio.verification.lockaudit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Instruments owned production monitor entry and known blocking calls before acquisition. */
public final class LockAgent {
  private static final java.util.concurrent.atomic.AtomicLong TRANSFORMED =
      new java.util.concurrent.atomic.AtomicLong();

  public static void premain(String report, Instrumentation instrumentation) {
    if (report == null || report.isEmpty())
      throw new IllegalArgumentException("Supply a report path");
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    Files.writeString(Path.of(report), Long.toString(LockProbe.VIOLATIONS.get()));
                    Files.writeString(
                        Path.of(report + ".classes"), Long.toString(TRANSFORMED.get()));
                  } catch (Exception e) {
                    throw new AssertionError("Cannot save audit report", e);
                  }
                },
                "lock-audit-report"));
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> redefining,
              ProtectionDomain domain,
              byte[] bytes) {
            if (name == null
                || !name.startsWith("com/socketio4j/socketio/")
                || name.startsWith("com/socketio4j/socketio/verification/lockaudit/")
                || domain == null
                || domain.getCodeSource() == null
                || domain.getCodeSource().getLocation().toString().contains("test-classes"))
              return null;
            try {
              ClassReader reader = new ClassReader(bytes);
              ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
              reader.accept(
                  new ClassVisitor(Opcodes.ASM9, writer) {
                    public MethodVisitor visitMethod(
                        int access,
                        String method,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                      if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                        throw new IllegalStateException(
                            "Synchronized method cannot be checked before acquisition: "
                                + name
                                + "."
                                + method);
                      }
                      MethodVisitor target =
                          super.visitMethod(access, method, descriptor, signature, exceptions);
                      return new MethodVisitor(Opcodes.ASM9, target) {
                        private void check(String operation) {
                          super.visitLdcInsn(Type.getObjectType(name));
                          super.visitLdcInsn(name + "." + method + descriptor + " -> " + operation);
                          super.visitMethodInsn(
                              Opcodes.INVOKESTATIC,
                              "com/socketio4j/socketio/verification/lockaudit/LockProbe",
                              "check",
                              "(Ljava/lang/Class;Ljava/lang/String;)V",
                              false);
                        }

                        public void visitInsn(int opcode) {
                          if (opcode == Opcodes.MONITORENTER) check("MONITORENTER");
                          super.visitInsn(opcode);
                        }

                        public void visitMethodInsn(
                            int opcode, String owner, String called, String desc, boolean iface) {
                          if (LockAudit.blockingCall(owner, called, desc))
                            check(owner + "." + called);
                          super.visitMethodInsn(opcode, owner, called, desc, iface);
                        }
                      };
                    }
                  },
                  0);
              byte[] transformed = writer.toByteArray();
              TRANSFORMED.incrementAndGet();
              return transformed;
            } catch (Throwable failure) {
              LockProbe.VIOLATIONS.incrementAndGet();
              throw new IllegalStateException("Cannot instrument " + name, failure);
            }
          }
        });
  }
}

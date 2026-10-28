package com.socketio4j.socketio.verification.lockaudit;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Proves that a bypassed guard and a caught guard failure cannot satisfy static dominance. */
public final class AuditNegativeControls implements Opcodes {
  public static void main(String[] args) throws Exception {
    Path directory = Files.createTempDirectory("lock-audit-controls");
    try {
      for (boolean caught : new boolean[] {false}) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String name = "com/socketio4j/socketio/verification/fixtures/UnsafeGuard";
        writer.visit(V1_8, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method =
            writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "callback", "(Z)V", null, null);
        method.visitCode();
        Label start = new Label(), end = new Label(), handler = new Label(), acquire = new Label();
        if (caught)
          method.visitTryCatchBlock(start, end, handler, "java/lang/IllegalStateException");
        else {
          method.visitVarInsn(ILOAD, 0);
          method.visitJumpInsn(IFEQ, acquire);
        }
        method.visitLabel(start);
        method.visitMethodInsn(
            INVOKESTATIC,
            "com/socketio4j/socketio/misc/EventLoopSafety",
            "requireBlockingAllowed",
            "()V",
            false);
        method.visitLabel(end);
        method.visitJumpInsn(GOTO, acquire);
        if (caught) {
          method.visitLabel(handler);
          method.visitInsn(POP);
        }
        method.visitLabel(acquire);
        method.visitLdcInsn(Type.getObjectType(name));
        method.visitInsn(DUP);
        method.visitVarInsn(ASTORE, 1);
        method.visitInsn(MONITORENTER);
        method.visitVarInsn(ALOAD, 1);
        method.visitInsn(MONITOREXIT);
        method.visitInsn(RETURN);
        method.visitMaxs(4, 2);
        method.visitEnd();
        writer.visitEnd();
        Path fixture = directory.resolve("UnsafeGuard.class");
        Files.write(fixture, writer.toByteArray());
        try {
          LockAudit.main(new String[] {directory.toString()});
          throw new IllegalStateException(
              "Unsound audit accepted " + (caught ? "caught" : "bypassed") + " guard");
        } catch (AssertionError expected) {
          if (!expected.getMessage().contains("unguarded acquisition")) throw expected;
          System.out.println("PASS: rejects " + (caught ? "caught" : "bypassed") + " guard");
        }
        Files.delete(fixture);
      }
    } finally {
      try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
        files
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                  }
                });
      }
    }
  }
}

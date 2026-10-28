package com.socketio4j.socketio.verification.lockaudit;

import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Control-flow check: guard-success must dominate every owned monitor/explicit lock acquisition
 * and blocking call in production classes.
 */
public final class LockAudit {

  public static boolean blockingCall(String owner, String name, String descriptor) {
    if (owner.equals("java/lang/Thread")) return name.equals("sleep") || name.equals("join");
    if (owner.equals("java/lang/Object")) return name.equals("wait");
    if (owner.equals("java/util/concurrent/locks/LockSupport")) return name.startsWith("park");
    if (owner.startsWith("java/util/concurrent/locks/"))
      return name.equals("lock") || name.equals("lockInterruptibly") || name.startsWith("await");
    if (owner.equals("java/util/concurrent/Semaphore"))
      return name.equals("acquire")
          || (name.equals("tryAcquire") && descriptor.contains("TimeUnit"));
    if (owner.startsWith("io/netty/")
        && (name.equals("sync") || name.equals("syncUninterruptibly") || name.startsWith("await")))
      return true;
    if (name.equals("awaitTermination") && owner.startsWith("java/util/concurrent/"))
      return true;
    return (owner.equals("java/util/concurrent/CountDownLatch") && name.equals("await"))
        || ((owner.equals("java/util/concurrent/Future")
                || owner.equals("java/util/concurrent/CompletableFuture"))
            && (name.equals("get") || name.equals("join")));
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: LockAudit <classes-dir>");
      System.exit(1);
    }
    List<String> failures = new ArrayList<>();
    int[] methods = {0}, monitors = {0};
    try (var files = Files.walk(Path.of(args[0]))) {
      for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        if (node.name.startsWith("com/socketio4j/socketio/verification/lockaudit/")) {
          continue;
        }
        for (MethodNode method : node.methods) {
          if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0)
            failures.add(node.name + "." + method.name + ": synchronized method");
          if (method.instructions.size() == 0) continue;
          methods[0]++;
          int n = method.instructions.size();
          List<Set<Integer>> normal = new ArrayList<>(), exceptional = new ArrayList<>();
          for (int i = 0; i < n; i++) {
            normal.add(new HashSet<>());
            exceptional.add(new HashSet<>());
          }
          Analyzer<BasicValue> analyzer =
              new Analyzer<>(new BasicInterpreter()) {
                protected void newControlFlowEdge(int from, int to) {
                  normal.get(from).add(to);
                }

                protected boolean newControlFlowExceptionEdge(int from, int to) {
                  exceptional.get(from).add(to);
                  return true;
                }
              };
          analyzer.analyze(node.name, method);
          boolean[] seen = new boolean[n];
          ArrayDeque<Integer> work = new ArrayDeque<>();
          work.add(0);
          while (!work.isEmpty()) {
            int i = work.remove();
            if (seen[i]) continue;
            seen[i] = true;
            AbstractInsnNode instruction = method.instructions.get(i);
            boolean guard =
                instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/socketio4j/socketio/misc/EventLoopSafety")
                    && call.name.equals("requireBlockingAllowed")
                    && call.desc.equals("()V")
                    && call.getOpcode() == Opcodes.INVOKESTATIC;
            boolean locking =
                instruction.getOpcode() == Opcodes.MONITORENTER
                    || (instruction instanceof MethodInsnNode call
                        && (blockingCall(call.owner, call.name, call.desc)
                            || (call.owner.startsWith("java/util/concurrent/locks/")
                                && (call.name.equals("lock") || call.name.equals("lockInterruptibly")))));
            if (locking)
              failures.add(
                  node.name
                      + "."
                      + method.name
                      + method.desc
                      + ": unguarded acquisition or blocking call at instruction "
                      + i);
            if (!guard) work.addAll(normal.get(i));
            work.addAll(exceptional.get(i));
          }
          for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() == Opcodes.MONITORENTER) monitors[0]++;
        }
      }
    }
    if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    System.out.println(
        "PASS: "
            + methods[0]
            + " methods, "
            + monitors[0]
            + " guarded monitor sites; no synchronized methods or unguarded explicit locks.");
  }
}

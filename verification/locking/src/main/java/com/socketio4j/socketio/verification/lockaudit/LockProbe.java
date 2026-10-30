package com.socketio4j.socketio.verification.lockaudit;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-agent probe; Netty is resolved in the instrumented application's class loader. */
public final class LockProbe {
  private static final ConcurrentHashMap<ClassLoader, Detection> DETECTORS =
      new ConcurrentHashMap<>();
  public static final AtomicLong VIOLATIONS = new AtomicLong();

  private LockProbe() {}

  public static void check(Class<?> owner, String site) {
    try {
      Detection detector = DETECTORS.computeIfAbsent(owner.getClassLoader(), Detection::new);
      Object executor = detector.current.invoke(null);
      if (detector.eventLoop.isInstance(executor)
          || (executor == null && detector.nettyThread.isInstance(Thread.currentThread()))) {
        VIOLATIONS.incrementAndGet();
        throw new AssertionError("NETTY_LOCK_VIOLATION " + site);
      }
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Lock probe cannot inspect Netty executor", e);
    }
  }

  private static final class Detection {
    final Method current;
    final Class<?> eventLoop;
    final Class<?> nettyThread;

    Detection(ClassLoader loader) {
      try {
        current =
            Class.forName("io.netty.util.internal.ThreadExecutorMap", false, loader)
                .getMethod("currentExecutor");
        eventLoop = Class.forName("io.netty.channel.EventLoop", false, loader);
        nettyThread =
            Class.forName("io.netty.util.concurrent.FastThreadLocalThread", false, loader);
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("Netty thread detector unavailable", e);
      }
    }
  }
}

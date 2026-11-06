package com.socketio4j.socketio.verification.fixtures;

/**
 * Negative control, run in a separate JVM so its violation cannot contaminate the positive report.
 */
public final class BlockingCallback {
  private static final Object MONITOR = new Object();

  public static void main(String[] args) throws Exception {
    Object loop = Class.forName("io.netty.channel.DefaultEventLoop").getConstructor().newInstance();
    try {
      Runnable callback =
          () -> {
            synchronized (MONITOR) {
              throw new AssertionError("Probe failed to reject before acquisition");
            }
          };
      Object future = loop.getClass().getMethod("submit", Runnable.class).invoke(loop, callback);
      try {
        ((java.util.concurrent.Future<?>) future).get();
        throw new AssertionError("Locking callback unexpectedly completed");
      } catch (java.util.concurrent.ExecutionException expected) {
        if (!expected.getCause().getMessage().contains("NETTY_LOCK_VIOLATION")) throw expected;
        System.out.println(
            "PASS: negative control rejected monitor acquisition on Netty event loop");
      }
    } finally {
      Object stopped =
          loop.getClass()
              .getMethod(
                  "shutdownGracefully", long.class, long.class, java.util.concurrent.TimeUnit.class)
              .invoke(loop, 0L, 0L, java.util.concurrent.TimeUnit.SECONDS);
      ((java.util.concurrent.Future<?>) stopped).get();
    }
  }
}

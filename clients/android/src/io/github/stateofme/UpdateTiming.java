package io.github.stateofme;
/** Monotonic completion-based throttle: leave margin above server's 1 Hz limit. */
public final class UpdateTiming {
 public static long delay(long now,long completedAt){return completedAt==0?0:Math.max(0,1100-Math.max(0,now-completedAt));}
}

package org.example.viridiafinder;

/** Monotonic timing; delays never cause catch-up bursts. */
public final class RiptideClock {
    private long next;
    public boolean ready(long now){return now>=next;}
    public void started(long now){next=now+250_000_000L;}
    public void released(long now){next=Math.max(next,now+50_000_000L);}
    public void reset(){next=0;}
}

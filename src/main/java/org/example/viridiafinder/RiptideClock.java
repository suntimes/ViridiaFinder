package org.example.viridiafinder;

/** Monotonic timing; delays never cause catch-up bursts. */
public final class RiptideClock {
    private long next,started;
    private boolean scheduled;
    public boolean ready(long now){return !scheduled||now-next>=0;}
    public void started(long now){started=now;defer(now,250_000_000L);}
    // Leave two ticks above vanilla's ten-tick threshold for ordinary tick/network jitter.
    public boolean charged(int useTicks){return useTicks>=12;}
    public boolean timedOut(long now){return now-started>=1_250_000_000L;}
    public void released(long now){defer(now,150_000_000L);}
    public void interrupted(long now){defer(now,500_000_000L);}
    private void defer(long now,long delay){
        long deadline=now+delay;
        if(!scheduled||deadline-next>0)next=deadline;
        scheduled=true;
    }
    public void reset(){scheduled=false;next=0;started=0;}
}

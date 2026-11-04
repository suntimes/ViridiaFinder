package org.example.viridiafinder;

import java.util.regex.Pattern;

/** Only parses the observed NPC reply after a locally observed manual Eye click. */
public final class RoundTracker {
    private static final Pattern NPC=Pattern.compile("^\\[\\s*(?:[^\\[\\]]*\\(Vigora\\)|Vigora)\\s*\\]\\s*(.*)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT=Pattern.compile("^Of the 4 caches, ([0-4]) have been claimed\\.(?:.*)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME=Pattern.compile("^Taking into account the ocean currents right now, I'd estimate that it'll probably be (\\d{1,4})\\s*(?:minutes?|分钟)\\s*before the next caches appear\\.$",Pattern.CASE_INSENSITIVE);
    private static final Pattern ZH_COUNT=Pattern.compile("^共\\s*4\\s*处宝藏[，,]\\s*其中\\s*([0-4])\\s*处已被领取[。.].*$");
    private static final Pattern ZH_TIME=Pattern.compile("^根据目前的洋流情况[，,]\\s*我估计大约\\s*(\\d{1,4})\\s*分钟后[，,]\\s*下一批宝藏才会出现[。.]?$");
    public transient int claimed=-1;
    public long expectedAt;
    private transient long armedUntil;
    private transient boolean countReceived,reminded;
    private transient long npcUntil;
    public void arm(long now){armedUntil=now+8000;countReceived=false;npcUntil=0;}
    public void forgetSession(){claimed=-1;armedUntil=0;countReceived=false;reminded=false;npcUntil=0;}
    public enum Result {IGNORED,COUNT,TIME,NEW_ROUND}
    public Result accept(String text,long now){
        if(armedUntil==0||now>armedUntil)return Result.IGNORED;
        String clean=text.replace('（','(').replace('）',')').replaceAll("§[0-9a-fk-orA-FK-OR]","").replaceAll("\\s+"," ").trim();
        var npc=NPC.matcher(clean);String body;
        if(npc.matches()){body=npc.group(1);npcUntil=now+2000;}
        else if(now<npcUntil)body=clean;
        else return Result.IGNORED;
        var count=COUNT.matcher(body);
        if(!count.matches())count=ZH_COUNT.matcher(body);
        if(count.matches()&&!countReceived){
            int next=Integer.parseInt(count.group(1));boolean changed=claimed>=0&&next<claimed;
            claimed=next;countReceived=true;
            if(changed){expectedAt=0;reminded=false;return Result.NEW_ROUND;}
            return Result.COUNT;
        }
        var time=TIME.matcher(body);
        if(!time.matches())time=ZH_TIME.matcher(body);
        if(time.matches()&&countReceived){
            int minutes=Integer.parseInt(time.group(1));if(minutes>240)return Result.IGNORED;
            long nextTime=now+minutes*60_000L;
            boolean rolled=expectedAt>0&&now>=expectedAt-120000&&nextTime>expectedAt+3600000&&minutes>=30;
            expectedAt=nextTime;reminded=false;return rolled?Result.NEW_ROUND:Result.TIME;
        }
        return Result.IGNORED;
    }
    public boolean reminderDue(long now){
        if(expectedAt>0&&now>=expectedAt&&!reminded){reminded=true;return true;}return false;
    }
    public boolean localRecordOlderThanHint(long recordStarted,long now){
        // Two-hour cycle documented by the wiki; retain five minutes of estimate slack.
        return claimed>=0&&expectedAt>=now&&expectedAt-now<=122*60_000L
            &&recordStarted>0&&recordStarted+5*60_000L<expectedAt-120*60_000L;
    }
    public String status(long now){
        String count=claimed<0?"未读取领取数":"领取数 "+claimed+"/4";
        if(expectedAt==0)return count+"；主手持眼左键查询";
        long seconds=Math.max(0,(expectedAt-now+999)/1000);
        return count+(seconds==0?"；预计到点，请用眼确认":"；预计刷新 "+seconds/60+"分"+seconds%60+"秒");
    }
}

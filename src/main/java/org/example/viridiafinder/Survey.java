package org.example.viridiafinder;

import java.util.*;
import java.util.function.Consumer;

/** 与 Minecraft 无关的有预算扫描器；未知区块永远不作为空气处理。 */
public final class Survey {
    public record Pos(int x, int y, int z) {
        public String key() { return x + "," + y + "," + z; }
        public double distance(Pos p) { return Math.sqrt(Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2)); }
        public String offset(Pos p) { return (x-p.x)+", "+(y-p.y)+", "+(z-p.z); }
    }
    public enum Kind {
        NONE("无容器"), CHEST("普通箱子"), TRAPPED("陷阱箱"), BARREL("木桶");
        public final String label;
        Kind(String label) { this.label = label; }
    }
    public enum Mark {
        CONTAINER("普通容器"), SUSPECT("疑似本轮宝藏（人工标记）"), CONFIRMED("本轮宝藏（人工确认）"), CLAIMED("已领取（人工记录）");
        public final String label;
        Mark(String label) { this.label = label; }
    }
    public interface View {
        boolean loaded(int chunkX, int chunkZ);
        Kind read(Pos p);
        int bottom();
        int top(); // exclusive
        default List<Pos> indexedPositions(int chunkX,int chunkZ) { return List.of(); }
    }
    public static final class Settings {
        public int radius = 8, below = 8, above = 8, budget = 2048;
        public boolean barrels = false, hud = true;
        public boolean highlight = true;
        public boolean autoRound = true;
        public Set<String> movementServers = new HashSet<>();
        public void validate() {
            if(movementServers == null) movementServers = new HashSet<>();
            barrels = false; // 旧配置兼容：不再检索木桶。
            radius = Math.max(0, Math.min(64, radius));
            below = Math.max(0, Math.min(128, below));
            above = Math.max(0, Math.min(128, above));
            budget = Math.max(128, Math.min(8192, budget));
        }
    }
    public static final class Found {
        public Pos pos;
        public Kind kind;
        public Mark mark = Mark.CONTAINER;
        public long seenAt;
        public boolean present;
        public Found(Pos pos, Kind kind) { this.pos=pos; this.kind=kind; }
    }
    public static final class Candidate {
        public int id;
        public Pos pos;
        public boolean visited;
        public Map<String, Found> found = new LinkedHashMap<>();
        public transient int checked, unknown, passChecked, passUnknown;
        public transient long completedAt;
        public transient boolean passInvalidated;
        // Historical scan audit only; never substitutes for a fresh client observation.
        public long lastScanAt;
        public int lastRadius,lastBelow,lastAbove,lastChecked,lastUnknown;
        public boolean lastScanComplete;
        public Candidate(int id, Pos pos) { this.id=id; this.pos=pos; }
    }
    public final List<Candidate> candidates;
    public final Settings settings;
    private int index, dx, dy, dz;
    private boolean positioned;
    public long passes;
    public Survey(List<Pos> points, Settings settings) {
        this.settings=settings; settings.validate();
        candidates = new ArrayList<>();
        for (int i=0;i<points.size();i++) candidates.add(new Candidate(i+1, points.get(i)));
    }
    public int tick(View view, int budget, long deadline, Consumer<Found> discovered) {
        int spent=0;
        while (!candidates.isEmpty() && spent<budget && System.nanoTime()<deadline) {
            Candidate c=candidates.get(index);
            if (!positioned) { dx=-settings.radius; dz=-settings.radius; dy=Math.max(view.bottom(),c.pos.y-settings.below); positioned=true; }
            int top=Math.min(view.top()-1,c.pos.y+settings.above);
            int bottom=Math.max(view.bottom(),c.pos.y-settings.below);
            dy=Math.max(dy,bottom);
            Pos p=new Pos(c.pos.x+dx,dy,c.pos.z+dz);
            spent++;
            if (bottom>top) { finishColumn(c); continue; }
            if (!view.loaded(p.x>>4,p.z>>4)) {
                c.passUnknown += top-dy+1;
                finishColumn(c); // 跳过未知列，不调用 read。
                continue;
            }
            Kind kind=view.read(p);
            c.passChecked++;
            observe(c,p,kind,discovered);
            if (++dy>top) finishColumn(c);
        }
        return spent;
    }
    public void observe(Candidate c,Pos p,Kind kind,Consumer<Found> discovered) {
        if(kind==Kind.BARREL)kind=Kind.NONE;
        Found old=c.found.get(p.key());
        if(kind!=Kind.NONE) {
            boolean fresh=old==null||old.kind!=kind;
            if(fresh){old=new Found(p,kind);c.found.put(p.key(),old);}
            old.present=true;old.seenAt=System.currentTimeMillis();
            if(fresh)discovered.accept(old);
        } else if(old!=null)old.present=false;
    }
    private void finishColumn(Candidate c) {
        dy=c.pos.y-settings.below;
        // 每列起点的世界高度截断在 next tick 处理。
        if (++dz>settings.radius) { dz=-settings.radius; dx++; }
        if (dx>settings.radius) {
            c.checked=c.passChecked; c.unknown=c.passUnknown; c.passChecked=0; c.passUnknown=0;
            c.lastScanAt=System.currentTimeMillis();
            c.lastRadius=settings.radius;c.lastBelow=settings.below;c.lastAbove=settings.above;
            c.lastChecked=c.checked;c.lastUnknown=c.unknown;
            c.lastScanComplete=!c.passInvalidated&&c.checked>0&&c.unknown==0;
            c.completedAt=c.passInvalidated?0:c.lastScanAt;
            c.passInvalidated=false;
            positioned=false; index=(index+1)%candidates.size();
            if (index==0) passes++;
        }
    }
    public void resetProgress() {
        positioned=false; index=0;
        for(Candidate c:candidates) { c.checked=0; c.unknown=0; c.passChecked=0; c.passUnknown=0; c.completedAt=0; c.passInvalidated=false; }
    }
    public void restartCoverage() {
        resetProgress();
        for(var c:candidates)c.visited=c.found.values().stream().anyMatch(f->f.mark==Mark.CLAIMED);
    }
    public int activeCandidateId() { return candidates.isEmpty()?0:candidates.get(index).id; }
    public void prioritize(Candidate c) {
        int wanted=candidates.indexOf(c);
        if(wanted<0||(index==wanted&&positioned))return;
        if(positioned){var old=candidates.get(index);old.passChecked=0;old.passUnknown=0;}
        index=wanted;positioned=false;c.passChecked=0;c.passUnknown=0;c.completedAt=0;c.passInvalidated=false;
    }
    public void invalidateChunk(int x,int z) {
        for(var c:candidates) {
            if(x>=((c.pos.x-settings.radius)>>4)&&x<=((c.pos.x+settings.radius)>>4)&&
                z>=((c.pos.z-settings.radius)>>4)&&z<=((c.pos.z+settings.radius)>>4)) {
                c.completedAt=0;
                if(positioned&&candidates.get(index)==c)c.passInvalidated=true;
            }
            for(var f:c.found.values()) if((f.pos.x>>4)==x&&(f.pos.z>>4)==z) f.present=false;
        }
    }
    public boolean fullyScanned(Candidate c,View view) {
        if(c.completedAt==0||c.unknown!=0||c.checked==0)return false;
        return areaLoaded(c,view);
    }
    public boolean areaLoaded(Candidate c,View view) {
        for(int x=(c.pos.x-settings.radius)>>4;x<=(c.pos.x+settings.radius)>>4;x++)
            for(int z=(c.pos.z-settings.radius)>>4;z<=(c.pos.z+settings.radius)>>4;z++)
                if(!view.loaded(x,z))return false;
        return true;
    }
    public static final class RoutePlan {
        private final List<Candidate> stops;
        private int next;
        public RoutePlan(List<Candidate> stops){this.stops=List.copyOf(stops);}
        public Candidate current(){return next<stops.size()?stops.get(next):null;}
        public int captureCompleted(Survey survey,View view){
            int count=0;
            for(var c:stops)if(!c.visited&&survey.fullyScanned(c,view)){c.visited=true;count++;}
            return count;
        }
        public int advance(Survey survey,View view){
            int count=0;
            while(current()!=null) {
                Candidate c=current();
                if(!c.visited&&c.found.values().stream().noneMatch(f->f.mark==Mark.CLAIMED)&&!survey.fullyScanned(c,view))break;
                c.visited=true;next++;count++;
            }
            return count;
        }
    }
    public boolean inRange(Candidate c, Pos p) {
        return Math.abs(p.x-c.pos.x)<=settings.radius && Math.abs(p.z-c.pos.z)<=settings.radius &&
            p.y>=c.pos.y-settings.below && p.y<=c.pos.y+settings.above;
    }
    public String coverage(Candidate c, View view) {
        int loaded=0,total=0;
        for(int x=(c.pos.x-settings.radius)>>4;x<=(c.pos.x+settings.radius)>>4;x++)
            for(int z=(c.pos.z-settings.radius)>>4;z<=(c.pos.z+settings.radius)>>4;z++) { total++; if(view.loaded(x,z)) loaded++; }
        return loaded==0 ? "未加载" : loaded==total ? "区域全部已加载" : "部分已加载（"+loaded+"/"+total+"区块）";
    }
    public List<Candidate> route(Pos start) {
        List<Candidate> todo=new ArrayList<>();
        for(Candidate c:candidates) if(!c.visited && c.found.values().stream().noneMatch(f->f.mark==Mark.CLAIMED)) todo.add(c);
        return RouteOptimizer.optimize(start,todo);
    }
}

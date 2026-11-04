package org.example.viridiafinder;

import java.util.*;

/** Select water observation stations, then join them by water-only A*. */
public final class SeaPlanner {
    public record Stop(List<Survey.Pos> path,List<Integer> candidates){}
    public record Plan(List<Stop> stops,List<Integer> unresolved,int radius,double length){}
    public static Plan plan(SeaMap map,Survey.Pos start,List<Survey.Candidate> candidates,int radius){
        int at=map.nearest(start,256);if(at<0)return new Plan(List.of(),candidates.stream().map(c->c.id).toList(),radius,0);
        var pool=new LinkedHashSet<Integer>();
        for(var c:candidates){int water=map.nearest(c.pos,Math.max(16,radius));if(water>=0)pool.add(water);}
        int spacing=Math.max(8,Math.min(32,radius/map.step));
        for(int z=0;z<map.h;z+=spacing)for(int x=0;x<map.w;x+=spacing){
            int i=z*map.w+x;if(map.water(i)&&covers(map.point(i),candidates,radius).size()>=2)pool.add(i);
        }
        var remaining=new ArrayList<>(candidates);List<Stop> stops=new ArrayList<>();double length=0;
        while(!remaining.isEmpty()&&!pool.isEmpty()){
            int current=at;
            var ranked=new ArrayList<>(pool);
            Map<Integer,List<Survey.Candidate>> coverage=new HashMap<>();
            for(int i:ranked)coverage.put(i,covers(map.point(i),remaining,radius));
            ranked.sort(Comparator.<Integer>comparingDouble(i->-(double)coverage.get(i).size()/(96+RouteOptimizer.distance(map.point(current),map.point(i)))).thenComparingInt(i->i));
            int chosen=-1,considered=0;double score=-1,chosenLength=0;List<Survey.Pos> chosenPath=List.of();
            for(int station:ranked){
                var covered=coverage.get(station);
                if(covered.isEmpty()){pool.remove(station);continue;}
                var path=map.path(at,station);if(path.isEmpty()){pool.remove(station);continue;}
                double travel=0;for(int i=1;i<path.size();i++)travel+=RouteOptimizer.distance(path.get(i-1),path.get(i));
                double benefit=covered.size()/(96+travel);
                if(benefit>score){score=benefit;chosen=station;chosenLength=travel;chosenPath=path;}
                if(++considered>=4)break;
            }
            if(chosen<0)break;
            var covered=coverage.get(chosen);stops.add(new Stop(chosenPath,covered.stream().map(c->c.id).toList()));
            length+=chosenLength;remaining.removeAll(covered);pool.remove(chosen);at=chosen;
        }
        return new Plan(stops,remaining.stream().map(c->c.id).toList(),radius,length);
    }
    private static List<Survey.Candidate> covers(Survey.Pos p,List<Survey.Candidate> candidates,int radius){
        return candidates.stream().filter(c->Math.abs(c.pos.x()-p.x())<=radius&&Math.abs(c.pos.z()-p.z())<=radius).toList();
    }
    public static int observedRadius(Survey.View view,Survey.Pos player){
        int cx=player.x()>>4,cz=player.z()>>4,radius=0;
        if(!view.loaded(cx,cz))return 0;
        for(int r=1;r<=16;r++){
            boolean all=true;
            for(int d=-r;d<=r;d++)if(!view.loaded(cx+d,cz-r)||!view.loaded(cx+d,cz+r)||!view.loaded(cx-r,cz+d)||!view.loaded(cx+r,cz+d)){all=false;break;}
            if(!all)break;radius=r;
        }return radius;
    }
}

package org.example.viridiafinder;

import java.util.*;

/** Select water observation stations, then join them by water-only A*. */
public final class SeaPlanner {
    public record Stop(List<Survey.Pos> path,List<Integer> candidates){}
    public record Plan(List<Stop> stops,List<Integer> unresolved,int radius,double length){}
    public static Plan plan(SeaMap map,Survey.Pos start,List<Survey.Candidate> candidates,int radius){
        return improve(map, greedyPlan(map,start,candidates,radius), candidates).plan();
    }
    static Plan greedyPlan(SeaMap map,Survey.Pos start,List<Survey.Candidate> candidates,int radius){
        int at=map.oceanEntry(start);if(at<0)return new Plan(List.of(),candidates.stream().map(c->c.id).toList(),radius,0);
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

    // The open tour has a fixed entry and a free end. Budget additional path searches
    // separately from the initial covering tour so refinement cannot trigger all-pairs A*.
    static final int MAX_CONNECTORS = 128;
    static final int MAX_DETOURS = 20;
    private static final double EPS = 1e-6;
    record Refinement(Plan plan, int connectors, int detours) {}
    private record Swap(int first, int last, double potential) {}
    private record Leg(List<Survey.Pos> path, double length) {}

    private static Survey.Pos end(Stop stop) {
        return stop.path().get(stop.path().size() - 1);
    }

    private static double pathLength(List<Survey.Pos> path) {
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += RouteOptimizer.distance(path.get(i - 1), path.get(i));
        return length;
    }

    private static final class Connectors {
        final SeaMap map;
        final Map<Long, Leg> cache = new HashMap<>();
        int detours;

        Connectors(SeaMap map) { this.map = map; }

        Leg get(Survey.Pos from, Survey.Pos to) {
            int a = map.cell(from), b = map.cell(to);
            long key = ((long)a << 32) | (b & 0xffffffffL);
            Leg known = cache.get(key);
            if (known != null) return known;
            if (cache.size() >= MAX_CONNECTORS) return null;
            List<Survey.Pos> path = List.of();
            if (map.clear(a, b)) {
                path = a == b ? List.of(from) : List.of(from, to);
            } else if (detours < MAX_DETOURS) {
                detours++;
                path = map.path(a, b);
            }
            var leg = new Leg(path, path.isEmpty() ? Double.POSITIVE_INFINITY : pathLength(path));
            cache.put(key, leg);
            return leg;
        }
    }

    static Refinement improve(SeaMap map, Plan baseline, List<Survey.Candidate> candidates) {
        if (baseline.stops().isEmpty()) return new Refinement(baseline, 0, 0);
        var route = new ArrayList<>(baseline.stops());
        var connections = new Connectors(map);
        // Reverse a sub-tour only when both replacement water legs are actually shorter.
        for (int pass = 0; pass < 12 && connections.cache.size() < MAX_CONNECTORS; pass++) {
            if (!shortenOrder(route, connections)) break;
        }
        var positions = new HashMap<Integer, Survey.Pos>();
        for (var candidate : candidates) positions.put(candidate.id, candidate.pos);
        // Move the observation point within the group's shared coverage square.
        for (int i = 0; i < route.size(); i++) shortenStation(map,route,i,positions,baseline.radius());
        for (int i = route.size() - 1; i >= 0; i--) shortenStation(map,route,i,positions,baseline.radius());
        double length = route.stream().mapToDouble(stop -> pathLength(stop.path())).sum();
        var selected = length + EPS < baseline.length()
            ? new Plan(List.copyOf(route), baseline.unresolved(), baseline.radius(), length)
            : baseline;
        return new Refinement(selected, connections.cache.size(), connections.detours);
    }

    private static boolean shortenOrder(List<Stop> route, Connectors connections) {
        int count = route.size();
        double[] costs = new double[count];
        for (int i = 0; i < count; i++) costs[i] = pathLength(route.get(i).path());
        var proposals = new ArrayList<Swap>();
        for (int first = 0; first < count - 1; first++) {
            var before = route.get(first).path().get(0);
            for (int last = first + 1; last < count; last++) {
                double gain = costs[first] - RouteOptimizer.distance(before, end(route.get(last)));
                if (last + 1 < count) {
                    gain += costs[last + 1] - RouteOptimizer.distance(end(route.get(first)), end(route.get(last + 1)));
                }
                if (gain > EPS) proposals.add(new Swap(first, last, gain));
            }
        }
        proposals.sort(Comparator.comparingDouble(Swap::potential).reversed());
        for (var move : proposals) {
            if (connections.cache.size() >= MAX_CONNECTORS) break;
            int first = move.first(), last = move.last();
            var incoming = connections.get(route.get(first).path().get(0), end(route.get(last)));
            if (incoming == null || incoming.path().isEmpty()) continue;
            Leg outgoing = null;
            double gain = costs[first] - incoming.length();
            if (last + 1 < count) {
                outgoing = connections.get(end(route.get(first)), end(route.get(last + 1)));
                if (outgoing == null || outgoing.path().isEmpty()) continue;
                gain += costs[last + 1] - outgoing.length();
            }
            if (gain <= EPS) continue;
            var replacement = new ArrayList<Stop>();
            replacement.add(new Stop(incoming.path(), route.get(last).candidates()));
            for (int i = last; i > first; i--) {
                var backwards = new ArrayList<>(route.get(i).path());
                Collections.reverse(backwards);
                boolean clear = true;
                for (int j = 1; j < backwards.size() && clear; j++) {
                    clear = connections.map.clear(connections.map.cell(backwards.get(j - 1)), connections.map.cell(backwards.get(j)));
                }
                if (!clear) break;
                replacement.add(new Stop(List.copyOf(backwards), route.get(i - 1).candidates()));
            }
            if (replacement.size() != last - first + 1) continue;
            for (int i = 0; i < replacement.size(); i++) route.set(first + i, replacement.get(i));
            if (outgoing != null) route.set(last + 1, new Stop(outgoing.path(), route.get(last + 1).candidates()));
            return true;
        }
        return false;
    }

    private static void shortenStation(SeaMap map, List<Stop> route, int i,
                                       Map<Integer, Survey.Pos> positions, int radius) {
        var station = route.get(i);
        var incoming = station.path();
        var next = i + 1 < route.size() ? route.get(i + 1) : null;
        var before = incoming.get(Math.max(0, incoming.size() - 2));
        var after = next == null ? null : next.path().get(Math.min(1, next.path().size() - 1));
        var original = end(station);
        double best = RouteOptimizer.distance(before, original);
        if (after != null) best += RouteOptimizer.distance(original, after);
        var bestPoint = original;
        int x0 = 0, z0 = 0, x1 = map.w - 1, z1 = map.h - 1;
        for (int id : station.candidates()) {
            var p = positions.get(id);
            // Only cell centres inside every candidate's actual X/Z coverage window qualify.
            double x = (double)p.x() - map.x0 - map.step / 2;
            double z = (double)p.z() - map.z0 - map.step / 2;
            x0 = Math.max(x0, (int)Math.ceil((x - radius) / map.step));
            x1 = Math.min(x1, (int)Math.floor((x + radius) / map.step));
            z0 = Math.max(z0, (int)Math.ceil((z - radius) / map.step));
            z1 = Math.min(z1, (int)Math.floor((z + radius) / map.step));
        }
        if (x0 > x1 || z0 > z1) return;
        int nx = Math.min(8, x1 - x0), nz = Math.min(8, z1 - z0);
        int startCell = map.cell(before), endCell = after == null ? -1 : map.cell(after);
        for (int iz = 0; iz <= nz; iz++) for (int ix = 0; ix <= nx; ix++) {
            int x = x0 + ix * (x1 - x0) / Math.max(1, nx);
            int z = z0 + iz * (z1 - z0) / Math.max(1, nz);
            int cell = z * map.w + x;
            if (!map.water(cell)) continue;
            var p = map.point(cell);
            double cost = RouteOptimizer.distance(before, p);
            if (after != null) cost += RouteOptimizer.distance(p, after);
            if (cost + EPS >= best || !map.clear(startCell, cell)) continue;
            if (after != null && !map.clear(cell, endCell)) continue;
            best = cost;
            bestPoint = p;
        }
        if (bestPoint.equals(original)) return;
        var arrival = new ArrayList<>(incoming);
        if (arrival.size() > 1) arrival.remove(arrival.size() - 1);
        if (!before.equals(bestPoint)) arrival.add(bestPoint);
        route.set(i, new Stop(List.copyOf(arrival), station.candidates()));
        if (next != null) {
            var departure = new ArrayList<>(next.path());
            if (departure.size() > 1) departure.remove(0);
            if (!after.equals(bestPoint)) departure.add(0, bestPoint);
            route.set(i + 1, new Stop(List.copyOf(departure), next.candidates()));
        }
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

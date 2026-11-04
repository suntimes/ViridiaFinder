package org.example.viridiafinder;

import java.util.*;

/** Open horizontal tour. Keeps the old nearest-neighbour solution as an upper bound. */
public final class RouteOptimizer {
    private RouteOptimizer() {}
    public static double distance(Survey.Pos a,Survey.Pos b){return Math.hypot((double)a.x()-b.x(),(double)a.z()-b.z());}
    public static double length(Survey.Pos start,List<Survey.Candidate> path){
        double total=0;for(var c:path){total+=distance(start,c.pos);start=c.pos;}return total;
    }
    public static List<Survey.Candidate> nearest(Survey.Pos start,List<Survey.Candidate> todo){
        var remaining=new ArrayList<>(todo);var result=new ArrayList<Survey.Candidate>();
        while(!remaining.isEmpty()){
            var at=start;
            var next=remaining.stream().min(Comparator.<Survey.Candidate>comparingDouble(c->distance(at,c.pos)).thenComparingInt(c->c.id)).orElseThrow();
            result.add(next);remaining.remove(next);start=next.pos;
        }return result;
    }
    public static List<Survey.Candidate> optimize(Survey.Pos start,List<Survey.Candidate> points){
        int n=points.size();if(n<3)return nearest(start,points);
        double[][] dist=new double[n+1][n+1];
        for(int i=0;i<n;i++){
            dist[n][i]=dist[i][n]=distance(start,points.get(i).pos);
            for(int j=0;j<i;j++)dist[i][j]=dist[j][i]=distance(points.get(i).pos,points.get(j).pos);
        }
        var first=new ArrayList<Integer>();for(int i=0;i<n;i++)first.add(i);
        first.sort(Comparator.<Integer>comparingDouble(i->dist[n][i]).thenComparingInt(i->points.get(i).id));
        int[] best=null;double bestCost=Double.POSITIVE_INFINITY;
        // Try every initial stop, not just nearby seeds; runs off the client thread.
        for(int seed=0;seed<n;seed++){
            int[] path=new int[n];boolean[] used=new boolean[n];int at=first.get(seed);
            for(int k=0;k<n;k++){
                path[k]=at;used[at]=true;
                int next=-1;for(int j=0;j<n;j++)if(!used[j]&&(next<0||dist[at][j]<dist[at][next]))next=j;
                at=next;
            }
            double original=cost(path,dist,n);
            if(original<bestCost){best=path.clone();bestCost=original;}
            for(int pass=0;pass<128;pass++){
                double gain=-1e-7;int left=-1,right=-1;
                for(int i=0;i<n-1;i++)for(int j=i+1;j<n;j++){
                    int a=i==0?n:path[i-1],b=path[i],c=path[j];
                    double delta=dist[a][c]-dist[a][b];
                    if(j+1<n)delta+=dist[b][path[j+1]]-dist[c][path[j+1]];
                    if(delta<gain){gain=delta;left=i;right=j;}
                }
                if(left<0)break;
                while(left<right){int t=path[left];path[left++]=path[right];path[right--]=t;}
            }
            double optimized=cost(path,dist,n);
            if(optimized<bestCost){best=path.clone();bestCost=optimized;}
        }
        var result=new ArrayList<Survey.Candidate>();for(int i:best)result.add(points.get(i));return result;
    }
    private static double cost(int[] path,double[][] dist,int start){
        double total=0;for(int i:path){total+=dist[start][i];start=i;}return total;
    }
}

package io.github.offlineglass.hook.adapters.spotify;

/** One critically damped trajectory for geometry and fills; retarget preserves velocity. */
public final class SpotifyFilterTransition {
    private final double[] start=new double[3],speed=new double[3],target=new double[3];
    private final double[] value=new double[3],velocity=new double[3];
    private long at;
    public SpotifyFilterTransition(double width,double selected,double tint,long now) {
        start[0]=target[0]=width;start[1]=target[1]=selected;start[2]=target[2]=tint;
        at=now;sample(now);
    }
    public void sample(long now) {
        double t=Math.max(0,now-at)/1000.0,w=22,e=Math.exp(-w*t);
        for(int i=0;i<3;i++) {
            double a=start[i]-target[i],b=speed[i]+w*a;
            value[i]=target[i]+(a+b*t)*e;
            velocity[i]=(b-w*(a+b*t))*e;
        }
    }
    public boolean retarget(double width,double selected,double tint,long now) {
        if(Math.abs(target[0]-width)<.01 && target[1]==selected && target[2]==tint)return false;
        sample(now);
        System.arraycopy(value,0,start,0,3);System.arraycopy(velocity,0,speed,0,3);
        target[0]=width;target[1]=selected;target[2]=tint;at=now;
        return true;
    }
    public double width(){return value[0];}
    public double selected(){return value[1];}
    public double tint(){return value[2];}
    public double widthVelocity(){return velocity[0];}
    public static float reveal(float width,int collapsed,int expanded) {
        return expanded<=collapsed ? 0f : Math.max(0f,Math.min(1f,(width-collapsed)/(expanded-collapsed)));
    }
    public static float segment(float width,int collapsed,int expanded) {
        return Math.min(width,collapsed+(expanded/2f-collapsed)*reveal(width,collapsed,expanded));
    }
}

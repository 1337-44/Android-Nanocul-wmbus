package pl.wmbuster.nanocullogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual direction-finding (AoA): robust crossing of lines of bearing.
 * Input azimuth is TRUE north clockwise and must be recorded explicitly
 * with antenna boresight pointed at a signal maximum, never auto sampled
 * from arbitrary phone motion.
 *
 * Estimates are heuristic, with no statistical 95% guarantee. Indoor
 * multipath/reflections, magnetic interference and side lobes can bias AoA.
 */
public final class BearingSolver {
    private static final double METERS_LAT = 111195.0;
    private BearingSolver() {}

    public static final class Bearing {
        public final double lat,lon,azimuthDeg,gpsAccuracyM,headingAccuracyDeg;
        public final long timestamp;
        public Bearing(double lat,double lon,double azimuthDeg,
                       double gpsAccuracyM,double headingAccuracyDeg,long timestamp) {
            this.lat=lat;this.lon=lon;this.azimuthDeg=azimuthDeg;
            this.gpsAccuracyM=gpsAccuracyM;
            this.headingAccuracyDeg=headingAccuracyDeg;this.timestamp=timestamp;
        }
    }
    public static final class Result {
        public final double lat,lon,uncertaintyM,angularErrorDeg,score;
        public final int sites,inliers,measurements;
        Result(double lat,double lon,double radius,double error,double score,
               int sites,int inliers,int observations) {
            this.lat=lat;this.lon=lon;uncertaintyM=radius;
            angularErrorDeg=error;this.score=score;this.sites=sites;
            this.inliers=inliers;this.measurements=observations;
        }
    }
    private static final class Ray {
        double x,y,dx,dy,nx,ny,accuracy,headingAccuracy;
        Ray(double x,double y,double az,double acc,double heading) {
            this.x=x;this.y=y;
            dx=Math.sin(Math.toRadians(az));dy=Math.cos(Math.toRadians(az));
            nx=dy;ny=-dx;accuracy=acc;headingAccuracy=heading;
        }
        double lateral(double px,double py) {
            return nx*(px-x)+ny*(py-y);
        }
        double forward(double px,double py) {
            return dx*(px-x)+dy*(py-y);
        }
        double angularError(double px,double py) {
            return Math.toDegrees(Math.atan2(Math.abs(lateral(px,py)),
                                     Math.max(1,forward(px,py))));
        }
    }
    public static Result estimate(List<Bearing> measurements) {
        if(measurements==null || measurements.size()<3)return null;
        List<Bearing> valid=new ArrayList<>();
        for(Bearing b:measurements) {
            if(Double.isFinite(b.lat)&&Double.isFinite(b.lon)&&
               Double.isFinite(b.azimuthDeg)&&Double.isFinite(b.gpsAccuracyM)&&
               Double.isFinite(b.headingAccuracyDeg)&&
               b.gpsAccuracyM>0&&b.gpsAccuracyM<=35&&
               b.headingAccuracyDeg>0&&b.headingAccuracyDeg<=30&&
               b.lat>=-85&&b.lat<=85&&b.lon>=-180&&b.lon<=180)
                valid.add(b);
        }
        if(valid.size()<3)return null;
        double lat0=0,lon0=0;
        for(Bearing b:valid){lat0+=b.lat;lon0+=b.lon;}
        lat0/=valid.size();lon0/=valid.size();
        double ex=METERS_LAT*Math.cos(Math.toRadians(lat0));
        if(ex<1000)return null;
        ex=Math.abs(ex);
        List<Ray> rays=new ArrayList<>();
        for(Bearing b:valid) {
            double x=(b.lon-lon0)*ex,y=(b.lat-lat0)*METERS_LAT;
            boolean duplicate=false;
            for(Ray r:rays) {
                if(Math.hypot(r.x-x,r.y-y)<15) {duplicate=true;break;}
            }
            if(!duplicate)rays.add(new Ray(x,y,b.azimuthDeg,
                                         b.gpsAccuracyM,b.headingAccuracyDeg));
        }
        if(rays.size()<3)return null;
        double span=0;
        for(Ray a:rays)for(Ray b:rays)
            span=Math.max(span,Math.hypot(a.x-b.x,a.y-b.y));
        if(span<30)return null;

        // Initial weighted least squares for perpendicular bearing errors.
        double[] candidate=solve(rays,null,null);
        if(candidate==null)return null;
        for(int repeat=0;repeat<5;repeat++) {
            double[] weights=new double[rays.size()];
            for(int i=0;i<rays.size();i++){
                Ray r=rays.get(i);
                double d=Math.hypot(candidate[0]-r.x,candidate[1]-r.y);
                double nominal=Math.max(6,r.accuracy+
                      d*Math.tan(Math.toRadians(Math.max(4,r.headingAccuracy))));
                double normalized=Math.abs(r.lateral(candidate[0],candidate[1]))/nominal;
                // Huber IRLS: cap the influence of reflected bearings.
                weights[i]=(normalized<=1.5?1:1.5/normalized)/(nominal*nominal);
                if(r.forward(candidate[0],candidate[1])<0)weights[i]*=0.05;
            }
            double[] next=solve(rays,weights,null);
            if(next==null)break;
            if(Math.hypot(candidate[0]-next[0],candidate[1]-next[1])<0.05) {
                candidate=next;break;
            }
            candidate=next;
        }
        // Keep only mutually consistent, forward-facing readings,
        // re-fit after discarding outlier compass bearings.
        List<Ray> inliers=new ArrayList<>();
        double err=0,acc=0,headingAcc=0;
        for(Ray r:rays) {
            double angle=r.angularError(candidate[0],candidate[1]);
            if(r.forward(candidate[0],candidate[1])>2 &&
               angle<=Math.max(12,2.2*r.headingAccuracy)) {
                inliers.add(r);err+=angle*angle;
                acc+=r.accuracy;headingAcc+=r.headingAccuracy;
            }
        }
        if(inliers.size()<3 || inliers.size()*2<rays.size())return null;
        double[] fit=solve(inliers,null,null);
        if(fit==null)return null;
        err=0;acc=0;headingAcc=0;double maxRange=0;
        for(Ray r:inliers) {
            double angle=r.angularError(fit[0],fit[1]);
            if(r.forward(fit[0],fit[1])<=2 || angle>Math.max(18,2.7*r.headingAccuracy))
                return null;
            err+=angle*angle;acc+=r.accuracy;headingAcc+=r.headingAccuracy;
            maxRange=Math.max(maxRange,Math.hypot(fit[0]-r.x,fit[1]-r.y));
        }
        double rms=Math.sqrt(err/inliers.size());
        double meanHeading=headingAcc/inliers.size();
        double meanGps=acc/inliers.size();
        // Conservative uncalibrated error indicator, not confidence interval.
        double radius=Math.max(12,Math.max(2.0*meanGps,
                maxRange*Math.tan(Math.toRadians(Math.max(3,rms+meanHeading)))));
        radius=Math.min(5000,radius);
        double score=Math.max(0,Math.min(95,
             35+12*Math.log1p(inliers.size())+
             12*Math.min(1,span/200)-2.4*rms-
             0.14*radius-0.3*meanHeading));
        return new Result(lat0+fit[1]/METERS_LAT,lon0+fit[0]/ex,
                          radius,rms,score,rays.size(),inliers.size(),valid.size());
    }
    private static double[] solve(List<Ray> rays,double[] weights,double[] unused) {
        double aa=0,ab=0,bb,cc=0,dd=0;
        bb=0;
        for(int i=0;i<rays.size();i++) {
            Ray r=rays.get(i);
            double w=weights==null?1.0:weights[i];
            double proj=r.nx*r.x+r.ny*r.y;
            aa+=w*r.nx*r.nx;ab+=w*r.nx*r.ny;bb+=w*r.ny*r.ny;
            cc+=w*r.nx*proj;dd+=w*r.ny*proj;
        }
        double det=aa*bb-ab*ab;
        if(det<1e-8*(aa+bb)*(aa+bb))return null;
        return new double[]{(cc*bb-ab*dd)/det,(aa*dd-ab*cc)/det};
    }
}

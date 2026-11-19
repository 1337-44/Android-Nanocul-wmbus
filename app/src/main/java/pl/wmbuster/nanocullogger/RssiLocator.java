package pl.wmbuster.nanocullogger;

import java.util.ArrayList;
import java.util.List;

/**
 * EXPERIMENTAL transmitter position estimator from relative RSSI.
 *
 * Unknown TX power is fitted as a nuisance parameter for every proposed
 * candidate; no single RSSI value is converted to an absolute distance.
 * A log-distance propagation exponent of 2.6 is an imperfect assumption.
 * Multi-path, walls, antenna orientation and movement routinely overwhelm
 * this model. Do not use the result as a precise meter location.
 */
public final class RssiLocator {
    private static final double EARTH_M = 6371000.0;
    private static final double EXPONENT = 2.6;
    private static final double METERS_PER_DEG = Math.PI * EARTH_M / 180.0;

    public static final class Observation {
        public final double lat, lon, accuracyM;
        public final int rssi;
        public final long timestamp;
        public Observation(double lat, double lon, double accuracyM, int rssi, long timestamp) {
            this.lat=lat; this.lon=lon; this.accuracyM=accuracyM;
            this.rssi=rssi; this.timestamp=timestamp;
        }
    }
    public static final class Estimate {
        public final double lat,lon,uncertaintyM,fitErrorDb;
        public final int locations,measurements;
        Estimate(double lat,double lon,double uncertainty,double error,int locations,int measurements) {
            this.lat=lat; this.lon=lon; uncertaintyM=uncertainty; fitErrorDb=error;
            this.locations=locations;this.measurements=measurements;
        }
    }
    private static final class Site {
        double x,y,rssi,accuracy;
        int count;
        Site(double x,double y,int rssi,double acc) {
            this.x=x;this.y=y;this.rssi=rssi;accuracy=acc;count=1;
        }
        void update(double px,double py,int signal,double acc) {
            count++;
            // A slowly updated mean prevents one outlier or repeated bursts
            // at a fixed phone position from overwhelming the walking route.
            double a=1.0/Math.min(count,12);
            x+=a*(px-x); y+=a*(py-y);
            rssi+=a*(signal-rssi); accuracy+=a*(acc-accuracy);
        }
    }
    private static double sq(double v){return v*v;}

    /** Returns null until observations include >=4 distinct, noncollinear
     * sites covering >=60 meters and reliable enough GPS and RSSI. */
    public static Estimate estimate(List<Observation> raw) {
        if(raw==null || raw.size()<4) return null;
        List<Observation> usable=new ArrayList<>();
        for(Observation o:raw) {
            if(Double.isFinite(o.lat) && Double.isFinite(o.lon) &&
               Double.isFinite(o.accuracyM) && o.accuracyM>0 && o.accuracyM<=35 &&
               o.lat>=-85 && o.lat<=85 && o.lon>=-180 && o.lon<=180 &&
               o.rssi>=-120 && o.rssi<=-20) usable.add(o);
        }
        if(usable.size()<4) return null;

        double lat0=0,lon0=0;
        for(Observation o:usable){lat0+=o.lat;lon0+=o.lon;}
        lat0/=usable.size();lon0/=usable.size();
        double eastScale=METERS_PER_DEG*Math.cos(Math.toRadians(lat0));
        double northScale=METERS_PER_DEG;

        List<Site> sites=new ArrayList<>();
        for(Observation o:usable) {
            double x=(o.lon-lon0)*eastScale,y=(o.lat-lat0)*northScale;
            Site closest=null;
            double best=15*15;
            for(Site s:sites) {
                double d=sq(x-s.x)+sq(y-s.y);
                if(d<best){best=d;closest=s;}
            }
            if(closest==null)sites.add(new Site(x,y,o.rssi,o.accuracyM));
            else closest.update(x,y,o.rssi,o.accuracyM);
        }
        if(sites.size()<4)return null;

        double mx=0,my=0;
        for(Site s:sites){mx+=s.x;my+=s.y;}
        mx/=sites.size(); my/=sites.size();
        double xx=0,xy=0,yy=0,span=0,meanAcc=0;
        for(Site s:sites) {
            xx+=sq(s.x-mx);xy+=(s.x-mx)*(s.y-my);yy+=sq(s.y-my);
            meanAcc+=s.accuracy;
            for(Site t:sites) span=Math.max(span,Math.hypot(s.x-t.x,s.y-t.y));
        }
        meanAcc/=sites.size();
        // Disallow tiny or essentially straight-line routes: mirrored
        // solutions cannot be distinguished by signal strength alone.
        double tr=xx+yy;
        double det=xx*yy-xy*xy;
        double discr=Math.sqrt(Math.max(0,tr*tr-4*det));
        double major=(tr+discr)/2,minor=(tr-discr)/2;
        if(span<60 || major<=0 || minor/major<0.075)return null;

        // Coarse-to-fine relative-RSSI least-squares search. TX power is
        // fitted at every candidate: RSSI = P - 10*n*log10(distance).
        // This provides a rough point, not a calibrated propagation model.
        double range=Math.min(1800, Math.max(120,span*1.6));
        double cx=mx,cy=my;
        for(int pass=0;pass<3;pass++) {
            double bestCost=Double.POSITIVE_INFINITY,bx=cx,by=cy;
            for(int ix=-12;ix<=12;ix++)for(int iy=-12;iy<=12;iy++) {
                double px=cx+range*ix/12.0,py=cy+range*iy/12.0;
                double cost=cost(sites,px,py);
                if(cost<bestCost){bestCost=cost;bx=px;by=py;}
            }
            cx=bx;cy=by;range/=5;
        }
        double err=Math.sqrt(cost(sites,cx,cy));
        if(!Double.isFinite(err) || err>18)return null;
        // A heuristic uncertainty envelope (NOT a 95% confidence interval).
        // Preserve generous minimum error even with noiseless synthetic data.
        double radius=Math.max(75,Math.max(3*meanAcc,span*(0.25+err/20.0)));
        radius=Math.min(radius,2500);
        return new Estimate(lat0+cy/northScale,lon0+cx/eastScale,
                            radius,err,sites.size(),usable.size());
    }

    private static double cost(List<Site> sites,double x,double y) {
        double sum=0;
        for(Site s:sites){
            double d=Math.max(3,Math.hypot(x-s.x,y-s.y));
            sum+=s.rssi+10*EXPONENT*Math.log10(d);
        }
        double power=sum/sites.size();
        double error=0;
        for(Site s:sites) {
            double d=Math.max(3,Math.hypot(x-s.x,y-s.y));
            double expected=power-10*EXPONENT*Math.log10(d);
            // Huber-ish clamp limits effect of drastic multipath/outliers.
            double residual=Math.max(-18,Math.min(18,s.rssi-expected));
            error+=residual*residual;
        }
        return error/sites.size();
    }
}

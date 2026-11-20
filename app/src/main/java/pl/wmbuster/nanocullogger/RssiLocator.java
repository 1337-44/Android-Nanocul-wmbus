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
        public final double qualityScore,exponent,spanM;
        public final int locations,measurements;
        Estimate(double lat,double lon,double uncertainty,double error,
                 double quality,double exponent,double span,
                 int locations,int measurements) {
            this.lat=lat;this.lon=lon;uncertaintyM=uncertainty;fitErrorDb=error;
            this.qualityScore=quality;this.exponent=exponent;this.spanM=span;
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

        // Relative RSSI fit: unknown Tx power AND environmental path-loss
        // exponent (1.8 .. 4.0) are nuisance parameters. A candidate position
        // with artificially tiny error is not automatically a precise fix.
        double best=Double.POSITIVE_INFINITY,bx=mx,by=my,bn=2.6;
        for(double n : new double[]{1.8,2.2,2.6,3.0,3.4,4.0}) {
            double range=Math.min(1800,Math.max(120,span*1.6));
            double cx=mx,cy=my;
            for(int pass=0;pass<3;pass++) {
                double passBest=Double.POSITIVE_INFINITY,pxBest=cx,pyBest=cy;
                for(int ix=-9;ix<=9;ix++)for(int iy=-9;iy<=9;iy++) {
                    double px=cx+range*ix/9.0,py=cy+range*iy/9.0;
                    double value=cost(sites,px,py,n);
                    if(value<passBest){passBest=value;pxBest=px;pyBest=py;}
                }
                cx=pxBest;cy=pyBest;
                range/=5;
            }
            double value=cost(sites,cx,cy,n);
            if(value<best){best=value;bx=cx;by=cy;bn=n;}
        }
        double err=Math.sqrt(best);
        if(!Double.isFinite(err)||err>17)return null;
        if(Math.hypot(bx-mx,by-my)>Math.max(300,span*2))return null;

        // Heuristic radius/score. They do NOT represent calibrated
        // confidence probabilities and can be wrong due to NLOS/multipath.
        double radius=Math.max(75,Math.max(3*meanAcc,
                             span*(0.25+err/20.0)));
        radius=Math.min(radius,2500);
        double quality=Math.max(0,Math.min(83,
            18+12*Math.log1p(sites.size())+17*Math.min(1,span/250)-
            2.9*err-0.035*radius));
        if(quality<15)return null;
        return new Estimate(lat0+by/northScale,lon0+bx/eastScale,
                            radius,err,quality,bn,span,sites.size(),usable.size());
    }

    private static double cost(List<Site> sites,double x,double y,double exponent) {
        double sum=0,meanWeight=0;
        for(Site s:sites) {
            double d=Math.max(3,Math.hypot(x-s.x,y-s.y));
            // One site counts once, not once per every meter telegram.
            double w=1.0/Math.max(1,Math.sqrt(s.accuracy/5.0));
            sum+=w*(s.rssi+10*exponent*Math.log10(d));
            meanWeight+=w;
        }
        if(meanWeight==0)return Double.POSITIVE_INFINITY;
        double power=sum/meanWeight;
        double error=0;
        for(Site s:sites) {
            double d=Math.max(3,Math.hypot(x-s.x,y-s.y));
            double expected=power-10*exponent*Math.log10(d);
            double residual=Math.abs(s.rssi-expected);
            // Huber's bounded-influence loss resists severe fading/outliers.
            error+=residual<=8 ? residual*residual :
                   16*residual-64;
        }
        return error/sites.size();
    }
}

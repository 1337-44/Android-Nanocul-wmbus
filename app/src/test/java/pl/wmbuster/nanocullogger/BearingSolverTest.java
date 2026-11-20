package pl.wmbuster.nanocullogger;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class BearingSolverTest {
    private static final double LAT=51.77;
    private static final double LON=19.43;
    private static final double NORTH=111195;
    private static final double EAST=NORTH*Math.cos(Math.toRadians(LAT));

    private static BearingSolver.Bearing at(double eastM,double northM,
                                           double deviationDeg) {
        double az=(Math.toDegrees(Math.atan2(-eastM,-northM))+360)%360;
        return new BearingSolver.Bearing(
            LAT+northM/NORTH,LON+eastM/EAST,az+deviationDeg,4,6,
            1700000000000L);
    }
    @Test public void threeGoodCrossingBearingsFindTarget() {
        List<BearingSolver.Bearing> samples=new ArrayList<>();
        samples.add(at(-120,-30,1));
        samples.add(at(100,-90,-1));
        samples.add(at(135,90,1));
        samples.add(at(-100,100,-1));
        BearingSolver.Result fix=BearingSolver.estimate(samples);
        assertNotNull("Cross-bearing should estimate a point",fix);
        double error=Math.hypot((fix.lat-LAT)*NORTH,(fix.lon-LON)*EAST);
        assertTrue("Expected <30m of synthetic truth, got "+error,error<30);
        assertTrue("Heuristic radius must not be exact",fix.uncertaintyM>=12);
        assertTrue("Quality score should be nonzero",fix.score>15);
        assertTrue(fix.inliers>=3);
    }
    @Test public void identicalPhoneStationIsNeverEnough() {
        List<BearingSolver.Bearing> samples=new ArrayList<>();
        for(int i=0;i<12;i++)samples.add(at(-100,30,i%3));
        assertNull(BearingSolver.estimate(samples));
    }
    @Test public void parallelDirectionsAreAmbiguous() {
        List<BearingSolver.Bearing> samples=new ArrayList<>();
        samples.add(new BearingSolver.Bearing(LAT,LON,45,4,6,0));
        samples.add(new BearingSolver.Bearing(LAT+90/NORTH,LON,45,4,6,0));
        samples.add(new BearingSolver.Bearing(LAT-90/NORTH,LON+100/EAST,45,4,6,0));
        assertNull(BearingSolver.estimate(samples));
    }
    @Test public void poorCompassCannotCreateFalsePrecision() {
        List<BearingSolver.Bearing> samples=new ArrayList<>();
        samples.add(new BearingSolver.Bearing(LAT-40/NORTH,LON,0,4,50,0));
        samples.add(new BearingSolver.Bearing(LAT+30/NORTH,LON,180,4,50,0));
        samples.add(new BearingSolver.Bearing(LAT,LON+50/EAST,270,4,50,0));
        assertNull(BearingSolver.estimate(samples));
    }
}

package pl.wmbuster.nanocullogger;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class RssiLocatorTest {
    private static final double LAT=51.77, LON=19.43;
    private static final double NORTH=111195;
    private static final double EAST=NORTH*Math.cos(Math.toRadians(LAT));

    @Test public void cannotLocateFromRepeatedSingleSpot() {
        List<RssiLocator.Observation> points=new ArrayList<>();
        for(int i=0;i<30;i++)points.add(
            new RssiLocator.Observation(LAT,LON,5,-62+i%3,i));
        assertNull(RssiLocator.estimate(points));
    }
    @Test public void cannotLocateFromCollinearWalk() {
        List<RssiLocator.Observation> points=new ArrayList<>();
        for(int i=0;i<10;i++)points.add(
            new RssiLocator.Observation(LAT,LON+i*25/EAST,4,-62-i,i));
        assertNull(RssiLocator.estimate(points));
    }
    @Test public void estimatesNearSyntheticTransmitterWithAreaNotExactPin() {
        List<RssiLocator.Observation> points=new ArrayList<>();
        for(int i=0;i<24;i++) {
            double angle=2*Math.PI*i/24.0;
            double distance=65+((i*31)%165);
            double x=distance*Math.cos(angle),y=distance*Math.sin(angle);
            // Unknown TX power, path-loss exponent 2.6 plus realistic noise.
            int rssi=(int)Math.round(-40-26*Math.log10(distance)+
                    (i%5-2)*0.7);
            points.add(new RssiLocator.Observation(
                LAT+y/NORTH,LON+x/EAST,5,rssi,i));
        }
        RssiLocator.Estimate e=RssiLocator.estimate(points);
        assertNotNull(e);
        double offset=Math.hypot((e.lon-LON)*EAST,(e.lat-LAT)*NORTH);
        assertTrue("Offset="+offset,offset<95);
        assertTrue(e.uncertaintyM>=75);
        assertTrue(e.locations>=4);
    }
    @Test public void discardsInaccurateGps() {
        List<RssiLocator.Observation> points=new ArrayList<>();
        for(int i=0;i<20;i++)points.add(
            new RssiLocator.Observation(LAT+i*15/NORTH,LON+i*18/EAST,500,-65,i));
        assertNull(RssiLocator.estimate(points));
    }
}

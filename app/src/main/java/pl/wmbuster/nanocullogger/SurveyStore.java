package pl.wmbuster.nanocullogger;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Local survey storage: one meter identity with samples and a recalculated
 * RSSI-only candidate position. Never infer location from a single packet.
 * No network access, no extra location permission beyond the recorder. */
public final class SurveyStore {
    private final File file;
    private final JSONObject survey;
    private final BestPositionStore bestStore;
    private long lastPersistMs=0L;
    private String lastStatus="Brak pomiarów GPS/RSSI";
    private static JSONObject read(File file) {
        if(file.length()<=0 || file.length()>12_000_000) return new JSONObject();
        try(FileInputStream in=new FileInputStream(file)) {
            byte[] bytes=new byte[(int)file.length()];
            int offset=0,n;
            while(offset<bytes.length &&
                  (n=in.read(bytes,offset,bytes.length-offset))>0) offset+=n;
            if(offset==bytes.length)
                return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        } catch(Exception ignored) {}
        return new JSONObject();
    }
    /** Loads the latest completed atomic snapshot, including changes made by
     * the foreground receiver running in the same application process. */
    public static JSONObject readSnapshot(Context context) {
        return read(new File(context.getFilesDir(),"radio-survey.json"));
    }
    public SurveyStore(Context context) {
        file=new File(context.getFilesDir(),"radio-survey.json");
        survey=read(file);
        bestStore=new BestPositionStore(context);
    }
    private static String key(WmbusDecoder.Result d) {
        return d.manufacturer+":"+d.idHex+":"+d.version+":"+d.medium;
    }
    public synchronized File bestPositionsFile() {
        return bestStore.csvFile();
    }
    public synchronized JSONObject bestPositions() {
        return bestStore.snapshot();
    }
    private static RssiLocator.Observation sample(JSONObject j) {
        return new RssiLocator.Observation(
                j.optDouble("lat",Double.NaN),j.optDouble("lon",Double.NaN),
                j.optDouble("accuracy_m",Double.NaN),j.optInt("rssi",-127),
                j.optLong("timestamp",0));
    }
    /** Called for every valid, CRC-confirmed radio telegram. If GPS or RSSI is
     * missing, re-evaluate existing samples without adding a fake reading. */
    public synchronized JSONObject update(
            WmbusDecoder.Result d,Integer rssi,JSONObject gps,long epochMs) {
        try {
            String identity=key(d);
            JSONObject meter=survey.optJSONObject(identity);
            boolean newMeter=meter==null;
            if(meter==null) {
                meter=new JSONObject();
                meter.put("id",d.id);
                meter.put("manufacturer",d.manufacturer);
                meter.put("medium",d.media);
                meter.put("version",d.version);
                meter.put("key",identity);
                meter.put("samples",new JSONArray());
                survey.put(identity,meter);
            }
            JSONArray samples=meter.getJSONArray("samples");
            meter.put("received_count",meter.optInt("received_count",0)+1);
            boolean added=false;
            String reason;
            if(gps==null || !gps.has("lat") || !gps.has("lon")) {
                reason="Brak aktualnego GPS (fix maks. 20 s)";
            } else {
                double acc=gps.optDouble("accuracy_m",Double.NaN);
                double lat=gps.optDouble("lat",Double.NaN);
                double lon=gps.optDouble("lon",Double.NaN);
                meter.put("last_gps_accuracy_m",
                        Double.isFinite(acc)?acc:JSONObject.NULL);
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||
                   lat<-85||lat>85||lon<-180||lon>180){
                    reason="Błędne współrzędne GPS";
                } else if(!Double.isFinite(acc)||acc<=0||acc>35) {
                    reason="GPS ±"+(Double.isFinite(acc)?Math.round(acc)+" m":"?")+
                           " (wymagane <=35 m; użyj dokładnej lokalizacji)";
                } else {
                    // GPS-only reception is still a useful point on the map.
                    // It must NOT be fed to the RSSI location estimator.
                    boolean goodRssi=rssi!=null && rssi>=-120 && rssi<=-20;
                    JSONObject point=new JSONObject();
                    point.put("lat",lat);point.put("lon",lon);
                    point.put("accuracy_m",acc);
                    point.put("rssi",goodRssi?rssi:JSONObject.NULL);
                    point.put("timestamp",epochMs);
                    // Preserve geographic diversity across multiple sessions.
                    // Repeated packets at one position update a clustered
                    // reception site instead of evicting older locations.
                    JSONObject nearby=null;
                    for(int i=0;i<samples.length();i++) {
                        JSONObject old=samples.optJSONObject(i);
                        if(old==null)continue;
                        double oldLat=old.optDouble("lat",Double.NaN);
                        double oldLon=old.optDouble("lon",Double.NaN);
                        if(!Double.isFinite(oldLat)||!Double.isFinite(oldLon))continue;
                        double dy=(lat-oldLat)*111195;
                        double dx=(lon-oldLon)*111195*Math.cos(Math.toRadians(lat));
                        if(Math.hypot(dx,dy)<12) {nearby=old;break;}
                    }
                    if(nearby==null) {
                        point.put("count",1);
                        samples.put(point);
                    } else {
                        int count=nearby.optInt("count",1);
                        // Do not give arbitrary duplicate receptions excessive
                        // weight. Allow slow replacement of previous values
                        // if the antenna/GPS accuracy improves.
                        if(goodRssi) {
                            int oldRssi=nearby.optInt("rssi",-127);
                            int updated=oldRssi<=-121?rssi:
                                (int)Math.round(oldRssi+(rssi-oldRssi)/
                                    (double)Math.min(10,count+1));
                            nearby.put("rssi",updated);
                        }
                        if(acc<nearby.optDouble("accuracy_m",1000)) {
                            nearby.put("lat",lat);
                            nearby.put("lon",lon);
                            nearby.put("accuracy_m",acc);
                        }
                        nearby.put("timestamp",epochMs);
                        nearby.put("count",Math.min(100000,count+1));
                    }
                    added=true;
                    reason=goodRssi?"Punkt GPS i RSSI zapisany":
                        "Punkt GPS zapisany; brak RSSI do wyznaczenia nadajnika";
                }
            }
            meter.put("last_gps_status",reason);
            meter.put("last_rssi_dbm",rssi==null?JSONObject.NULL:rssi);
            lastStatus=d.manufacturer+" "+d.id+": "+reason;
            // Up to 400 spatially diverse sites PER meter, retained across
            // restarts and previous walks (vs old 160 raw latest telegrams).
            while(samples.length()>400)samples.remove(0);
            JSONObject pos=recalculate(identity,meter,epochMs);
            meter.put("last_seen",epochMs);
            // Previously only GPS-accepted frames were persisted. If no GPS
            // or coarse location was available, the map had no meters at all.
            // Persist even zero-sample meter metadata, without fsync on each
            // high-rate telegram.
            if(newMeter || epochMs-lastPersistMs>=3500L) {
                if(persist())lastPersistMs=epochMs;
            }
            return pos;
        } catch(Exception ignored) {
            return null;
        }
    }
    /**
     * Re-estimate from ALL retained survey sessions and manual compass
     * bearings; only improve the separate best-position CSV when quality
     * improves. If new evidence is worse, keep the previous best.
     */
    private JSONObject recalculate(String identity,JSONObject meter,long timestamp)
            throws Exception {
        JSONArray arr=meter.optJSONArray("samples");
        List<RssiLocator.Observation> pts=new ArrayList<>();
        if(arr!=null)for(int i=0;i<arr.length();i++) {
            JSONObject p=arr.optJSONObject(i);
            if(p!=null)pts.add(sample(p));
        }
        RssiLocator.Estimate rssi=RssiLocator.estimate(pts);
        JSONArray bArr=meter.optJSONArray("bearings");
        List<BearingSolver.Bearing> rays=new ArrayList<>();
        if(bArr!=null)for(int i=0;i<bArr.length();i++) {
            JSONObject v=bArr.optJSONObject(i);
            if(v!=null)rays.add(new BearingSolver.Bearing(
                v.optDouble("lat",Double.NaN),v.optDouble("lon",Double.NaN),
                v.optDouble("azimuth_deg",Double.NaN),
                v.optDouble("gps_accuracy_m",Double.NaN),
                v.optDouble("heading_accuracy_deg",Double.NaN),
                v.optLong("timestamp",0)));
        }
        BearingSolver.Result direction=BearingSolver.estimate(rays);
        JSONObject candidate=null;
        if(rssi!=null) {
            candidate=new JSONObject();
            candidate.put("lat",rssi.lat);
            candidate.put("lon",rssi.lon);
            candidate.put("uncertainty_m",rssi.uncertaintyM);
            candidate.put("quality_score",rssi.qualityScore);
            candidate.put("fit_error_db",rssi.fitErrorDb);
            candidate.put("path_loss_exponent",rssi.exponent);
            candidate.put("distinct_sites",rssi.locations);
            candidate.put("observations",rssi.measurements);
            candidate.put("method","RSSI_GPS");
            candidate.put("status","estimated_not_verified");
        }
        if(direction!=null && (candidate==null ||
             direction.score>=candidate.optDouble("quality_score",0)-8 ||
             direction.uncertaintyM<
                 candidate.optDouble("uncertainty_m",10000)*0.65)) {
            candidate=new JSONObject();
            candidate.put("lat",direction.lat);
            candidate.put("lon",direction.lon);
            candidate.put("uncertainty_m",direction.uncertaintyM);
            candidate.put("quality_score",direction.score);
            candidate.put("angular_error_deg",direction.angularErrorDeg);
            candidate.put("distinct_sites",direction.sites);
            candidate.put("bearing_inliers",direction.inliers);
            candidate.put("observations",direction.measurements);
            candidate.put("method","CROSS_BEARING");
            candidate.put("status","estimated_not_verified");
        }
        if(candidate!=null) {
            bestStore.consider(identity,meter.optString("id"),
                        meter.optString("manufacturer"),candidate,timestamp);
            meter.put("estimate",candidate);
        } else {
            meter.remove("estimate");
        }
        JSONObject best=bestStore.get(identity);
        if(best!=null)meter.put("best_estimate",best);
        return best!=null?best:candidate;
    }

    /** Store a deliberate compass bearing from a site with high RSSI.
     * The phone TOP EDGE must face the antenna boresight. This method
     * never records passive compass orientations from ordinary packets.
     * Bearings from previous sessions remain available to the solver. */
    public synchronized boolean addBearing(
            String identity,double lat,double lon,double gpsAccuracy,
            double azimuthTrueDeg,double headingAccuracy,long timestamp)
            throws Exception {
        if(identity==null || !Double.isFinite(lat)||!Double.isFinite(lon)||
           !Double.isFinite(azimuthTrueDeg)||!Double.isFinite(gpsAccuracy)||
           !Double.isFinite(headingAccuracy)||gpsAccuracy<=0||
           gpsAccuracy>35||headingAccuracy<=0||headingAccuracy>30||
           lat<-85||lat>85||lon<-180||lon>180) return false;
        JSONObject meter=survey.optJSONObject(identity);
        if(meter==null) return false;
        JSONArray bearings=meter.optJSONArray("bearings");
        if(bearings==null){bearings=new JSONArray();meter.put("bearings",bearings);}
        JSONObject value=new JSONObject();
        value.put("lat",lat);
        value.put("lon",lon);
        value.put("gps_accuracy_m",gpsAccuracy);
        value.put("azimuth_deg",(azimuthTrueDeg%360+360)%360);
        value.put("heading_accuracy_deg",headingAccuracy);
        value.put("timestamp",timestamp);
        // At each site use only the latest explicit calibrated sweep.
        for(int i=bearings.length()-1;i>=0;i--) {
            JSONObject old=bearings.optJSONObject(i);
            if(old==null)continue;
            double dy=(lat-old.optDouble("lat",lat))*111195;
            double dx=(lon-old.optDouble("lon",lon))*111195*
                Math.cos(Math.toRadians(lat));
            if(Math.hypot(dx,dy)<15)bearings.remove(i);
        }
        bearings.put(value);
        while(bearings.length()>120)bearings.remove(0);
        recalculate(identity,meter,timestamp);
        persist();
        return true;
    }

    public synchronized void flush() {persist();}
    public synchronized String latestStatus() {return lastStatus;}

    public static final class ImportReport {
        public int recoveredFrames;
        public int withGps;
        public int skipped;
    }

    /**
     * Restore GPS/RSSI survey history from a previously exported session
     * JSONL, including logs written by v0.1/v0.2/v0.3.
     * Decoder_update/invalid frames are skipped. The file remains on disk.
     * Should be called from a background thread with the USB service stopped.
     * Do not import the same log twice: observations may be duplicated.
     */
    public static ImportReport importFromJsonl(Context context,InputStream stream)
            throws IOException {
        if(stream==null)throw new IOException("Brak strumienia pliku");
        SurveyStore store=new SurveyStore(context);
        ImportReport report=new ImportReport();
        try(BufferedReader reader=new BufferedReader(
                new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;
            int lineCount=0;
            long totalChars=0;
            while((line=reader.readLine())!=null) {
                totalChars+=line.length();
                if(++lineCount>20000 || totalChars>20_000_000L)
                    throw new IOException("Log zbyt duży; limit 20 MB / 20 000 wierszy");
                if(line.isEmpty())continue;
                try {
                    JSONObject event=new JSONObject(line);
                    if(!"nanoCUL_USB".equals(event.optString("source")) ||
                       !event.has("frame_hex") ||
                       !event.has("manufacturer") || !event.has("id"))
                        continue;
                    String manufacturer=event.optString("manufacturer");
                    String id=event.optString("id");
                    String idHex=event.optString("id_hex");
                    if(manufacturer.isEmpty()||id.isEmpty()||idHex.isEmpty()){
                        report.skipped++;continue;
                    }
                    WmbusDecoder.Result d=new WmbusDecoder.Result(
                        manufacturer,id,idHex,event.optString("media","Unknown"),
                        event.optInt("version",0),event.optInt("medium_code",0),
                        event.optInt("ci",0),event.optString("decode_status","imported"),
                        event.optString("summary",""),new ArrayList<>());
                    Integer rssi=event.isNull("rssi_dbm")?null:
                        (event.has("rssi_dbm")?event.optInt("rssi_dbm",-127):null);
                    JSONObject gps=event.optJSONObject("location");
                    long when;
                    try{when=Instant.parse(event.optString("timestamp_utc")).toEpochMilli();}
                    catch(Exception ex){when=System.currentTimeMillis();}
                    store.update(d,rssi,gps,when);
                    report.recoveredFrames++;
                    if(gps!=null &&
                       gps.optDouble("accuracy_m",Double.NaN)<=35 &&
                       gps.optDouble("accuracy_m",Double.NaN)>0)
                        report.withGps++;
                }catch(Exception ignored) {
                    report.skipped++;
                }
            }
        }
        // Ensure the most recent counters are written after an imported run.
        store.persist();
        return report;
    }

    public synchronized JSONObject snapshot() {
        try {return new JSONObject(survey.toString());}
        catch(Exception e) {return new JSONObject();}
    }
    private boolean persist() {
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream stream=new FileOutputStream(temp)) {
            stream.write(survey.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        } catch(Exception e){return false;}
        // Atomic swap on the same filesystem.
        if(!temp.renameTo(file)) {
            temp.delete();
            return false;
        }
        return true;
    }
}

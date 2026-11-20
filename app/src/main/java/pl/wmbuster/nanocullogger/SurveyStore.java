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
    }
    private static String key(WmbusDecoder.Result d) {
        return d.manufacturer+":"+d.idHex+":"+d.version+":"+d.medium;
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
            } else if(rssi==null || rssi<-120 || rssi>-20) {
                reason="Brak poprawnego RSSI";
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
                    JSONObject point=new JSONObject();
                    point.put("lat",lat);point.put("lon",lon);
                    point.put("accuracy_m",acc);
                    point.put("rssi",rssi);point.put("timestamp",epochMs);
                    samples.put(point);
                    added=true;
                    reason="Punkt GPS i RSSI zapisany";
                }
            }
            meter.put("last_gps_status",reason);
            meter.put("last_rssi_dbm",rssi==null?JSONObject.NULL:rssi);
            lastStatus=d.manufacturer+" "+d.id+": "+reason;
            // Retain a bounded number of measurements per meter.
            while(samples.length()>160)samples.remove(0);
            List<RssiLocator.Observation> pts=new ArrayList<>(samples.length());
            for(int i=0;i<samples.length();i++) {
                JSONObject s=samples.optJSONObject(i);
                if(s!=null)pts.add(sample(s));
            }
            RssiLocator.Estimate estimate=RssiLocator.estimate(pts);
            JSONObject pos=null;
            if(estimate!=null) {
                pos=new JSONObject();
                pos.put("lat",estimate.lat);
                pos.put("lon",estimate.lon);
                pos.put("uncertainty_m",estimate.uncertaintyM);
                pos.put("fit_error_db",estimate.fitErrorDb);
                pos.put("distinct_sites",estimate.locations);
                pos.put("observations",estimate.measurements);
                pos.put("model","relative_rssi_log_distance_v1");
                pos.put("status","estimated_not_verified");
                meter.put("estimate",pos);
            } else {
                meter.remove("estimate");
            }
            meter.put("last_seen",epochMs);
            // Previously only GPS-accepted frames were persisted. If no GPS
            // or coarse location was available, the map had no meters at all.
            // Persist even zero-sample meter metadata, without fsync on each
            // high-rate telegram.
            if(added || newMeter || epochMs-lastPersistMs>=5000L) {
                if(persist())lastPersistMs=epochMs;
            }
            return pos;
        } catch(Exception ignored) {
            return null;
        }
    }
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
                    if(gps!=null && rssi!=null &&
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

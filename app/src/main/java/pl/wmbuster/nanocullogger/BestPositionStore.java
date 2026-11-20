package pl.wmbuster.nanocullogger;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Persistent best estimate across GPS walks and Android app restarts.
 * A candidate may only replace a best estimate if its heuristic evidence
 * quality has improved or its uncertainty has substantially reduced.
 * This score is a ranking metric, NEVER a calibrated probability.
 *
 * Separate 'transmitter_positions.csv' in app-specific Documents/nanoCUL.
 * Use Export in the UI to store a user-owned copy outside this sandbox
 * before uninstalling the APK.
 */
public final class BestPositionStore {
    private final File metadata;
    private final File csv;
    private JSONObject best;
    public BestPositionStore(Context context) {
        metadata=new File(context.getFilesDir(),"best_transmitters.json");
        File dir=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if(dir==null)dir=context.getFilesDir();
        dir=new File(dir,"nanoCUL");
        if(!dir.exists())dir.mkdirs();
        csv=new File(dir,"transmitter_positions.csv");
        best=readJson(metadata);
        // For users upgrading from earlier app versions, regenerate a
        // human-readable CSV if the JSON registry already exists.
        if(!csv.exists())saveCsv();
    }
    public File csvFile() {return csv;}

    private static JSONObject readJson(File file) {
        try {
            if(!file.isFile() || file.length()>2_000_000)return new JSONObject();
            byte[] bytes=new byte[(int)file.length()];
            try(FileInputStream in=new FileInputStream(file)) {
                int offset=0,n;
                while(offset<bytes.length && (n=in.read(bytes,offset,bytes.length-offset))>0)
                    offset+=n;
                if(offset==bytes.length)
                    return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            }
        }catch(Exception ignored){}
        return new JSONObject();
    }
    private static void atomicWrite(File target,byte[] data) throws Exception {
        File tmp=new File(target.getParentFile(),target.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if(!tmp.renameTo(target)) {
            tmp.delete();
            throw new Exception("Nie można zapisać "+target.getName());
        }
    }
    private static String csvCell(String value) {
        if(value==null)return "";
        return "\""+value.replace("\"","\"\"").replace("\r"," ").replace("\n"," ")+"\"";
    }
    private synchronized void saveCsv() {
        StringBuilder out=new StringBuilder();
        out.append("serial,manufacturer,latitude,longitude,quality_score,uncertainty_m,method,sites,updated_utc\n");
        JSONArray names=best.names();
        if(names!=null)for(int i=0;i<names.length();i++) {
            JSONObject record=best.optJSONObject(names.optString(i));
            if(record==null)continue;
            out.append(csvCell(record.optString("serial"))).append(',')
               .append(csvCell(record.optString("manufacturer"))).append(',')
               .append(record.optDouble("lat")).append(',')
               .append(record.optDouble("lon")).append(',')
               .append(record.optDouble("quality_score")).append(',')
               .append(record.optDouble("uncertainty_m")).append(',')
               .append(csvCell(record.optString("method"))).append(',')
               .append(record.optInt("sites")).append(',')
               .append(csvCell(record.optString("updated_utc"))).append('\n');
        }
        try {atomicWrite(csv,out.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){android.util.Log.w("BestPositionStore","CSV export failed",e);}
    }
    public synchronized JSONObject snapshot() {
        try {return new JSONObject(best.toString());}
        catch(Exception ignored){return new JSONObject();}
    }
    public synchronized JSONObject get(String key) {
        JSONObject item=best.optJSONObject(key);
        if(item==null)return null;
        try{return new JSONObject(item.toString());}
        catch(Exception e){return null;}
    }

    /** Writes first qualified fix, then replaces it ONLY on measured gain.
     * Never invent an estimate from a meter ID alone. */
    public synchronized boolean consider(
            String key,String serial,String manufacturer,JSONObject candidate,
            long timestamp) {
        if(key==null || serial==null || serial.isEmpty() || candidate==null)return false;
        double lat=candidate.optDouble("lat",Double.NaN);
        double lon=candidate.optDouble("lon",Double.NaN);
        double quality=candidate.optDouble("quality_score",Double.NaN);
        double uncertainty=candidate.optDouble("uncertainty_m",Double.NaN);
        int sites=candidate.optInt("distinct_sites",0);
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||
           !Double.isFinite(quality)||!Double.isFinite(uncertainty)||
           lat<-85||lat>85||lon<-180||lon>180||
           quality<15||quality>100||uncertainty<2||uncertainty>5000||sites<3)
            return false;
        JSONObject old=best.optJSONObject(key);
        if(old!=null) {
            double oldQuality=old.optDouble("quality_score",0);
            double oldRadius=old.optDouble("uncertainty_m",5000);
            boolean betterScore=quality>=oldQuality+3.0 &&
                                uncertainty<=oldRadius*1.18;
            boolean betterUncertainty=uncertainty<=oldRadius*0.75 &&
                                      quality>=oldQuality-1.0;
            if(!betterScore && !betterUncertainty)return false;
        }
        try {
            JSONObject next=new JSONObject(candidate.toString());
            next.put("serial",serial);
            next.put("manufacturer",manufacturer);
            next.put("key",key);
            next.put("updated_utc",Instant.ofEpochMilli(timestamp).toString());
            JSONObject copy=new JSONObject(best.toString());
            copy.put(key,next);
            atomicWrite(metadata,copy.toString().getBytes(StandardCharsets.UTF_8));
            best=copy;
            saveCsv();
            return true;
        } catch(Exception e) {
            android.util.Log.e("BestPositionStore","Could not update best position",e);
            return false;
        }
    }
}

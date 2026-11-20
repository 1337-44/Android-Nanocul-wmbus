package pl.wmbuster.nanocullogger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARDF / fox-hunting mode with manually recorded lines of bearing.
 * Rotate a directional antenna while reading RSSI for ONE meter;
 * only click SAVE at a confirmed signal maximum.
 * Hold phone FLAT, display up, top edge along antenna boresight.
 * No continuous sampling while moving: arbitrary phone azimuths are NOT AoA.
 */
public final class DirectionCaptureActivity extends Activity implements SensorEventListener {
    private static final long MAX_GPS_AGE_NS=20_000_000_000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SensorManager sensors;
    private Sensor rotation;
    private TextView headingText,helpText,siteText,selectionText,offsetText;
    private Spinner meters;
    private SeekBar offsetBar;
    private final List<String> meterKeys=new ArrayList<>();
    private String lastMeterList="";
    private double magneticHeading=Double.NaN,headingAccuracy=35;
    private int sensorAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
    private Location phonePosition;
    private final Runnable uiRefresh=new Runnable() {
        @Override public void run() {
            refreshMeters();
            refreshInfo();
            handler.postDelayed(this,1500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroller=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p=(int)(18*getResources().getDisplayMetrics().density);
        root.setPadding(p,p,p,p);
        scroller.addView(root);
        setContentView(scroller);
        root.addView(text("Łowy na lisa — antena kierunkowa",21,true));
        root.addView(text(
           "To tryb RĘCZNY. Wybierz licznik, obróć antenę kierunkową w stronę "+
           "MAKSYMALNEGO RSSI, ustaw telefon ekranem do góry i jego górną "+
           "krawędzią wzdłuż osi anteny. Dopiero wtedy naciśnij ZAPISZ AZYMUT. "+
           "Powtórz w przynajmniej 3 odległych stanowiskach (co najmniej 30 m). "+
           "Kierunek telefonu podczas zwykłego spaceru nie jest pomiarem nadajnika.",
           14,false));

        root.addView(text("Wybierz nadajnik (identyfikator z telegramu)",16,true));
        meters=new Spinner(this);
        root.addView(meters);
        selectionText=text("Oczekiwanie na odbiór liczników...",14,false);
        root.addView(selectionText);

        root.addView(text("Azymut anteny — północ geograficzna",17,true));
        headingText=text("Kompas niegotowy",23,true);
        root.addView(headingText);

        root.addView(text("Korekta montażu telefonu względem osi anteny",15,true));
        offsetBar=new SeekBar(this);
        offsetBar.setMax(360);
        int saved=getPreferences(MODE_PRIVATE).getInt("heading_offset",0);
        offsetBar.setProgress(180+Math.max(-180,Math.min(180,saved)));
        root.addView(offsetBar);
        offsetText=text("Offset: "+saved+"°",14,false);
        root.addView(offsetText);
        offsetBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean byUser) {
                int off=progress-180;
                offsetText.setText("Offset: "+off+"° (0° = antena zgodna z górą telefonu)");
                if(byUser)getPreferences(MODE_PRIVATE).edit()
                    .putInt("heading_offset",off).apply();
                refreshInfo();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        siteText=text("GPS: oczekiwanie...",14,false);
        root.addView(siteText);
        helpText=text("Zapisywane azymuty trafiają do trwałej bazy z poprzednimi sesjami.",13,false);
        root.addView(helpText);
        Button save=new Button(this);
        save.setText("ZAPISZ AZYMUT — maksimum RSSI");
        save.setOnClickListener(v->saveBearing());
        root.addView(save);
        Button map=new Button(this);
        map.setText("Zobacz wyniki na mapie");
        map.setOnClickListener(v->startActivity(
            new android.content.Intent(this,MapActivity.class)));
        root.addView(map);
        Button back=new Button(this);
        back.setText("Wróć do odbioru");
        back.setOnClickListener(v->finish());
        root.addView(back);

        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        rotation=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rotation==null) {
            helpText.setText("Ten telefon nie ma sensora ROTATION_VECTOR: "+
                "bez wiarygodnego azymutu nie zapisujemy linii kierunkowej.");
        }
    }
    private TextView text(String value,int sp,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);t.setTextSize(sp);
        if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);
        t.setPadding(0,8,0,8);
        return t;
    }
    private JSONObject selectedMeter() {
        int i=meters==null? -1:meters.getSelectedItemPosition();
        if(i<0 || i>=meterKeys.size())return null;
        return SurveyStore.readSnapshot(this).optJSONObject(meterKeys.get(i));
    }
    private String selectedKey() {
        int i=meters==null? -1:meters.getSelectedItemPosition();
        return i<0 || i>=meterKeys.size()?null:meterKeys.get(i);
    }
    private void refreshMeters() {
        JSONObject data=SurveyStore.readSnapshot(this);
        JSONArray names=data.names();
        List<String> keys=new ArrayList<>();
        if(names!=null)for(int i=0;i<names.length();i++){
            String key=names.optString(i);
            if(data.optJSONObject(key)!=null)keys.add(key);
        }
        keys.sort((a,b)->Long.compare(
            data.optJSONObject(b).optLong("last_seen"),
            data.optJSONObject(a).optLong("last_seen")));
        String encoded=String.join("|",keys);
        if(!encoded.equals(lastMeterList)) {
            String selected=selectedKey();
            meterKeys.clear();
            meterKeys.addAll(keys);
            List<String> descriptions=new ArrayList<>();
            for(String key:keys){
                JSONObject m=data.optJSONObject(key);
                descriptions.add(m.optString("manufacturer")+" "+
                    m.optString("id")+" · "+
                    m.optInt("last_rssi_dbm",-127)+" dBm");
            }
            meters.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,descriptions));
            if(selected!=null && keys.contains(selected))
                meters.setSelection(keys.indexOf(selected));
            lastMeterList=encoded;
        }
    }
    private Location currentFix() {
        Location p=NanoCulService.latestGpsFix;
        if(p!=null && SystemClock.elapsedRealtimeNanos()-
                      p.getElapsedRealtimeNanos()<MAX_GPS_AGE_NS)
            return p;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
             PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager=(LocationManager)getSystemService(LOCATION_SERVICE);
        try {
            Location result=manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(result!=null && SystemClock.elapsedRealtimeNanos()-
               result.getElapsedRealtimeNanos()<MAX_GPS_AGE_NS)return result;
        }catch(Exception ignored){}
        return null;
    }
    private double trueHeading(Location position) {
        if(position==null||!Double.isFinite(magneticHeading))return Double.NaN;
        GeomagneticField earth=new GeomagneticField(
                (float)position.getLatitude(),(float)position.getLongitude(),
                (float)(position.hasAltitude()?position.getAltitude():0),
                position.getTime());
        int offset=offsetBar.getProgress()-180;
        return (magneticHeading+earth.getDeclination()+offset+720)%360;
    }
    private void refreshInfo() {
        JSONObject meter=selectedMeter();
        if(meter!=null) {
            long age=System.currentTimeMillis()-meter.optLong("last_seen",0);
            String ageText=age<0?"?":age/1000+" s";
            selectionText.setText("RSSI ostatniego telegramu: "+
                (meter.isNull("last_rssi_dbm")?"brak":meter.optInt("last_rssi_dbm")+" dBm")+
                " · odebrano "+ageText+" temu · zapisane azymuty: "+
                (meter.optJSONArray("bearings")==null?0:
                 meter.optJSONArray("bearings").length()));
        } else selectionText.setText("Wybierz licznik po rozpoczęciu odbioru USB.");
        phonePosition=currentFix();
        if(phonePosition==null)siteText.setText(
            "GPS: brak aktualnego fixu (<20 s). Wyjdź na zewnątrz.");
        else siteText.setText(String.format(Locale.ROOT,
            "GPS telefonu: %.6f %.6f ±%.0f m",
            phonePosition.getLatitude(),phonePosition.getLongitude(),
            phonePosition.hasAccuracy()?phonePosition.getAccuracy():999));
        double bearing=trueHeading(phonePosition);
        headingText.setText(Double.isFinite(bearing)?
            String.format(Locale.ROOT,"%.1f° (rzeczywisty) · błąd ~%.0f°",
                          bearing,headingAccuracy):
            "Azymut niedostępny — sprawdź kompas i GPS");
    }
    private void saveBearing() {
        String key=selectedKey();
        JSONObject meter=selectedMeter();
        Location pos=currentFix();
        double bearing=trueHeading(pos);
        if(key==null || meter==null) {show("Wybierz nadajnik.");return;}
        long age=System.currentTimeMillis()-meter.optLong("last_seen",0);
        if(age<0 || age>45000){
            show("Brak świeżego telegramu tego nadajnika (<=45 s).");return;
        }
        int rssi=meter.optInt("last_rssi_dbm",-127);
        if(rssi<-120 || rssi>-20){
            show("Brak RSSI z X21. Najpierw potwierdź maksimum sygnału.");return;
        }
        if(pos==null || !pos.hasAccuracy() || pos.getAccuracy()>35) {
            show("Potrzebny aktualny i dokładny GPS (<=35 m).");return;
        }
        if(rotation==null || sensorAccuracy==SensorManager.SENSOR_STATUS_UNRELIABLE ||
           !Double.isFinite(bearing)||headingAccuracy>30){
            show("Kompas niewiarygodny — kalibruj telefon z dala od metalu.");return;
        }
        try {
            SurveyStore store=NanoCulService.activeSurvey;
            if(store==null)store=new SurveyStore(this);
            boolean ok=store.addBearing(
                key,pos.getLatitude(),pos.getLongitude(),
                pos.getAccuracy(),bearing,headingAccuracy,System.currentTimeMillis());
            if(ok) {
                show(String.format(Locale.ROOT,
                    "Zapisano kierunek %.1f° z GPS; przejdź do kolejnego stanowiska.",
                    bearing));
                refreshInfo();
            } else show("Nie zapisano azymutu. Sprawdź GPS i wybrany licznik.");
        }catch(Exception e){show("Błąd zapisu azymutu: "+e.getMessage());}
    }
    private void show(String message){
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
    @Override public void onSensorChanged(SensorEvent event) {
        if(event.sensor.getType()!=Sensor.TYPE_ROTATION_VECTOR)return;
        float[] matrix=new float[9],angles=new float[3];
        SensorManager.getRotationMatrixFromVector(matrix,event.values);
        SensorManager.getOrientation(matrix,angles);
        magneticHeading=(Math.toDegrees(angles[0])+360)%360;
        sensorAccuracy=event.accuracy;
        if(event.values.length>=5 && event.values[4]>0 &&
           Float.isFinite(event.values[4])) {
            headingAccuracy=Math.min(45,Math.max(3,
                   Math.toDegrees(event.values[4])));
        } else {
            headingAccuracy=sensorAccuracy>=SensorManager.SENSOR_STATUS_ACCURACY_HIGH?
                  8:sensorAccuracy>=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM?
                  15:sensorAccuracy>=SensorManager.SENSOR_STATUS_ACCURACY_LOW?25:45;
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){
        sensorAccuracy=accuracy;
    }
    @Override protected void onResume() {
        super.onResume();
        if(rotation!=null)sensors.registerListener(this,rotation,
                        SensorManager.SENSOR_DELAY_UI);
        handler.post(uiRefresh);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(uiRefresh);
        sensors.unregisterListener(this);
        super.onPause();
    }
}

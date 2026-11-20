package pl.wmbuster.nanocullogger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows BOTH (1) measured phone GPS/RSSI reception sites and
 * (2) hypothetical transmitter estimates. They are visibly distinct.
 * A GPS reception marker is NEVER described as a transmitter position.
 */
public final class MapActivity extends Activity {
    private static final int MAX_SITE_MARKERS=90;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MapView map;
    private TextView summary;
    private String lastSnapshot="";
    private boolean firstCenter=true;
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            redraw();
            handler.postDelayed(this,2500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Configuration.getInstance().setUserAgentValue(getPackageName()+"/0.3.1");
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        setContentView(root);

        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button back=new Button(this);back.setText("‹ Wróć");
        back.setOnClickListener(v->finish());
        bar.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        Button refreshBtn=new Button(this);refreshBtn.setText("Odśwież");
        refreshBtn.setOnClickListener(v->{lastSnapshot="";redraw();});
        bar.addView(refreshBtn,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(bar);
        summary=new TextView(this);
        summary.setTextSize(13);
        summary.setPadding(14,8,14,8);
        summary.setText("Wczytywanie punktów pomiarowych i szacunków RSSI...");
        root.addView(summary);

        map=new MapView(this);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(16.0);
        root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        TextView foot=new TextView(this);
        foot.setTextSize(12);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(10,8,10,8);
        foot.setText("Pomarańczowy = miejsce odbioru przez TELEFON. " +
          "Niebieski + okrąg = najlepszy szacunek nadajnika. " +
          "Zielony = ręczny kierunek z anteny kierunkowej. " +
          "Kafelki OSM wymagają internetu.");
        root.addView(foot);
        redraw();
    }

    private static double distanceMeters(GeoPoint a,GeoPoint b) {
        double n=(a.getLatitude()-b.getLatitude())*111195;
        double e=(a.getLongitude()-b.getLongitude())*111195*
                 Math.cos(Math.toRadians((a.getLatitude()+b.getLatitude())/2));
        return Math.hypot(n,e);
    }

    private GeoPoint currentPhoneLocation() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
              PackageManager.PERMISSION_GRANTED &&
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=
              PackageManager.PERMISSION_GRANTED) return null;
        try {
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            Location best=null;
            for(String provider:new String[]{
                LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}) {
                Location l=lm.getLastKnownLocation(provider);
                if(l!=null && l.hasAccuracy() && l.getTime()>0 &&
                   System.currentTimeMillis()-l.getTime()<120000 &&
                   (best==null || l.getAccuracy()<best.getAccuracy())) best=l;
            }
            return best==null?null:new GeoPoint(best.getLatitude(),best.getLongitude());
        }catch(Exception ignored) {return null;}
    }

    private void addPhoneSpot(GeoPoint pt,String manufacturer,String meter,
                              int rssi,double accuracy) {
        Polygon dot=new Polygon();
        dot.setPoints(Polygon.pointsAsCircle(pt,9));
        // Orange = actual recording location, never transmitter.
        dot.getFillPaint().setColor(Color.argb(85,239,137,42));
        dot.getOutlinePaint().setColor(Color.rgb(190,95,21));
        dot.getOutlinePaint().setStrokeWidth(2);
        map.getOverlays().add(dot);

        Marker marker=new Marker(map);
        marker.setPosition(pt);
        marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);
        marker.setTitle("POMIAR GPS: "+manufacturer+" "+meter);
        marker.setSnippet("Miejsce odbioru przez telefon, NIE pozycja nadajnika. "+
            (rssi<=-121?"Brak RSSI":"RSSI "+rssi+" dBm")+
            "; GPS ±"+Math.round(accuracy)+" m");
        map.getOverlays().add(marker);
    }

    private void addEstimate(GeoPoint pt,JSONObject meter,JSONObject candidate) {
        double uncertainty=candidate.optDouble("uncertainty_m",150);
        if(!Double.isFinite(uncertainty))uncertainty=150;
        Polygon circle=new Polygon();
        circle.setPoints(Polygon.pointsAsCircle(pt,uncertainty));
        circle.getFillPaint().setColor(Color.argb(25,25,110,190));
        circle.getOutlinePaint().setColor(Color.argb(150,25,110,190));
        circle.getOutlinePaint().setStrokeWidth(3);
        map.getOverlays().add(circle);
        Marker marker=new Marker(map);
        marker.setPosition(pt);
        marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);
        marker.setTitle("SZACUNEK: "+meter.optString("manufacturer")+" "+meter.optString("id"));
        marker.setSnippet("Szacunek "+candidate.optString("method","RSSI")+
            ", nie adres nadajnika. Orientacyjna niepewność ±~"+
            Math.round(uncertainty)+" m · ocena modelu "+
            Math.round(candidate.optDouble("quality_score",0))+"/100 (nie procent) · "+
            candidate.optInt("distinct_sites")+" stanowisk");
        map.getOverlays().add(marker);
    }

    private void addBearingRay(JSONObject bearing) {
        double lat=bearing.optDouble("lat",Double.NaN);
        double lon=bearing.optDouble("lon",Double.NaN);
        double az=bearing.optDouble("azimuth_deg",Double.NaN);
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(az))return;
        double radians=Math.toRadians(az);
        double distance=650; // Drawing extent only, not a measured range.
        double lat2=lat+Math.cos(radians)*distance/111195.0;
        double c=Math.cos(Math.toRadians(lat));
        if(Math.abs(c)<0.01)return;
        double lon2=lon+Math.sin(radians)*distance/(111195.0*c);
        Polyline line=new Polyline();
        java.util.List<GeoPoint> pts=new java.util.ArrayList<>();
        pts.add(new GeoPoint(lat,lon));
        pts.add(new GeoPoint(lat2,lon2));
        line.setPoints(pts);
        line.setColor(Color.rgb(25,135,75));
        line.setWidth(4f);
        line.setTitle("Ręczny namiar na nadajnik: "+
            Math.round(az)+"° (linia bez pomiaru odległości)");
        map.getOverlays().add(line);
    }

    private void redraw() {
        if(map==null)return;
        JSONObject data=SurveyStore.readSnapshot(this);
        String json=data.toString();
        if(lastSnapshot.equals(json))return;
        lastSnapshot=json;
        map.getOverlays().clear();
        int meters=data.length(),estimated=0,totalSamples=0,drawn=0;
        int bearingsTotal=0;
        int metersWithGps=0,withRssi=0;
        GeoPoint first=null;
        List<JSONObject> meterRows=new ArrayList<>();
        JSONArray names=data.names();
        if(names!=null)for(int i=0;i<names.length();i++) {
            JSONObject meter=data.optJSONObject(names.optString(i));
            if(meter!=null)meterRows.add(meter);
        }
        // Show meters most recently heard first.
        meterRows.sort((a,b)->Long.compare(b.optLong("last_seen"),a.optLong("last_seen")));
        StringBuilder details=new StringBuilder();
        int shown=0;
        for(JSONObject meter:meterRows) {
            String id=meter.optString("manufacturer")+" "+meter.optString("id");
            JSONArray samples=meter.optJSONArray("samples");
            int sampleCount=samples==null?0:samples.length();
            int meterRssi=0;
            totalSamples+=sampleCount;
            if(sampleCount>0)metersWithGps++;

            List<GeoPoint> displayedSites=new ArrayList<>();
            if(samples!=null)for(int i=samples.length()-1;i>=0;i--) {
                JSONObject p=samples.optJSONObject(i);
                if(p==null)continue;
                if(p.has("rssi") && !p.isNull("rssi") &&
                   p.optInt("rssi",-127)>-121) meterRssi++;
                double lat=p.optDouble("lat",Double.NaN),
                       lon=p.optDouble("lon",Double.NaN);
                if(!Double.isFinite(lat)||!Double.isFinite(lon))continue;
                GeoPoint gp=new GeoPoint(lat,lon);
                if(first==null)first=gp;
                boolean nearby=false;
                for(GeoPoint other:displayedSites) {
                    if(distanceMeters(gp,other)<15) {nearby=true;break;}
                }
                if(nearby)continue;
                displayedSites.add(gp);
                if(drawn<MAX_SITE_MARKERS) {
                    addPhoneSpot(gp,meter.optString("manufacturer"),meter.optString("id"),
                                 p.optInt("rssi",-127),p.optDouble("accuracy_m",0));
                    drawn++;
                }
                // Bound processing for large surveys; each meter already keeps <=160.
            }

            withRssi+=meterRssi;
            JSONArray bearings=meter.optJSONArray("bearings");
            int meterBearings=bearings==null?0:bearings.length();
            bearingsTotal+=meterBearings;
            if(bearings!=null)for(int i=0;i<bearings.length();i++) {
                JSONObject bearing=bearings.optJSONObject(i);
                if(bearing!=null) {
                    addBearingRay(bearing);
                    if(first==null)first=new GeoPoint(
                        bearing.optDouble("lat"),bearing.optDouble("lon"));
                }
            }
            // Favor the persistent best estimate across all walks, not
            // necessarily the latest model result with poorer evidence.
            JSONObject candidate=meter.optJSONObject("best_estimate");
            if(candidate==null)candidate=meter.optJSONObject("estimate");
            if(candidate!=null) {
                double lat=candidate.optDouble("lat",Double.NaN),
                       lon=candidate.optDouble("lon",Double.NaN);
                if(Double.isFinite(lat) && Double.isFinite(lon)) {
                    GeoPoint gp=new GeoPoint(lat,lon);
                    if(first==null)first=gp;
                    addEstimate(gp,meter,candidate);
                    estimated++;
                }
            }
            if(shown++<5) {
                details.append("\n").append(id)
                       .append(" · ").append(meter.optInt("received_count",0))
                       .append(" ram. · ").append(sampleCount).append(" GPS")
                       .append(" (").append(meterRssi).append(" RSSI)")
                       .append(" · ").append(meterBearings).append(" azymutów");
                if(candidate==null) {
                    if(sampleCount==0)
                        details.append(" · ").append(meter.optString("last_gps_status",
                                                    "brak pomiarów GPS"));
                    else if(displayedSites.size()<4)
                        details.append(" · potrzeba 4 różnych miejsc (>=15 m)");
                    else
                        details.append(" · potrzeba >=60 m i trasy z kilku stron");
                } else details.append(" · SZACUNEK");
            }
        }
        if(first==null) {
            GeoPoint phone=currentPhoneLocation();
            if(phone!=null) {
                first=phone;
                Marker phoneMarker=new Marker(map);
                phoneMarker.setPosition(phone);
                phoneMarker.setTitle("Telefon · ostatnia pozycja GPS");
                phoneMarker.setSnippet("To nie jest lokalizacja licznika.");
                map.getOverlays().add(phoneMarker);
            }
        }
        StringBuilder top=new StringBuilder()
            .append("Liczniki: ").append(meters)
            .append(" · GPS: ").append(totalSamples)
            .append(" · RSSI: ").append(withRssi)
            .append(" · azymuty: ").append(bearingsTotal)
            .append(" · punkty na mapie: ").append(drawn)
            .append(" · szacunki nadajników: ").append(estimated);
        if(meters==0)
            top.append("\nBrak zapisanej historii pomiarów. Uruchom odbiór i sprawdź GPS telefonu.");
        else if(totalSamples==0)
            top.append("\nŻaden z odebranych telegramów nie miał zaakceptowanego "+
                "GPS (świeży fix, dokładność <=35 m) i RSSI.");
        else if(withRssi==0)
            top.append("\nZebrano GPS, ale brak RSSI: sprawdź czy culfw wysyła "+
                "metadane przez X21. Nadajników bez RSSI nie lokalizujemy.");
        else if(estimated==0)
            top.append("\nBrak jeszcze szacunku: RSSI wymaga >=4 różne miejsca "+
                "i >=60 m zróżnicowanej trasy; namiary kierunkowe >=3 miejsc. "+
                "Jedna ulica lub zakłócony kompas to za mało.");
        top.append(details);
        if(!NanoCulService.gpsStatus.isEmpty())
            top.append("\nGPS telefonu: ").append(NanoCulService.gpsStatus);
        summary.setText(top.toString());
        if(firstCenter && first!=null) {
            map.getController().setCenter(first);
            map.getController().setZoom(16.0);
            firstCenter=false;
        }
        map.invalidate();
    }

    @Override protected void onResume() {
        super.onResume();
        if(map!=null)map.onResume();
        handler.post(refresh);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if(map!=null)map.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        if(map!=null)map.onDetach();
        super.onDestroy();
    }
}

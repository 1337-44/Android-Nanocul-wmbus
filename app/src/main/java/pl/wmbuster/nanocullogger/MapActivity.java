package pl.wmbuster.nanocullogger;

import android.app.Activity;
import android.graphics.Color;
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

import java.util.ArrayList;
import java.util.List;

/** Map of experimentally estimated transmitter positions, not exact meters.
 * Online OSM tiles require internet and expose requested tile regions to OSM.
 * Survey positions are stored locally; nothing is uploaded by this class. */
public final class MapActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MapView map;
    private TextView summary;
    private SurveyStore store;
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
        Configuration.getInstance().setUserAgentValue(getPackageName()+"/0.2.0");
        store=new SurveyStore(this);

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
        summary.setText("Mapa szacunkowa. Potrzeba min. 4 różnych pozycji GPS, odległości >= 60 m i odpowiedniej geometrii.");
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
        foot.setText("Okrąg = orientacyjna niepewność, nie granica dokładności. " +
          "Ściany i odbicia mogą silnie zniekształcić RSSI. Podkład OSM wymaga internetu.");
        root.addView(foot);
        redraw();
    }

    private void redraw() {
        if(map==null)return;
        JSONObject data=store.snapshot();
        String json=data.toString();
        if(lastSnapshot.equals(json))return;
        lastSnapshot=json;
        map.getOverlays().clear();
        int all=data.length(),known=0;
        GeoPoint first=null;
        List<GeoPoint> points=new ArrayList<>();

        JSONArray names=data.names();
        if(names!=null)for(int i=0;i<names.length();i++) {
            JSONObject meter=data.optJSONObject(names.optString(i));
            if(meter==null)continue;
            JSONObject candidate=meter.optJSONObject("estimate");
            if(candidate!=null) {
                double lat=candidate.optDouble("lat",Double.NaN),
                       lon=candidate.optDouble("lon",Double.NaN);
                if(!Double.isFinite(lat)||!Double.isFinite(lon))continue;
                GeoPoint gp=new GeoPoint(lat,lon);
                if(first==null)first=gp;
                points.add(gp);
                double uncertainty=candidate.optDouble("uncertainty_m",150);
                if(!Double.isFinite(uncertainty))uncertainty=150;
                Polygon circle=new Polygon();
                circle.setPoints(Polygon.pointsAsCircle(gp,uncertainty));
                circle.getFillPaint().setColor(Color.argb(22,25,110,190));
                circle.getOutlinePaint().setColor(Color.argb(140,25,110,190));
                circle.getOutlinePaint().setStrokeWidth(2);
                map.getOverlays().add(circle);
                Marker marker=new Marker(map);
                marker.setPosition(gp);
                marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM);
                marker.setTitle(meter.optString("manufacturer")+" "+
                    meter.optString("id"));
                marker.setSnippet("SZACUNEK RSSI, nie adres • ±~"+
                    (int)Math.round(uncertainty)+" m • "+
                    candidate.optInt("distinct_sites")+" lokalizacji GPS • "+
                    candidate.optInt("observations")+" pomiarów");
                map.getOverlays().add(marker);
                known++;
            } else if(first==null) {
                // Until model is solvable, center on a recorded phone position
                // but DO NOT show a falsely inferred transmitter pin.
                JSONArray samples=meter.optJSONArray("samples");
                if(samples!=null && samples.length()>0) {
                    JSONObject s=samples.optJSONObject(samples.length()-1);
                    if(s!=null)first=new GeoPoint(s.optDouble("lat"),s.optDouble("lon"));
                }
            }
        }
        summary.setText("Nadajniki: "+known+" szacunków / "+all+
           " wykrytych liczników. Każdy poprawny telegram z GPS i RSSI aktualizuje model.");
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

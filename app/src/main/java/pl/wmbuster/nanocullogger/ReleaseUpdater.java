package pl.wmbuster.nanocullogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Checks the developer's official GitHub Releases API and downloads an APK.
 * The installation dialog is ALWAYS controlled by Android and the user.
 *
 * Android will reject updates that are not signed with the SAME certificate
 * as the installed app. The historic CI v0.1.0 debug key was ephemeral.
 * Configure the repository's PRIVATE signing secrets for true in-place
 * updates; the updater cannot and must not bypass Android signature checks.
 */
public final class ReleaseUpdater {
    private static final String API =
        "https://api.github.com/repos/1337-44/Android-Nanocul-wmbus/releases?per_page=15";
    private static final String ASSET_PREFIX =
        "https://github.com/1337-44/Android-Nanocul-wmbus/releases/download/";
    private static final String APK_MIME="application/vnd.android.package-archive";

    private static void toast(Activity a,String message) {
        a.runOnUiThread(()->Toast.makeText(a,message,Toast.LENGTH_LONG).show());
    }
    private static String version(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionName;
        } catch(Exception e) {return "0.0.0";}
    }
    private static int[] parseVersion(String value) {
        if(value.startsWith("v") || value.startsWith("V"))
            value=value.substring(1);
        String[] parts=value.split("\\.");
        int[] nums=new int[3];
        for(int i=0;i<nums.length;i++) {
            if(i>=parts.length)break;
            nums[i]=Integer.parseInt(parts[i].replaceAll("[^0-9].*$",""));
        }
        return nums;
    }
    private static int compare(String a,String b) {
        int[] x=parseVersion(a),y=parseVersion(b);
        for(int i=0;i<3;i++)if(x[i]!=y[i])return Integer.compare(x[i],y[i]);
        return 0;
    }
    private static String readApi(String uri) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(uri).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept","application/vnd.github+json");
        conn.setRequestProperty("User-Agent","Android-Nanocul-wmbus/0.2");
        try {
            if(conn.getResponseCode()!=200)
                throw new Exception("GitHub API HTTP "+conn.getResponseCode());
            try(InputStream in=conn.getInputStream();
                ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){
                    if(out.size()+n>700000)throw new Exception("GitHub response too large");
                    out.write(buf,0,n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }
    public static void check(Activity activity) {
        toast(activity,"Sprawdzanie GitHub Releases...");
        new Thread(()->{
            try {
                JSONArray list=new JSONArray(readApi(API));
                JSONObject best=null,asset=null;
                String installed=version(activity);
                for(int i=0;i<list.length();i++) {
                    JSONObject r=list.optJSONObject(i);
                    if(r==null || r.optBoolean("draft",true))continue;
                    String tag=r.optString("tag_name");
                    try {if(compare(tag,installed)<=0)continue;}
                    catch(Exception ignored){continue;}
                    JSONArray assets=r.optJSONArray("assets");
                    if(assets==null)continue;
                    JSONObject apk=null;
                    for(int a=0;a<assets.length();a++) {
                        JSONObject candidate=assets.optJSONObject(a);
                        if(candidate==null)continue;
                        String url=candidate.optString("browser_download_url");
                        if(url.startsWith(ASSET_PREFIX) &&
                           url.endsWith(".apk") &&
                           url.contains("/"+tag+"/")) {
                            apk=candidate;break;
                        }
                    }
                    if(apk==null)continue;
                    try {
                        if(best==null ||
                           compare(tag,best.optString("tag_name"))>0){
                            best=r;asset=apk;
                        }
                    }catch(Exception ignored){}
                }
                if(best==null) {
                    activity.runOnUiThread(()->new AlertDialog.Builder(activity)
                        .setTitle("Brak nowszej wersji")
                        .setMessage("Zainstalowana: "+installed+
                            "\nNie znaleziono nowszego APK w GitHub Releases.")
                        .setPositiveButton("OK",null).show());
                    return;
                }
                String tag=best.getString("tag_name");
                String apkUrl=asset.getString("browser_download_url");
                String digest=asset.optString("digest","");
                activity.runOnUiThread(()->new AlertDialog.Builder(activity)
                    .setTitle("Nowa wersja "+tag)
                    .setMessage("Masz: "+installed+"\nNajnowsza: "+tag+
                       "\n\nAktualizacja wymaga potwierdzenia instalacji przez Androida "+
                       "i tego samego podpisu APK. Wersje DEBUG ze starszych "+
                       "kompilacji mogą mieć różne podpisy. "+
                       "Przed ewentualną reinstalacją wyeksportuj logi.")
                    .setNegativeButton("Anuluj",null)
                    .setNeutralButton("Zobacz wydanie",(d,w)->{
                        activity.startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/1337-44/Android-Nanocul-wmbus/releases/tag/"+tag)));
                    })
                    .setPositiveButton("Pobierz APK",(d,w)->
                        download(activity,tag,apkUrl,digest)).show());
            } catch(Exception e) {
                toast(activity,"Aktualizator: "+e.getMessage());
            }
        },"github-release-check").start();
    }

    private static void download(Activity activity,String tag,String uri,String digest) {
        if(!uri.startsWith(ASSET_PREFIX) || !uri.endsWith(".apk")) {
            toast(activity,"Nieoczekiwany adres APK — przerwano");return;
        }
        if(Build.VERSION.SDK_INT>=26 &&
           !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                .setTitle("Zezwól na instalowanie aktualizacji")
                .setMessage("W ustawieniach Androida zezwól tej aplikacji na "+
                    "instalowanie z tego źródła. Następnie ponów pobieranie.")
                .setNegativeButton("Anuluj",null)
                .setPositiveButton("Otwórz ustawienia",(d,w)->{
                    Intent intent=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:"+activity.getPackageName()));
                    activity.startActivity(intent);
                }).show();
            return;
        }
        DownloadManager manager=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(uri));
        req.setTitle("NanoCUL GPS Logger "+tag);
        req.setDescription("Aktualizacja z GitHub Releases");
        req.setMimeType(APK_MIME);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "NanoCUL_GPS_Logger_"+tag+".apk");
        final long id;
        try {id=manager.enqueue(req);}
        catch(Exception e){toast(activity,"Błąd pobierania: "+e.getMessage());return;}
        toast(activity,"Pobieranie APK uruchomione...");
        BroadcastReceiver receiver=new BroadcastReceiver() {
            @Override public void onReceive(Context context,Intent intent) {
                if(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1L)!=id)return;
                try {activity.unregisterReceiver(this);}catch(Exception ignored){}
                new Thread(()->{
                    DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
                    try(Cursor c=manager.query(q)) {
                        if(c==null || !c.moveToFirst() ||
                           c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=
                             DownloadManager.STATUS_SUCCESSFUL){
                            toast(activity,"Nie udało się pobrać APK");return;
                        }
                    } catch(Exception e){toast(activity,e.getMessage());return;}
                    Uri downloaded=manager.getUriForDownloadedFile(id);
                    if(downloaded==null){toast(activity,"Brak pliku APK");return;}
                    // Verify the SHA-256 returned in GitHub's asset metadata
                    // where supported. Browser HTTPS and platform signature
                    // validation still apply.
                    if(digest.startsWith("sha256:")) {
                        try(InputStream input=activity.getContentResolver().openInputStream(downloaded)){
                            MessageDigest hasher=MessageDigest.getInstance("SHA-256");
                            byte[] buffer=new byte[32768];int n;
                            while((n=input.read(buffer))>0)hasher.update(buffer,0,n);
                            byte[] sum=hasher.digest();
                            StringBuilder hex=new StringBuilder();
                            for(byte b:sum)hex.append(String.format(Locale.ROOT,"%02x",b&255));
                            if(!digest.substring(7).equalsIgnoreCase(hex.toString())) {
                                toast(activity,"Błąd sumy SHA-256: instalacja anulowana");
                                return;
                            }
                        } catch(Exception e){
                            toast(activity,"Nie można zweryfikować APK: "+e.getMessage());
                            return;
                        }
                    }
                    activity.runOnUiThread(()->{
                        try {
                            Intent install=new Intent(Intent.ACTION_VIEW);
                            install.setDataAndType(downloaded,APK_MIME);
                            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(install);
                        } catch(Exception e){
                            toast(activity,"Pobrano do Downloads; otwórz APK ręcznie");
                        }
                    });
                },"github-apk-verify").start();
            }
        };
        IntentFilter filter=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if(Build.VERSION.SDK_INT>=33)
            activity.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(receiver,filter);
    }
}

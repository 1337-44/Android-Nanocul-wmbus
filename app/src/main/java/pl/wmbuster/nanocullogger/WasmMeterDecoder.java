package pl.wmbuster.nanocullogger;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Runs a pinned GPL-3.0-or-later wmbusmeters WASM build inside an isolated,
 * headless, local-asset-only WebView. USB and GPS remain Android native.
 *
 * The full driver engine is a separate asynchronous decoding layer:
 * - basic OMS + RAW JSONL logging always continues;
 * - this engine adds a correlated decoder_update JSONL record;
 * - an unsupported meter or missing AES key isn't misreported as decoded.
 */
public final class WasmMeterDecoder {
    public interface Listener {
        void onReady(String version);
        void onError(String error);
        void onResult(long telegramId,String json);
    }
    private static final String ORIGIN="appassets.androidplatform.net";
    private static final String HOME="https://"+ORIGIN+"/assets/wmbus/index.html";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Context context;
    private final Listener listener;
    private volatile boolean ready;
    private volatile boolean closed;
    private WebView view;

    public WasmMeterDecoder(Context appContext,Listener listener) {
        this.context=appContext.getApplicationContext();
        this.listener=listener;
        main.post(this::initialize);
    }
    private void initialize() {
        if(closed)return;
        try {
            WebViewAssetLoader loader=new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                    new WebViewAssetLoader.AssetsPathHandler(context))
                .build();
            view=new WebView(context);
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setAllowFileAccess(false);
            view.getSettings().setAllowContentAccess(false);
            view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
            view.getSettings().setDomStorageEnabled(false);
            view.addJavascriptInterface(new Callback(),"AndroidWmbus");
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(
                    WebView web,WebResourceRequest request) {
                    Uri uri=request.getUrl();
                    if(!"https".equalsIgnoreCase(uri.getScheme()) ||
                       !ORIGIN.equalsIgnoreCase(uri.getHost()) ||
                       uri.getPath()==null ||
                       !uri.getPath().startsWith("/assets/wmbus/")) {
                        return new WebResourceResponse("text/plain","UTF-8",403,
                            "Forbidden",java.util.Collections.emptyMap(),
                            new ByteArrayInputStream(
                               "External request blocked".getBytes(StandardCharsets.UTF_8)));
                    }
                    WebResourceResponse response=loader.shouldInterceptRequest(uri);
                    if(response!=null)return response;
                    return new WebResourceResponse("text/plain","UTF-8",404,
                        "Not found",java.util.Collections.emptyMap(),
                        new ByteArrayInputStream(new byte[0]));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest r) {
                    return !HOME.equals(r.getUrl().toString());
                }
            });
            view.loadUrl(HOME);
        }catch(Exception e){listener.onError("WebView: "+e.getMessage());}
    }
    private final class Callback {
        @JavascriptInterface public void onReady(String version) {
            if(closed)return;
            ready=true;
            listener.onReady(version);
        }
        @JavascriptInterface public void onError(String detail) {
            if(!closed)listener.onError(detail);
        }
        @JavascriptInterface public void onResult(String id,String result) {
            if(closed)return;
            try {listener.onResult(Long.parseLong(id),result);}
            catch(Exception e){listener.onError("Invalid decode callback");}
        }
    }
    public boolean isReady() {return ready && !closed;}
    /** Validated ASCII hex is JSON-quoted, never interpolated as executable JS. */
    public boolean submit(long id,String rawHex) {
        if(!isReady() || rawHex==null || rawHex.length()>600 ||
           rawHex.length()<24 || (rawHex.length()&1)!=0 ||
           !rawHex.matches("[a-fA-F0-9]+"))return false;
        main.post(()->{
            if(!closed && view!=null)
                view.evaluateJavascript("window.wmbusDecode("+id+","+
                    JSONObject.quote(rawHex)+")",null);
        });
        return true;
    }
    public void close() {
        closed=true;
        ready=false;
        main.post(()->{
            if(view!=null) {
                view.stopLoading();
                view.removeJavascriptInterface("AndroidWmbus");
                view.destroy();
                view=null;
            }
        });
    }
}

package io.nekohasekai.sagernet;

import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpService extends NanoHTTPD {
    public interface HttpServerCallback {
        String OnHttp(String url, JSONObject body) throws Throwable;
    }

    final static String SuccessResp = "{}";

    Map<String, HttpServerCallback> callback = new HashMap<>();

    public HttpService(String ip, int port) {
        super(ip, port);
    }

    private JSONObject parseBody(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<String, String>();
            JSONObject json = null;
            session.parseBody(files);
            String body = files.get("postData");
            Log.i("VpnHttpApi", "recv body :" + body);
            if (body == null) {
                return null;
            }
            return new JSONObject(body);
        } catch (Throwable e) {
            Log.e("VpnHttpApi", "parseBody error!", e);
            e.printStackTrace();
            return null;
        }
    }

    public void registerHandler(String url, HttpServerCallback callback) {
        this.callback.put(url, callback);
    }

    public NanoHTTPD.Response serve(NanoHTTPD.IHTTPSession session) {
        String url = session.getUri();
        if (url.endsWith("/") && url.length() > 1) {
            url = url.substring(0, url.length() - 1);
        }
        HttpServerCallback handler = callback.get(url);
        if (handler == null) {
            return newFixedLengthResponse("500");
        }
        String resp;
        try {
            resp = handler.OnHttp(session.getUri(), parseBody(session));
            if (resp == null) {
                resp = SuccessResp;
            }
        } catch (Throwable e) {
            resp = "{\"error\":\"" + e.getMessage() + "\"}";
            Log.e("VpnHttpApi", "OnHttp error!", e);
            e.printStackTrace();
        }
        return NanoHTTPD.newFixedLengthResponse(resp);
    }
}
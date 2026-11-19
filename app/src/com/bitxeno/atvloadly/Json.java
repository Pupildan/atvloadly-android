package com.bitxeno.atvloadly;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Thin helpers over org.json for the backend's uniform response envelope.
 *
 * Every backend endpoint answers with an ApiResult shaped
 * {@code {"code":200,"msg":"success","data":...}} on success and
 * {@code {"code":-1,"msg":"...","data":null}} on failure. These helpers parse
 * that envelope once so callers deal with a typed Result instead of raw JSON.
 */
public final class Json {

    private static final String TAG = "atvloadly.Json";

    /** Outcome of one API call: ok mirrors the backend envelope's code field. */
    public static class Result {
        public final boolean ok;
        public final String error;
        public final JSONObject body;
        public final String raw;

        Result(boolean ok, String error, JSONObject body, String raw) {
            this.ok = ok;
            this.error = error;
            this.body = body;
            this.raw = raw;
        }
    }

    private Json() {}

    /** Parse a raw response body into a Result, applying the envelope rules. */
    public static Result envelope(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            int code = obj.optInt("code", 0);
            // The envelope uses code 200 for success and -1 (with a msg) for a
            // handled failure. Anything else is treated as a failure too.
            if (code == 200) {
                return new Result(true, null, obj, raw);
            }
            return new Result(false, obj.optString("msg", "request failed"), obj, raw);
        } catch (JSONException e) {
            Log.w(TAG, "unparseable response: " + e);
            return new Result(false, "invalid response", null, raw);
        }
    }

    public static Result failure(String message) {
        return new Result(false, message, null, null);
    }

    /**
     * Extract the "data" member of an envelope. Missing or null data becomes
     * an empty object so callers can index into it without null checks.
     */
    public static JSONObject dataObject(Result r) {
        if (r.body == null) return new JSONObject();
        Object d = r.body.opt("data");
        if (d instanceof JSONObject) return (JSONObject) d;
        return new JSONObject();
    }

    /** Extract a "data" array; a non-array payload yields an empty array. */
    public static JSONArray dataArray(Result r) {
        if (r.body == null) return new JSONArray();
        Object d = r.body.opt("data");
        if (d instanceof JSONArray) return (JSONArray) d;
        return new JSONArray();
    }

    /** Safe string read from a JSONObject, defaulting to the empty string. */
    public static String str(JSONObject o, String key) {
        return o == null ? "" : o.optString(key, "");
    }

    /** Safe string read with an explicit fallback when the key is missing. */
    public static String str(JSONObject o, String key, String fallback) {
        if (o == null || !o.has(key)) return fallback;
        return o.optString(key, fallback);
    }

    /** Safe string read from an array element. */
    public static String str(JSONArray a, int i) {
        return a == null ? "" : a.optString(i, "");
    }

    /** Safe boolean read, defaulting to false. */
    public static boolean bool(JSONObject o, String key) {
        return o != null && o.optBoolean(key, false);
    }

    /** Safe long read, defaulting to 0. */
    public static long lng(JSONObject o, String key) {
        return o == null ? 0L : o.optLong(key, 0L);
    }

    /**
     * Build a JSONObject from alternating key/value pairs. Values may be null.
     * Alternates are (String key, Object value).
     */
    public static JSONObject object(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            put(o, (String) kv[i], kv[i + 1]);
        }
        return o;
    }

    /** JSONException-free put for Object values. */
    public static JSONObject put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** JSONException-free put for boolean values. */
    public static JSONObject put(JSONObject o, String key, boolean value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** JSONException-free put for int values. */
    public static JSONObject put(JSONObject o, String key, int value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** JSONException-free put for long values. */
    public static JSONObject put(JSONObject o, String key, long value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
        return o;
    }
}

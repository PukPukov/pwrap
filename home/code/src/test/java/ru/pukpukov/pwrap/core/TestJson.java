package ru.pukpukov.pwrap.core;

import module java.base;
import com.google.gson.*;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class TestJson {
    
    public static JsonObject obj(@Nullable Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            result.add(Json.required((String) pairs[i], "Expected fixture value"), Json.encode(pairs[i + 1]));
        }
        return result;
    }
    
    static JsonObject object(JsonElement value) { return value.getAsJsonObject(); }
    
    static String string(JsonElement value) { return Json.decode(value, String.class); }
    
    static String string(JsonObject object, String key, String fallback) { return object.has(key) ? string(object.get(key)) : fallback; }
    
    static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? Json.decode(object.get(key), Boolean.class) : fallback;
    }
    
    static long integer(JsonObject object, String key, long fallback) { return object.has(key) ? Json.decode(object.get(key), Long.class) : fallback; }
    
    static <T> List<T> list(JsonObject object, String key, Function<JsonElement, T> read) {
        return object.getAsJsonArray(key).asList().stream().map(read).toList();
    }
    
    static String text(JsonElement value) { return value.toString(); }
    
}
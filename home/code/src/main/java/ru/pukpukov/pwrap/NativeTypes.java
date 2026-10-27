package ru.pukpukov.pwrap;

import module java.base;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import com.google.gson.annotations.JsonAdapter;
import ru.pukpukov.pwrap.core.*;
import ru.pukpukov.pwrap.backends.nix.*;
import ru.pukpukov.pwrap.backends.nixos.*;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Register only our record graph, at build time; no runtime classpath scan. */
public final class NativeTypes implements Feature {

    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> root : List.of(
            Egress.class, Endpoint.class, Config.class, PlatformConfig.class, NixStorage.class, Payload.class,
            Activation.class, ServiceEgress.class, Launch.class, Policy.class, Dbus.class, Session.class,
            FileAuthority.class, Instances.class, Storage.class, Resources.class, Network.class,
            OpenActions.class, Desktop.class, Audio.class, Graphics.class, Platform.class
        )) register(root, seen);
    }

    private static void register(Class<?> type, Set<Class<?>> seen) {
        if (!type.getPackageName().startsWith("ru.pukpukov.pwrap") || !seen.add(type)) return;
        for (Class<?> nested : type.getDeclaredClasses()) register(nested, seen);
        var adapter = type.getAnnotation(JsonAdapter.class);
        if (adapter != null) {
            RuntimeReflection.register(adapter.value());
            RuntimeReflection.register(adapter.value().getDeclaredConstructors());
        }
        if (!type.isRecord() && !type.isEnum() && !type.isAnnotationPresent(Json.Input.class)) return;
        RuntimeReflection.register(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getDeclaredFields());
        RuntimeReflection.register(type.getDeclaredMethods());
        if (type.isRecord()) for (var component : type.getRecordComponents()) registerType(component.getGenericType(), seen);
    }

    private static void registerType(Type type, Set<Class<?>> seen) {
        switch (type) {
            case Class<?> c -> register(c, seen);
            case ParameterizedType p -> {
                registerType(p.getRawType(), seen);
                for (var arg : p.getActualTypeArguments()) registerType(arg, seen);
            }
            default -> { }
        }
    }
}
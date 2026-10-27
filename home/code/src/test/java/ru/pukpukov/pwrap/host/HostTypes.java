package ru.pukpukov.pwrap.host;

import module java.base;
import org.graalvm.nativeimage.hosted.*;
import ru.pukpukov.pwrap.backends.nixos.NativeProjectionChecks;

/** Fixture wire records are registered only in the test image, never the launcher. */
public final class HostTypes implements Feature {
    
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Class<?> type : List.of(
            HostLifetime.class, HostNetwork.class, HostFixtures.class, HostMaintenance.class,
            HostEgress.class, HostActivation.class, HostServices.class, NativeProjectionChecks.class
        )) register(type);
    }
    
    private static void register(Class<?> type) {
        for (Class<?> nested : type.getDeclaredClasses()) register(nested);
        if (!type.isRecord() && !type.isEnum()) return;
        RuntimeReflection.register(type);
        RuntimeReflection.register(type.getDeclaredConstructors());
        RuntimeReflection.register(type.getDeclaredFields());
        RuntimeReflection.register(type.getDeclaredMethods());
    }
}
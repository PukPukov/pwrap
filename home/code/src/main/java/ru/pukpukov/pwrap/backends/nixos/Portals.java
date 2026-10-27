package ru.pukpukov.pwrap.backends.nixos;

import module java.base;
import ru.pukpukov.pwrap.core.OpenActions;

/** NixOS implements generic open integrations with the standard desktop portal. */
public final class Portals {
    
    public static final String NAME = "org.freedesktop.portal.Desktop", PATH = "/org/freedesktop/portal/desktop";
    
    public static List<String> arguments(OpenActions policy) {
        List<String> args = new ArrayList<>(List.of(
            "--pwrap-portal-open=" + String.join(",", policy.schemes()),
            "--pwrap-portal-file-ask=" + policy.openFile().ask(), "--pwrap-portal-uri-ask=" + policy.uri().ask()
        ));
        if (policy.uri().enable()) args.add(call("OpenURI", "OpenURI", PATH));
        if (policy.openFile().enable()) args.add(call("OpenURI", "OpenFile", PATH));
        if (policy.enabled()) args.add(call("Request", "Close", PATH + "/request/*"));
        return args;
    }
    
    private static String call(String api, String member, String path) {
        return "--call=" + NAME + "=org.freedesktop.portal." + api + "." + member + "@" + path;
    }
    
    public static List<String> mimeTypes(OpenActions policy) {
        List<String> types = new ArrayList<>();
        policy.schemes().forEach(s -> types.add("x-scheme-handler/" + s));
        if (policy.uri().enable() && (policy.uri().schemes().contains("http") || policy.uri().schemes().contains("https"))) types.add("text/html");
        return types;
    }
    
}
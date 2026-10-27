/** One Maven artifact; implementation packages stay encapsulated. */
module ru.pukpukov.pwrap {
    requires com.google.gson;
    requires org.checkerframework.checker.qual;
    requires org.graalvm.nativeimage;
    requires org.graalvm.word;
    requires jdk.unsupported;
    
    opens ru.pukpukov.pwrap to com.google.gson;
    opens ru.pukpukov.pwrap.core to com.google.gson;
    opens ru.pukpukov.pwrap.backends.nix to com.google.gson;
    opens ru.pukpukov.pwrap.backends.nixos to com.google.gson;
}
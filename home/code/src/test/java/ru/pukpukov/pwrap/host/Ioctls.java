package ru.pukpukov.pwrap.host;

import module java.base;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.word.*;
import ru.pukpukov.pwrap.core.Posix;
import static ru.pukpukov.pwrap.core.Json.require;

/** ioctl(-1) filter regression only: never opens or operates on a terminal. */
@CContext(Ioctls.Headers.class)
final class Ioctls {
    
    public static final class Headers implements CContext.Directives {
        
        public List<String> getHeaderFiles() { return List.of("<sys/ioctl.h>", "<termios.h>"); }
        
    }
    
    @CConstant static native long TIOCSTI();
    @CConstant static native long TIOCGWINSZ();
    @CFunction static native int ioctl(int fd, UnsignedWord request, VoidPointer argument);
    
    static void check() {
        for (long low : new long[]{TIOCSTI(), TIOCGWINSZ()}) {
            for (long high : new long[]{0, 1L << 32, 0x12345678L << 32, Long.MIN_VALUE, 0xffffffffL << 32}) {
                int result = ioctl(-1, WordFactory.unsigned(high | low), WordFactory.nullPointer());
                int error = Posix.errno();
                require(
                    result == -1 && error == (low == TIOCSTI() ? 1 : 9),
                    "ioctl filter changed for request " + Long.toUnsignedString(high | low, 16) + ": " + error
                );
            }
        }
    }
    
}
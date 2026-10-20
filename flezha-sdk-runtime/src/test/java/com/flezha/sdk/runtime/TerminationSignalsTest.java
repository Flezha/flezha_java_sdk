package com.flezha.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The termination signals a started runner leaves to the core: the JVM's own
 * SIGTERM handler gives way, and a handler the application installed stays.
 * Each handler is read by swapping it out and back, the only read the signal
 * API offers, and the JVM's handlers are restored afterwards.
 */
class TerminationSignalsTest {

    @Test
    @DisplayName("the JVM's SIGTERM handler gives way, and an application's own handler is kept")
    void theJvmsHandlerGivesWayAndTheApplicationsStays() throws Exception {
        Class<?> signalClass = Class.forName("sun.misc.Signal");
        Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
        Method handle = signalClass.getMethod("handle", signalClass, handlerClass);
        Object term = signalClass.getConstructor(String.class).newInstance("TERM");
        Object interrupt = signalClass.getConstructor(String.class).newInstance("INT");
        Object applications = Proxy.newProxyInstance(
                handlerClass.getClassLoader(),
                new Class<?>[] {handlerClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> 0;
                    case "toString" -> "the application's";
                    default -> null;
                });
        Object jvmsTerm = handle.invoke(null, term, applications);
        handle.invoke(null, term, jvmsTerm);
        Object jvmsInterrupt = handle.invoke(null, interrupt, applications);
        try {
            FlezhaImpl.handSignalsToCore();

            assertThat(handle.invoke(null, term, jvmsTerm)).hasToString("handed to the flezha core");
            assertThat(handle.invoke(null, interrupt, jvmsInterrupt)).isSameAs(applications);
        } finally {
            handle.invoke(null, term, jvmsTerm);
            handle.invoke(null, interrupt, jvmsInterrupt);
        }
    }
}

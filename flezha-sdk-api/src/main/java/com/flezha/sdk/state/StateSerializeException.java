package com.flezha.sdk.state;

/** A cell's state or change could not be encoded for saving. */
public class StateSerializeException extends RuntimeException {

    public StateSerializeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The value being saved holds a class its tasklet's codec does not register, so there is no
     * id to write it under. Raised at the first flush, never silently given an ad hoc id.
     */
    public static final class UnregisteredClass extends StateSerializeException {
        private final String className;

        public UnregisteredClass(String className, Throwable cause) {
            super(
                    "UnregisteredClass: `" + className + "` is not registered with its tasklet's codec; declare it"
                            + " as a member class of the tasklet or state class, or list it in the tasklet's"
                            + " kryoTypes()",
                    cause);
            this.className = className;
        }

        /** The binary name of the class that is not registered. */
        public String className() {
            return className;
        }
    }
}

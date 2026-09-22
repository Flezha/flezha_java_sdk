package com.flezha.sdk.tasklet;

/**
 * Why a {@link TaskletCtx#spawnWorkflow} request reached no instance. None of these fault the
 * requesting tasklet: they are thrown by a read of its {@link SpawnHandle}. Each carries the
 * core's own sentence as its message.
 */
public class SpawnException extends Exception {

    public SpawnException(String message) {
        super(message);
    }

    /** The failure the core reported as {@code kind}, one subclass per kind. */
    static SpawnException of(String kind, String message) {
        return switch (kind) {
            case "already_running" -> new AlreadyRunning(message);
            case "factory_unknown" -> new FactoryUnknown(message);
            case "factory_not_workflow" -> new FactoryNotWorkflow(message);
            case "timeout" -> new Timeout(message);
            case "unavailable" -> new Unavailable(message);
            default -> new Refused(message);
        };
    }

    /** The id is taken and the request carried {@link IfRunning#ERROR}. */
    public static final class AlreadyRunning extends SpawnException {
        public AlreadyRunning(String message) {
            super(message);
        }
    }

    /** No connected runner registers the requested factory. */
    public static final class FactoryUnknown extends SpawnException {
        public FactoryUnknown(String message) {
            super(message);
        }
    }

    /** The requested factory is registered, but as a stream: only a workflow can be spawned. */
    public static final class FactoryNotWorkflow extends SpawnException {
        public FactoryNotWorkflow(String message) {
            super(message);
        }
    }

    /** The controller declined: authorization, quota, shutdown, or a failed start. */
    public static final class Refused extends SpawnException {
        public Refused(String message) {
            super(message);
        }
    }

    /** No answer arrived within {@link SpawnHandle#RESULT_TIMEOUT_MS} of the request. */
    public static final class Timeout extends SpawnException {
        public Timeout(String message) {
            super(message);
        }
    }

    /** No control plane to request a spawn on. */
    public static final class Unavailable extends SpawnException {
        public Unavailable(String message) {
            super(message);
        }
    }
}

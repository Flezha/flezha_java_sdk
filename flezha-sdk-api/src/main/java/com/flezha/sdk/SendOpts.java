package com.flezha.sdk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a {@link FlezhaRunner#send(String, String, String, SendOpts)} travels:
 * through the controller, with an optional message id and partition key, or
 * locally.
 *
 * <p>Instances are immutable; each setter answers a new one.
 */
public final class SendOpts {

    final String msgId;
    final String key;
    final boolean local;

    private SendOpts(@Nullable String msgId, @Nullable String key, boolean local) {
        this.msgId = msgId;
        this.key = key;
        this.local = local;
    }

    /** Through the controller, with no id and no key. */
    @Nonnull
    public static SendOpts of() {
        return new SendOpts(null, null, false);
    }

    /**
     * The message's id, stamped as {@code flz.msg_id}: an operator whose input
     * log still holds an input of this id absorbs the repeat and answers
     * {@code absorbed}.
     */
    @Nonnull
    public SendOpts msgId(String id) {
        return new SendOpts(id, key, local);
    }

    /** The partition key: the member of a partition group whose range owns it takes the message. */
    @Nonnull
    public SendOpts key(String key) {
        return new SendOpts(msgId, key, local);
    }

    /**
     * In process, without the controller: the message is on no disk, is lost
     * if the runner stops before handling it, and an operator's input log does
     * not cover it.
     */
    @Nonnull
    public SendOpts local() {
        return new SendOpts(msgId, key, true);
    }
}

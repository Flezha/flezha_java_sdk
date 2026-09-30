package com.flezha.examples.agentdesk;

import com.flezha.examples.agentdesk.Budget.Event;
import com.flezha.examples.agentdesk.Budget.Sent;
import com.flezha.examples.agentdesk.Budget.Used;
import com.flezha.examples.agentdesk.Budget.Verdict;
import com.flezha.examples.agentdesk.Reply.Answer;
import com.flezha.sdk.channel.CallTarget;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EntryChannel;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.operator.UnboundedOperator;

/**
 * The HTTP side of the desk: an unbounded operator that carries the HTTP plugin, answers every
 * request, and calls the session a message is for.
 */
public final class GatewayOperator implements UnboundedOperator {

    static final long DEADLINE_MS = 60_000;

    @Override
    public String factoryId() {
        return "gateway";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.effectivelyOnce();                                 // each request and answer on disk before it runs
        EntryChannel<Request> http = c.input(Request.class, "http");
        http.filter(request -> !request.sends())
                .into(Watch.class)
                .response();                                 // a stream, or 404
        Stream<Verdict> verdicts = http.filter(Request::sends)
                .map(Event.class, request -> new Sent(request.message()))
                .keyBy(Event::user)                          // one cell per user
                .into(Budget.class);
        verdicts.into(Admit.class)
                .response();                                 // 202, or 429 over the budget
        verdicts.filter(Verdict::admitted)
                .map(Message.class, Verdict::message)
                .keyBy(Message::user)                        // the user's session
                .call(CallTarget.factory("agent").channel("message"), Answer.class, DEADLINE_MS)
                .map(Event.class, Used::of)
                .keyBy(Event::user)
                .into(Budget.class);                         // the tokens the answer used
    }
}

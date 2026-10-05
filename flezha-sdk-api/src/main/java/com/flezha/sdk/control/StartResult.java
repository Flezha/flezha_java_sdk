package com.flezha.sdk.control;

/**
 * What a relay start answered: the id the relay runs under, the generated one when the
 * start named none, and what the start did.
 */
public record StartResult(String id, StartOutcome outcome) {}

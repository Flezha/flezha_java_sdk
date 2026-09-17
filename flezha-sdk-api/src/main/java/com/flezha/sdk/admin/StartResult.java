package com.flezha.sdk.admin;

/**
 * What an operative start answered: the id the operative runs under, the generated one when the
 * start named none, and what the start did.
 */
public record StartResult(String id, StartOutcome outcome) {}

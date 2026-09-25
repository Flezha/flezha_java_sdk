package com.flezha.sdk.admin;

/**
 * What an operator start answered: the id the operator runs under, the generated one when the
 * start named none, and what the start did.
 */
public record StartResult(String id, StartOutcome outcome) {}

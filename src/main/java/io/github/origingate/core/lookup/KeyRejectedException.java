package io.github.origingate.core.lookup;

/** Thrown when a provider refuses a key or rate-limits the request. The chain then skips that provider for a while. */
public class KeyRejectedException extends LookupException {
    public KeyRejectedException(String message) { super(message); }
}

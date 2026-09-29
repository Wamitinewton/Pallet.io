package io.pallet.gitintegration.push;

public enum ChainDecision {
    ACCEPT,
    DUPLICATE,
    STALE,
    NEEDS_COMPARE
}

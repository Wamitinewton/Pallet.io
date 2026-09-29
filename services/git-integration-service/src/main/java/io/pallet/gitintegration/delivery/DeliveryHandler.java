package io.pallet.gitintegration.delivery;

import java.util.Set;

/**
 * Turns one parsed delivery into its effects. {@link #handle} runs inside the delivery's transaction, so everything it
 * writes, outbox rows included, commits with the delivery's outcome or not at all. A handler never calls GitHub: it
 * throws {@link NeedsGitHub} and is run again with the answer. At most one handler per event.
 */
public interface DeliveryHandler {

    /** Names from {@link SubscribedEvents}. */
    Set<String> events();

    DeliveryOutcome handle(DeliveryContext context);
}

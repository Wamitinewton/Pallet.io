package io.pallet.gitintegration.delivery;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/** The read limits every parse of a webhook payload runs under, whether a signature has passed or not. */
public final class WebhookJson {

    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_STRING_LENGTH = 1024 * 1024;

    private WebhookJson() {}

    public static JsonMapper limitedMapper(long maxDocumentLength) {
        StreamReadConstraints limits = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNameLength(MAX_STRING_LENGTH)
                .maxDocumentLength(maxDocumentLength)
                .build();
        return JsonMapper.builder(
                        JsonFactory.builder().streamReadConstraints(limits).build())
                .build();
    }
}

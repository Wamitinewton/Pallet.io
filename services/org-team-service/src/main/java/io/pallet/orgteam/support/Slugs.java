package io.pallet.orgteam.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class Slugs {

    public static final int MAX_LENGTH = 63;
    public static final String DNS_LABEL_REGEX = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";

    private static final Pattern DNS_LABEL = Pattern.compile(DNS_LABEL_REGEX);
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_HYPHENS = Pattern.compile("^-+|-+$");
    private static final String FALLBACK_PREFIX = "slug-";
    private static final int FALLBACK_SUFFIX_LENGTH = 8;

    private Slugs() {}

    public static String fromName(String name) {
        String folded = DIACRITICS
                .matcher(Normalizer.normalize(name, Normalizer.Form.NFD))
                .replaceAll("")
                .toLowerCase(Locale.ROOT);
        String slug = trimHyphens(SEPARATORS.matcher(folded).replaceAll("-"));
        if (slug.length() > MAX_LENGTH) {
            slug = trimHyphens(slug.substring(0, MAX_LENGTH));
        }
        return slug.isEmpty() ? randomSlug() : slug;
    }

    public static boolean isValid(String slug) {
        return slug != null
                && slug.length() <= MAX_LENGTH
                && DNS_LABEL.matcher(slug).matches();
    }

    private static String trimHyphens(String value) {
        return EDGE_HYPHENS.matcher(value).replaceAll("");
    }

    private static String randomSlug() {
        return FALLBACK_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, FALLBACK_SUFFIX_LENGTH);
    }
}

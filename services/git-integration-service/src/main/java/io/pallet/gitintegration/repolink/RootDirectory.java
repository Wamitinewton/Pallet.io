package io.pallet.gitintegration.repolink;

import io.pallet.gitintegration.repolink.RepoLinkExceptions.InvalidRootDirectoryException;

/**
 * The directory inside the repository an app builds from; null is the repository root. The schema's check constraint
 * is the second line behind these rules, not a substitute for them.
 */
public final class RootDirectory {

    static final int MAX_LENGTH = 255;

    private RootDirectory() {}

    /**
     * @return the normalized directory (no trailing {@code /}), or null for the repository root
     * @throws InvalidRootDirectoryException for an absolute path, any {@code ..}, a {@code .} or empty segment, a
     *     backslash, a control character, or more than 255 characters
     */
    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidRootDirectoryException("must be at most " + MAX_LENGTH + " characters");
        }
        if (value.startsWith("/")) {
            throw new InvalidRootDirectoryException("must be relative to the repository root");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                throw new InvalidRootDirectoryException("must not contain a backslash");
            }
            if (Character.isISOControl(c)) {
                throw new InvalidRootDirectoryException("must not contain control characters");
            }
        }
        if (value.contains("..")) {
            throw new InvalidRootDirectoryException("must not contain '..'");
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new InvalidRootDirectoryException("must not contain an empty segment");
            }
            if (segment.equals(".")) {
                throw new InvalidRootDirectoryException("must not contain a '.' segment");
            }
        }
        return normalized;
    }
}

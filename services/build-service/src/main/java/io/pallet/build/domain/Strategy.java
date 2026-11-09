package io.pallet.build.domain;

/** How an image is built. {@code AUTO} is only ever a setting; a build's resolved strategy is one of the other two. */
public enum Strategy {
    AUTO,
    DOCKERFILE,
    BUILDPACKS
}

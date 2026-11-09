package io.pallet.build.domain;

/** The app's build settings as they stood when a build was first dispatched; later attempts keep them. */
public record SettingsSnapshot(Strategy strategySetting, String dockerfilePath) {

    public static final SettingsSnapshot DEFAULTS = new SettingsSnapshot(Strategy.AUTO, "Dockerfile");
}

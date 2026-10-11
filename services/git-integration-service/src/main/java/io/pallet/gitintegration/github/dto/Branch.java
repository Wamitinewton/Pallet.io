package io.pallet.gitintegration.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code GET /repositories/{id}/branches/{branch}}, trimmed to the head commit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Branch(String name, Commit commit) {

    /** {@code author} is the GitHub account, absent when the commit's author email matches none. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(String sha, Detail commit, Account author) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detail(String message, Person author) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Person(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String login) {}
}

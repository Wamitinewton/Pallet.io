"use client";

import { useDebouncedValue } from "@/shared/presentation/hooks";
import { Field, Icon, Input, InputGroup, Spinner, VisuallyHidden, type InputProps } from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import { Fragment, type ReactNode } from "react";
import { slugProblem, suggestSlugs } from "../domain/signup";
import { SLUG_HOST, SLUG_UNCHECKED_COPY, slugAvailableCopy, slugTakenCopy } from "./identity-copy";
import { useIdentityUseCases } from "./identity-use-cases";
import { identityQueries } from "./queries";
import styles from "./SlugField.module.css";

export const SLUG_CHECK_DEBOUNCE_MS = 400;

type Availability =
    | { readonly status: "empty" }
    | { readonly status: "invalid"; readonly problem: string }
    | { readonly status: "checking" }
    | { readonly status: "available" }
    | { readonly status: "taken" }
    | { readonly status: "unchecked" };

export type SlugFieldProps = Omit<InputProps, "value"> & {
    readonly slug: string;
    readonly organizationName: string;
    readonly error?: string | undefined;
    readonly onSuggestion: (slug: string) => void;
};

/** Says whether the slug is free while the person types; it informs, and the server's answer on submit is final. */
export function SlugField({ slug, organizationName, error, onSuggestion, ...inputProps }: SlugFieldProps) {
    const { checkSlugAvailability } = useIdentityUseCases();
    const checked = useDebouncedValue(slug, SLUG_CHECK_DEBOUNCE_MS);
    const problem = slug === "" ? undefined : slugProblem(slug);
    const settled = checked === slug;
    const query = useQuery({
        ...identityQueries.slugAvailability(checkSlugAvailability, checked),
        enabled: settled && slug !== "" && problem === undefined,
    });

    const availability = ((): Availability => {
        if (slug === "") return { status: "empty" };
        if (problem !== undefined) return { status: "invalid", problem };
        if (!settled || query.isPending) return { status: "checking" };
        if (query.isError) return { status: "unchecked" };
        return { status: query.data.available ? "available" : "taken" };
    })();

    return (
        <Field
            label="Organization URL"
            error={error ?? errorFor(availability, slug, organizationName, onSuggestion)}
            success={
                availability.status === "available" && (
                    <span className={styles.status}>
                        <Icon name="check" />
                        {slugAvailableCopy(slug)}
                    </span>
                )
            }
            hint={hintFor(availability, slug)}
        >
            <InputGroup addon={SLUG_HOST}>
                <Input mono autoComplete="off" autoCapitalize="none" spellCheck={false} {...inputProps} />
            </InputGroup>
            <VisuallyHidden role="status">{announcementFor(availability, slug)}</VisuallyHidden>
        </Field>
    );
}

function errorFor(
    availability: Availability,
    slug: string,
    organizationName: string,
    onSuggestion: (slug: string) => void,
): ReactNode {
    if (availability.status === "invalid") return availability.problem;
    if (availability.status !== "taken") return undefined;

    const suggestions = suggestSlugs(slug, organizationName);
    if (suggestions.length === 0) return slugTakenCopy(slug);
    return (
        <>
            {slugTakenCopy(slug)} Try{" "}
            {suggestions.map((suggestion, index) => (
                <Fragment key={suggestion}>
                    {index > 0 && " or "}
                    <button
                        type="button"
                        className={styles.suggestion}
                        onClick={() => {
                            onSuggestion(suggestion);
                        }}
                    >
                        {suggestion}
                    </button>
                </Fragment>
            ))}
            .
        </>
    );
}

function hintFor(availability: Availability, slug: string): ReactNode {
    switch (availability.status) {
        case "checking":
            return (
                <span className={styles.status}>
                    <Spinner size={12} />
                    Checking {SLUG_HOST}
                    {slug}
                </span>
            );
        case "unchecked":
            return SLUG_UNCHECKED_COPY;
        default:
            return undefined;
    }
}

function announcementFor(availability: Availability, slug: string): string {
    switch (availability.status) {
        case "available":
            return slugAvailableCopy(slug);
        case "taken":
            return slugTakenCopy(slug);
        default:
            return "";
    }
}

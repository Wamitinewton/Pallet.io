import { UseCasesProvider } from "@/composition/client";
import { httpSessionGateway } from "@/modules/session/infrastructure/http-session-gateway";
import { fixedClock, type Clock } from "@/shared/domain/clock";
import { browserTransport } from "@/shared/infrastructure/api/browser-transport";
import { httpFetch } from "@/shared/infrastructure/http/http-fetch";
import { ClockProvider, QueryProvider, ThemeProvider } from "@/shared/presentation/providers";
import { createQueryClient } from "@/shared/presentation/query";
import { Toaster } from "@/shared/presentation/ui";
import type { QueryClient } from "@tanstack/react-query";
import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { NuqsTestingAdapter, type OnUrlUpdateFunction } from "nuqs/adapters/testing";
import type { ReactElement, ReactNode } from "react";
import { TEST_ORIGIN } from "./msw/server";

export const TEST_NOW = "2026-01-15T12:00:00Z";

export interface RenderWithProvidersOptions extends Omit<RenderOptions, "wrapper"> {
    readonly clock?: Clock;
    readonly searchParams?: string | Record<string, string>;
    readonly onUrlUpdate?: OnUrlUpdateFunction;
    /** Feeds each URL update back into the page, as a browser would, instead of freezing the initial one. */
    readonly urlMemory?: boolean;
    readonly queryClient?: QueryClient;
}

export interface RenderWithProvidersResult extends RenderResult {
    readonly user: UserEvent;
    readonly queryClient: QueryClient;
}

export function createTestQueryClient(): QueryClient {
    const client = createQueryClient();
    const defaults = client.getDefaultOptions();
    client.setDefaultOptions({
        ...defaults,
        queries: { ...defaults.queries, retry: false, refetchOnWindowFocus: false },
    });
    return client;
}

export function renderWithProviders(
    ui: ReactElement,
    {
        clock = fixedClock(TEST_NOW),
        searchParams,
        onUrlUpdate,
        urlMemory = false,
        queryClient = createTestQueryClient(),
        ...options
    }: RenderWithProvidersOptions = {},
): RenderWithProvidersResult {
    const transport = browserTransport({ baseUrl: `${TEST_ORIGIN}/bff` });
    const sessionGateway = httpSessionGateway({ fetch: httpFetch, baseUrl: `${TEST_ORIGIN}/api/session` });

    function Wrapper({ children }: { children: ReactNode }) {
        return (
            <NuqsTestingAdapter
                hasMemory={urlMemory}
                {...(searchParams !== undefined && { searchParams })}
                {...(onUrlUpdate !== undefined && { onUrlUpdate })}
            >
                <ThemeProvider>
                    <QueryProvider client={queryClient}>
                        <ClockProvider clock={clock}>
                            <UseCasesProvider transport={transport} sessionGateway={sessionGateway}>
                                <Toaster>{children}</Toaster>
                            </UseCasesProvider>
                        </ClockProvider>
                    </QueryProvider>
                </ThemeProvider>
            </NuqsTestingAdapter>
        );
    }

    const user = userEvent.setup();
    return { user, queryClient, ...render(ui, { wrapper: Wrapper, ...options }) };
}

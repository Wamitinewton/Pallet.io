export interface AccessTokenProvider {
    accessToken(): Promise<string | undefined>;
}

export const anonymousAccessTokens: AccessTokenProvider = {
    accessToken: () => Promise.resolve(undefined),
};

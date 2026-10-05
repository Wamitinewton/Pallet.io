/** Raw fetch for adapters that speak HTTP themselves (the BFF proxy) rather than through an API client. */
export const httpFetch: typeof fetch = (input, init) => fetch(input, init);

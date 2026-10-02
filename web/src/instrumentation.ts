export async function register(): Promise<void> {
    if (process.env.NEXT_RUNTIME === "nodejs") {
        const { validateServerEnv } = await import("./instrumentation-node");
        validateServerEnv();
    }
}

const LEVELS = ["trace", "debug", "info", "warn", "error", "fatal"] as const;

export type LogLevel = (typeof LEVELS)[number];

export type LogFields = Readonly<Record<string, unknown>>;

export interface Logger {
    debug(event: string, fields?: LogFields): void;
    info(event: string, fields?: LogFields): void;
    warn(event: string, fields?: LogFields): void;
    error(event: string, fields?: LogFields): void;
}

export type LogSink = (line: string, level: LogLevel) => void;

const standardStreams: LogSink = (line, level) => {
    (LEVELS.indexOf(level) >= LEVELS.indexOf("warn") ? process.stderr : process.stdout).write(`${line}\n`);
};

function serializable(value: unknown): unknown {
    return value instanceof Error ? { name: value.name, message: value.message } : value;
}

export function createLogger(minimum: LogLevel, sink: LogSink = standardStreams): Logger {
    const threshold = LEVELS.indexOf(minimum);
    const write =
        (level: LogLevel) =>
        (event: string, fields: LogFields = {}) => {
            if (LEVELS.indexOf(level) < threshold) return;
            const entries = Object.entries(fields).map(([name, value]) => [name, serializable(value)]);
            sink(
                JSON.stringify({ time: new Date().toISOString(), level, event, ...Object.fromEntries(entries) }),
                level,
            );
        };

    return { debug: write("debug"), info: write("info"), warn: write("warn"), error: write("error") };
}

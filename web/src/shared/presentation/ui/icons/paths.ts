export type IconShape =
    | { readonly kind: "path"; readonly d: string }
    | { readonly kind: "circle"; readonly cx: number; readonly cy: number; readonly r: number }
    | {
          readonly kind: "rect";
          readonly x: number;
          readonly y: number;
          readonly width: number;
          readonly height: number;
          readonly rx?: number;
      };

export const ICON_PATHS = {
    home: [
        { kind: "path", d: "M3 10.5 12 3l9 7.5" },
        { kind: "path", d: "M5 9.5V20h5v-6h4v6h5V9.5" },
    ],
    apps: [
        { kind: "path", d: "M12 3 3.5 7.5 12 12l8.5-4.5L12 3Z" },
        { kind: "path", d: "m3.5 12 8.5 4.5 8.5-4.5" },
        { kind: "path", d: "m3.5 16.5 8.5 4.5 8.5-4.5" },
    ],
    users: [
        { kind: "circle", cx: 9, cy: 8, r: 3.5 },
        { kind: "path", d: "M2.5 20c.6-3.4 3.3-5.5 6.5-5.5s5.9 2.1 6.5 5.5" },
        { kind: "path", d: "M16 4.7a3.5 3.5 0 0 1 0 6.6" },
        { kind: "path", d: "M18.5 14.8c1.6.8 2.7 2.6 3 5.2" },
    ],
    teams: [
        { kind: "rect", x: 3, y: 3, width: 7.5, height: 7.5, rx: 2 },
        { kind: "rect", x: 13.5, y: 3, width: 7.5, height: 7.5, rx: 2 },
        { kind: "rect", x: 3, y: 13.5, width: 7.5, height: 7.5, rx: 2 },
        { kind: "rect", x: 13.5, y: 13.5, width: 7.5, height: 7.5, rx: 2 },
    ],
    github: [
        {
            kind: "path",
            d: "M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21",
        },
    ],
    settings: [
        { kind: "circle", cx: 12, cy: 12, r: 3 },
        {
            kind: "path",
            d: "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
        },
    ],
    bell: [
        { kind: "path", d: "M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" },
        { kind: "path", d: "M10.3 21a1.9 1.9 0 0 0 3.4 0" },
    ],
    user: [
        { kind: "circle", cx: 12, cy: 8, r: 4 },
        { kind: "path", d: "M4 21c.8-4 4-6.5 8-6.5s7.2 2.5 8 6.5" },
    ],
    logout: [
        { kind: "path", d: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" },
        { kind: "path", d: "m16 17 5-5-5-5" },
        { kind: "path", d: "M21 12H9" },
    ],
    plus: [{ kind: "path", d: "M12 5v14M5 12h14" }],
    check: [{ kind: "path", d: "M20 6 9 17l-5-5" }],
    "check-circle": [
        { kind: "circle", cx: 12, cy: 12, r: 9 },
        { kind: "path", d: "m8.5 12 2.5 2.5 4.5-5" },
    ],
    x: [{ kind: "path", d: "M18 6 6 18M6 6l12 12" }],
    copy: [
        { kind: "rect", x: 9, y: 9, width: 12, height: 12, rx: 2 },
        { kind: "path", d: "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" },
    ],
    "chevron-down": [{ kind: "path", d: "m6 9 6 6 6-6" }],
    "chevron-right": [{ kind: "path", d: "m9 6 6 6-6 6" }],
    selector: [
        { kind: "path", d: "m7 15 5 5 5-5" },
        { kind: "path", d: "m7 9 5-5 5 5" },
    ],
    menu: [{ kind: "path", d: "M4 6h16M4 12h16M4 18h16" }],
    sun: [
        { kind: "circle", cx: 12, cy: 12, r: 4 },
        {
            kind: "path",
            d: "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
        },
    ],
    moon: [{ kind: "path", d: "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" }],
    alert: [
        { kind: "path", d: "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" },
        { kind: "path", d: "M12 9v4M12 17h.01" },
    ],
    info: [
        { kind: "circle", cx: 12, cy: 12, r: 9 },
        { kind: "path", d: "M12 16v-4M12 8h.01" },
    ],
    mail: [
        { kind: "rect", x: 3, y: 5, width: 18, height: 14, rx: 2 },
        { kind: "path", d: "m3 7 9 6 9-6" },
    ],
    branch: [
        { kind: "circle", cx: 6, cy: 6, r: 2.5 },
        { kind: "circle", cx: 6, cy: 18, r: 2.5 },
        { kind: "circle", cx: 18, cy: 7, r: 2.5 },
        { kind: "path", d: "M6 8.5v7M18 9.5c0 5-6 3.5-11 7" },
    ],
    commit: [
        { kind: "circle", cx: 12, cy: 12, r: 3.5 },
        { kind: "path", d: "M3 12h5.5M15.5 12H21" },
    ],
    refresh: [
        { kind: "path", d: "M21 12a9 9 0 0 1-15.4 6.4L3 16" },
        { kind: "path", d: "M3 12a9 9 0 0 1 15.4-6.4L21 8" },
        { kind: "path", d: "M21 3v5h-5M3 21v-5h5" },
    ],
    trash: [
        {
            kind: "path",
            d: "M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 1H8a2 2 0 0 1-2-1L5 6",
        },
    ],
    external: [{ kind: "path", d: "M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" }],
    lock: [
        { kind: "rect", x: 4, y: 11, width: 16, height: 10, rx: 2 },
        { kind: "path", d: "M8 11V7a4 4 0 1 1 8 0v4" },
    ],
    key: [
        { kind: "circle", cx: 7.5, cy: 15.5, r: 4.5 },
        { kind: "path", d: "m10.7 12.3 9.8-9.8M17 6l3 3M14.5 8.5l2 2" },
    ],
    monitor: [
        { kind: "rect", x: 2, y: 4, width: 20, height: 13, rx: 2 },
        { kind: "path", d: "M8 21h8M12 17v4" },
    ],
    phone: [
        { kind: "rect", x: 6, y: 2, width: 12, height: 20, rx: 2.5 },
        { kind: "path", d: "M11 18h2" },
    ],
    play: [{ kind: "path", d: "M7 4.5v15l12.5-7.5L7 4.5Z" }],
    link: [
        { kind: "path", d: "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" },
        { kind: "path", d: "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" },
    ],
    unlink: [
        {
            kind: "path",
            d: "m18.8 13.3 1.7-1.8a5 5 0 0 0-7-7l-1.8 1.7M5.2 10.7l-1.7 1.8a5 5 0 0 0 7 7l1.8-1.7M8 2v3M2 8h3M16 22v-3M22 16h-3",
        },
    ],
    shield: [{ kind: "path", d: "M12 22s8-3.5 8-10V5l-8-3-8 3v7c0 6.5 8 10 8 10Z" }],
    more: [
        { kind: "circle", cx: 5, cy: 12, r: 1.3 },
        { kind: "circle", cx: 12, cy: 12, r: 1.3 },
        { kind: "circle", cx: 19, cy: 12, r: 1.3 },
    ],
    grid: [
        { kind: "rect", x: 3, y: 3, width: 7, height: 7, rx: 1.5 },
        { kind: "rect", x: 14, y: 3, width: 7, height: 7, rx: 1.5 },
        { kind: "rect", x: 3, y: 14, width: 7, height: 7, rx: 1.5 },
        { kind: "rect", x: 14, y: 14, width: 7, height: 7, rx: 1.5 },
    ],
    arrow: [{ kind: "path", d: "M5 12h14M13 6l6 6-6 6" }],
    back: [{ kind: "path", d: "M19 12H5M11 18l-6-6 6-6" }],
    folder: [{ kind: "path", d: "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z" }],
    clock: [
        { kind: "circle", cx: 12, cy: 12, r: 9 },
        { kind: "path", d: "M12 7v5l3 2" },
    ],
    globe: [
        { kind: "circle", cx: 12, cy: 12, r: 9 },
        { kind: "path", d: "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" },
    ],
    search: [
        { kind: "circle", cx: 11, cy: 11, r: 7 },
        { kind: "path", d: "m20 20-3.5-3.5" },
    ],
    building: [
        { kind: "rect", x: 4, y: 3, width: 16, height: 18, rx: 1.5 },
        { kind: "path", d: "M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3" },
    ],
    send: [
        { kind: "path", d: "M22 2 11 13" },
        { kind: "path", d: "M22 2 15 22l-4-9-9-4 20-7Z" },
    ],
    eye: [
        { kind: "path", d: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" },
        { kind: "circle", cx: 12, cy: 12, r: 3 },
    ],
    archive: [
        { kind: "rect", x: 2, y: 3, width: 20, height: 5, rx: 1 },
        { kind: "path", d: "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8M10 12h4" },
    ],
    cloud: [
        { kind: "path", d: "M17.5 19a4.5 4.5 0 1 0-1.3-8.8A7 7 0 1 0 6 18.3" },
        { kind: "path", d: "M6 19h11.5" },
    ],
    "eye-off": [
        { kind: "path", d: "M10.6 5.1A10.6 10.6 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-2.6 3.6" },
        { kind: "path", d: "M6.6 6.6A17.2 17.2 0 0 0 2 12s3.5 7 10 7a9.9 9.9 0 0 0 5.4-1.6" },
        { kind: "path", d: "M9.9 9.9a3 3 0 0 0 4.2 4.2" },
        { kind: "path", d: "m2 2 20 20" },
    ],
} as const satisfies Record<string, readonly IconShape[]>;

export type IconName = keyof typeof ICON_PATHS;

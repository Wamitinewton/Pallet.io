import { BRAND_MARK } from "@/shared/presentation/ui";
import { ImageResponse } from "next/og";
import { BRAND_COLORS, SITE_NAME } from "./site";

export const alt = `${SITE_NAME}: push to your branch, and Pallet takes it from there.`;
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OpenGraphImage() {
    return new ImageResponse(
        <div
            style={{
                width: "100%",
                height: "100%",
                display: "flex",
                flexDirection: "column",
                justifyContent: "space-between",
                padding: "72px 80px",
                background: BRAND_COLORS.night,
                backgroundImage: `radial-gradient(circle at 88% 12%, ${BRAND_COLORS.blue}55, transparent 46%), radial-gradient(circle at 8% 100%, ${BRAND_COLORS.green}26, transparent 40%)`,
                color: BRAND_COLORS.paper,
                fontFamily: "sans-serif",
            }}
        >
            <div style={{ display: "flex", alignItems: "center", gap: 20 }}>
                <svg width={72} height={70} viewBox={BRAND_MARK.viewBox}>
                    <g fill="none" strokeLinecap="round" strokeLinejoin="round">
                        <path d={BRAND_MARK.upperChevron} stroke={BRAND_COLORS.green} strokeWidth={40} />
                        <path d={BRAND_MARK.lowerChevron} stroke={BRAND_COLORS.blue} strokeWidth={40} />
                    </g>
                    <g fill={BRAND_COLORS.blue} stroke={BRAND_COLORS.blue} strokeWidth={16} strokeLinejoin="round">
                        {BRAND_MARK.blocks.map((d) => (
                            <path key={d} d={d} />
                        ))}
                    </g>
                </svg>
                <span style={{ fontSize: 48, fontWeight: 700, letterSpacing: "-0.01em" }}>{SITE_NAME}</span>
            </div>

            <div style={{ display: "flex", flexDirection: "column", gap: 28 }}>
                <div
                    style={{
                        display: "flex",
                        flexDirection: "column",
                        fontSize: 76,
                        fontWeight: 700,
                        lineHeight: 1.04,
                        letterSpacing: "-0.03em",
                    }}
                >
                    <span>Push to your branch.</span>
                    <span style={{ color: BRAND_COLORS.blueOnDark }}>Pallet takes it from there.</span>
                </div>
                <span style={{ fontSize: 30, color: BRAND_COLORS.nightMuted }}>
                    Link a GitHub repo, pick a production branch, bring your team.
                </span>
            </div>

            <div
                style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 14,
                    paddingTop: 28,
                    borderTop: `1px solid ${BRAND_COLORS.nightLine}`,
                    fontSize: 24,
                    color: BRAND_COLORS.nightMuted,
                }}
            >
                <span style={{ width: 12, height: 12, borderRadius: 999, background: BRAND_COLORS.green }} />
                Early access · Built in public, one service at a time
            </div>
        </div>,
        size,
    );
}

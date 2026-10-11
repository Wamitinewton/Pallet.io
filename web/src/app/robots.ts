import { siteUrl } from "@/composition/site";
import type { MetadataRoute } from "next";

const PRIVATE_PATHS = [
    "/orgs",
    "/account",
    "/notifications",
    "/invites",
    "/reset-password",
    "/github",
    "/api",
    "/bff",
] as const;

export default function robots(): MetadataRoute.Robots {
    const base = siteUrl();
    return {
        rules: { userAgent: "*", allow: "/", disallow: [...PRIVATE_PATHS] },
        sitemap: new URL("/sitemap.xml", base).href,
        host: base.origin,
    };
}

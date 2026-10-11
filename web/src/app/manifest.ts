import type { MetadataRoute } from "next";
import { BRAND_COLORS, SITE_DESCRIPTION, SITE_NAME } from "./site";

export default function manifest(): MetadataRoute.Manifest {
    return {
        name: SITE_NAME,
        short_name: SITE_NAME,
        description: SITE_DESCRIPTION,
        start_url: "/",
        display: "standalone",
        background_color: BRAND_COLORS.mist,
        theme_color: BRAND_COLORS.blue,
        icons: [
            { src: "/icon.svg", type: "image/svg+xml", sizes: "any" },
            { src: "/apple-icon.png", type: "image/png", sizes: "180x180" },
        ],
    };
}

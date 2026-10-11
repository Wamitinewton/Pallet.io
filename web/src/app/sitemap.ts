import { siteUrl } from "@/composition/site";
import { authPaths } from "@/modules/session";
import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
    const base = siteUrl();
    const entry = (path: string, priority: number): MetadataRoute.Sitemap[number] => ({
        url: new URL(path, base).href,
        changeFrequency: "monthly",
        priority,
    });
    return [entry("/", 1), entry(authPaths.signUp, 0.8), entry(authPaths.signIn(), 0.5)];
}

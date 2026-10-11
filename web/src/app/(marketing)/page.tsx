import type { Metadata } from "next";
import { SITE_DESCRIPTION, SITE_NAME, SITE_TAGLINE } from "../site";
import { ClosingCta } from "./sections/ClosingCta";
import { Hero } from "./sections/Hero";
import { HowItWorks } from "./sections/HowItWorks";
import { TeamsAndRoles } from "./sections/TeamsAndRoles";

export const metadata: Metadata = {
    title: { absolute: `${SITE_NAME} · ${SITE_TAGLINE}` },
    description: SITE_DESCRIPTION,
    alternates: { canonical: "/" },
};

export default function LandingPage() {
    return (
        <>
            <Hero />
            <HowItWorks />
            <TeamsAndRoles />
            <ClosingCta />
        </>
    );
}

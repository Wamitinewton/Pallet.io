import { JetBrains_Mono, Onest, Outfit } from "next/font/google";

const outfit = Outfit({
    subsets: ["latin"],
    weight: ["500", "600", "700"],
    display: "swap",
    variable: "--font-outfit",
});

const onest = Onest({
    subsets: ["latin"],
    weight: ["400", "500", "600"],
    display: "swap",
    variable: "--font-onest",
});

const jetBrainsMono = JetBrains_Mono({
    subsets: ["latin"],
    weight: ["400", "500"],
    display: "swap",
    variable: "--font-jetbrains-mono",
});

export const fontVariables = [outfit.variable, onest.variable, jetBrainsMono.variable].join(" ");

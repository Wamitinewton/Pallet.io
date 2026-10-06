import { z } from "zod";

const publicEnvSchema = z.object({
    NEXT_PUBLIC_APP_NAME: z.string().min(1).default("Pallet"),
    NEXT_PUBLIC_GITHUB_APP_SLUG: z.string().min(1).optional(),
    NEXT_PUBLIC_INVITE_EXISTING_ACCOUNT: z.stringbool().default(false),
});

export type PublicEnv = Readonly<z.output<typeof publicEnvSchema>>;

const blank = (value: string | undefined) => (value === "" ? undefined : value);

// Each variable is referenced by its literal name so Next.js can inline it into the client bundle.
export const publicEnv: PublicEnv = Object.freeze(
    publicEnvSchema.parse({
        NEXT_PUBLIC_APP_NAME: blank(process.env.NEXT_PUBLIC_APP_NAME),
        NEXT_PUBLIC_GITHUB_APP_SLUG: blank(process.env.NEXT_PUBLIC_GITHUB_APP_SLUG),
        NEXT_PUBLIC_INVITE_EXISTING_ACCOUNT: blank(process.env.NEXT_PUBLIC_INVITE_EXISTING_ACCOUNT),
    }),
);

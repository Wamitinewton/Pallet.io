import { emailSchema } from "@/shared/domain/email";
import { z } from "zod";

/** No length or strength rule on the password: an account may predate the current policy. */
export const credentialsSchema = z.object({
    email: emailSchema,
    password: z.string().min(1, "Enter your password"),
});

export type Credentials = z.output<typeof credentialsSchema>;

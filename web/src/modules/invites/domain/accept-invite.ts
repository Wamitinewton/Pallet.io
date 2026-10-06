import { passwordSchema } from "@/shared/domain/password-policy";
import { z } from "zod";

export const newAccountSchema = z.object({ password: passwordSchema });

export type NewAccount = z.output<typeof newAccountSchema>;

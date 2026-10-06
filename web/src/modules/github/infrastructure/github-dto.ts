import { z } from "zod";
import { ACCOUNT_TYPES } from "../domain/installation";

export const instantSchema = z.iso.datetime({ offset: true });

export const installationIdSchema = z.number().int().positive();

export const accountSchema = {
    installationId: installationIdSchema,
    accountLogin: z.string().min(1),
    accountType: z.enum(ACCOUNT_TYPES),
};

/** The browser is sent wherever this says, so nothing but an https URL gets through. */
export const githubUrlSchema = z.url({ protocol: /^https$/ });

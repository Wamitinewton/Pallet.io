import { publicEnv } from "@/shared/infrastructure/config/public-env";

/** Switches for flows whose backend half ships separately; each defaults off. */
export const features = {
    /** An existing account accepting an invite while signed in, once identity-service/15 is deployed. */
    inviteExistingAccount: publicEnv.NEXT_PUBLIC_INVITE_EXISTING_ACCOUNT,
} as const;

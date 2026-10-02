declare const brand: unique symbol;

export type Brand<T, B extends string> = T & { readonly [brand]: B };

export type OrgId = Brand<string, "OrgId">;
export type UserId = Brand<string, "UserId">;
export type AppId = Brand<string, "AppId">;
export type TeamId = Brand<string, "TeamId">;
export type InviteId = Brand<string, "InviteId">;
export type InstallationId = Brand<number, "InstallationId">;
export type RepoId = Brand<number, "RepoId">;
export type DeliveryId = Brand<string, "DeliveryId">;

export const asOrgId = (value: string) => value as OrgId;
export const asUserId = (value: string) => value as UserId;
export const asAppId = (value: string) => value as AppId;
export const asTeamId = (value: string) => value as TeamId;
export const asInviteId = (value: string) => value as InviteId;
export const asInstallationId = (value: number) => value as InstallationId;
export const asRepoId = (value: number) => value as RepoId;
export const asDeliveryId = (value: string) => value as DeliveryId;

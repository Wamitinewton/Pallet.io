import { parseAsStringLiteral } from "nuqs";
import { GITHUB_RETURN_FLAGS, GITHUB_RETURN_PARAM } from "../domain/return-intent";

export const CONNECT_STEPS = ["existing"] as const;

export const githubSearchParams = {
    [GITHUB_RETURN_PARAM]: parseAsStringLiteral(GITHUB_RETURN_FLAGS),
    connect: parseAsStringLiteral(CONNECT_STEPS),
};

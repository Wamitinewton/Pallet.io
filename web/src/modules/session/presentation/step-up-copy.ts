export const STEP_UP_TITLE_COPY = "Confirm it's you";
export const WRONG_PASSWORD_COPY = "That password isn't right.";

export function stepUpDescription(email: string | undefined): string {
    const whose = email === undefined ? "your password" : `the password for ${email}`;
    return `This needs a recent sign-in. Enter ${whose} to continue.`;
}

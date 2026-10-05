import { AuthSoloScreen } from "./AuthSoloScreen";
import { ResetPasswordForm, type ResetPasswordFormProps } from "./ResetPasswordForm";

export function ResetPasswordScreen({ token }: ResetPasswordFormProps) {
    return (
        <AuthSoloScreen>
            <ResetPasswordForm token={token} />
        </AuthSoloScreen>
    );
}

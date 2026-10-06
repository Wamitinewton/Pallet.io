import { AuthSoloScreen } from "@/shared/presentation/shell";
import { ResetPasswordForm, type ResetPasswordFormProps } from "./ResetPasswordForm";

export function ResetPasswordScreen({ token }: ResetPasswordFormProps) {
    return (
        <AuthSoloScreen>
            <ResetPasswordForm token={token} />
        </AuthSoloScreen>
    );
}

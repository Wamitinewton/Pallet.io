import { AuthSoloScreen } from "@/shared/presentation/shell";
import { ForgotPasswordForm, type ForgotPasswordFormProps } from "./ForgotPasswordForm";

export function ForgotPasswordScreen({ email }: ForgotPasswordFormProps) {
    return (
        <AuthSoloScreen>
            <ForgotPasswordForm email={email} />
        </AuthSoloScreen>
    );
}

"use client";

import { emailSchema } from "@/shared/domain/email";
import { AuthForm, AuthHeader, Button, Field, Input } from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { z } from "zod";

const addressSchema = z.object({ email: emailSchema });

export interface EmailAddressStepProps {
    readonly onContinue: (email: string) => void;
}

export function EmailAddressStep({ onContinue }: EmailAddressStepProps) {
    const form = useForm<z.output<typeof addressSchema>>({
        resolver: zodResolver(addressSchema),
        defaultValues: { email: "" },
    });

    const submit = form.handleSubmit(({ email }) => {
        onContinue(email);
    });

    return (
        <AuthForm onSubmit={(event) => void submit(event)} noValidate>
            <AuthHeader
                title="Confirm your email"
                lede="Enter the email you signed up with, then the code we sent to it."
            />
            <Field label="Email" error={form.formState.errors.email?.message}>
                <Input
                    type="email"
                    autoComplete="email"
                    autoCapitalize="none"
                    spellCheck={false}
                    {...form.register("email")}
                />
            </Field>
            <Button type="submit" variant="primary" size="lg" block>
                Continue
            </Button>
        </AuthForm>
    );
}

"use client";

import { ApiError } from "@/shared/domain/errors";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import {
    Button,
    Field,
    Icon,
    Input,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { isUnchanged, profileUpdateSchema, type Profile, type ProfileUpdate } from "../domain/profile";
import {
    EMAIL_CONFIRMED_COPY,
    PROFILE_DESCRIPTION_COPY,
    PROFILE_NOT_SAVED_COPY,
    PROFILE_SAVED_COPY,
} from "./account-copy";
import styles from "./AccountView.module.css";
import { useUpdateProfile } from "./use-account";

export interface ProfilePanelProps {
    readonly profile: Profile;
}

export function ProfilePanel({ profile }: ProfilePanelProps) {
    const toast = useToast();
    const update = useUpdateProfile();
    const form = useForm<ProfileUpdate>({
        resolver: zodResolver(profileUpdateSchema),
        defaultValues: { displayName: profile.displayName },
    });
    const typed = useWatch({ control: form.control, name: "displayName" });

    const submit = form.handleSubmit((values) => {
        update.mutate(values, {
            onSuccess: () => {
                form.reset(values);
                toast.success(PROFILE_SAVED_COPY);
            },
            onError: (error) => {
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) toast.error(`${PROFILE_NOT_SAVED_COPY} ${messageFor(error)}`);
            },
        });
    });

    return (
        <Panel>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title="Profile" description={PROFILE_DESCRIPTION_COPY} />
                <PanelBody>
                    <Stack>
                        <Field label="Name" error={form.formState.errors.displayName?.message}>
                            <Input autoComplete="name" {...form.register("displayName")} />
                        </Field>
                        <Field
                            label="Email"
                            hint={
                                <span className={styles.confirmed}>
                                    <Icon name="check-circle" />
                                    {EMAIL_CONFIRMED_COPY}
                                </span>
                            }
                        >
                            <Input value={profile.email} readOnly />
                        </Field>
                    </Stack>
                </PanelBody>
                <PanelFoot className={styles.footEnd}>
                    <Button
                        type="submit"
                        variant="primary"
                        size="sm"
                        loading={update.isPending}
                        disabled={isUnchanged(profile, typed)}
                    >
                        Save
                    </Button>
                </PanelFoot>
            </form>
        </Panel>
    );
}

"use client";

import { ApiError } from "@/shared/domain/errors";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import {
    Button,
    Field,
    Input,
    OrgAvatar,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import {
    isSameName,
    renameOrganizationSchema,
    type Organization,
    type RenameOrganizationForm,
} from "../domain/organization";
import { GENERAL_DESCRIPTION_COPY, NOT_RENAMED_COPY, RENAMED_COPY } from "./organization-copy";
import styles from "./OrgSettingsView.module.css";
import { useRenameOrganization } from "./use-organization-mutations";

export interface RenameOrgPanelProps {
    readonly organization: Organization;
    readonly canRename: boolean;
}

export function RenameOrgPanel({ organization, canRename }: RenameOrgPanelProps) {
    const toast = useToast();
    const rename = useRenameOrganization(organization.orgId);
    const form = useForm<RenameOrganizationForm>({
        resolver: zodResolver(renameOrganizationSchema),
        defaultValues: { name: organization.name },
    });
    const typed = useWatch({ control: form.control, name: "name" });

    const submit = form.handleSubmit((values) => {
        rename.mutate(values, {
            onSuccess: (renamed) => {
                form.reset({ name: renamed.name });
                toast.success(RENAMED_COPY);
            },
            onError: (error) => {
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) toast.error(`${NOT_RENAMED_COPY} ${messageFor(error)}`);
            },
        });
    });

    return (
        <Panel>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title="General" description={GENERAL_DESCRIPTION_COPY} />
                <PanelBody>
                    <div className={styles.general}>
                        <OrgAvatar name={organization.name} size="lg" />
                        <Field label="Name" className={styles.nameField} error={form.formState.errors.name?.message}>
                            <Input autoComplete="organization" readOnly={!canRename} {...form.register("name")} />
                        </Field>
                    </div>
                </PanelBody>
                {canRename && (
                    <PanelFoot className={styles.footEnd}>
                        <Button
                            type="submit"
                            variant="primary"
                            size="sm"
                            loading={rename.isPending}
                            disabled={isSameName(organization, typed)}
                        >
                            Save
                        </Button>
                    </PanelFoot>
                )}
            </form>
        </Panel>
    );
}

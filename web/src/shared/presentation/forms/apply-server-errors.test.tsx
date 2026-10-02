import type { FieldError } from "@/shared/domain/errors";
import { testApiClients } from "@/test/clients";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen } from "@testing-library/react";
import { http } from "msw";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { describe, expect, it } from "vitest";
import { Button, Field, Input } from "../ui";
import { applyServerErrors, toFormPath } from "./apply-server-errors";

interface OrgForm {
    name: string;
    slug: string;
}

function CreateOrgForm() {
    const [clients] = useState(testApiClients);
    const form = useForm<OrgForm>({ defaultValues: { name: "", slug: "" } });
    const [unplaced, setUnplaced] = useState<readonly FieldError[]>([]);
    const { errors } = form.formState;

    const submit = form.handleSubmit(async (values) => {
        try {
            await clients.orgTeam.POST("/orgs", { body: values });
        } catch (thrown) {
            setUnplaced(applyServerErrors(form, thrown));
        }
    });

    return (
        <form onSubmit={(event) => void submit(event)}>
            <Field label="Name" error={errors.name?.message}>
                <Input {...form.register("name")} />
            </Field>
            <Field label="URL" error={errors.slug?.message}>
                <Input {...form.register("slug")} />
            </Field>
            {unplaced.length > 0 && (
                <ul aria-label="Other problems">
                    {unplaced.map((problem) => (
                        <li key={problem.field}>
                            {problem.field}: {problem.message}
                        </li>
                    ))}
                </ul>
            )}
            <Button type="submit">Create</Button>
        </form>
    );
}

describe("applyServerErrors", () => {
    it("lands field errors from a 400 on their fields and returns the ones it can't place", async () => {
        server.use(
            http.post(bffUrl("/org-team/orgs"), () =>
                error(400, "VALIDATION_ERROR", {
                    validationErrors: [
                        { field: "name", message: "must not be blank" },
                        { field: "slug", message: "must be a lowercase DNS label" },
                        { field: "plan", message: "is not available" },
                    ],
                }),
            ),
        );
        const { user } = renderWithProviders(<CreateOrgForm />);

        await user.type(screen.getByLabelText("URL"), "Not A Slug");
        await user.click(screen.getByRole("button", { name: "Create" }));

        expect(await screen.findByText("must not be blank")).toBeInTheDocument();
        expect(screen.getByLabelText("Name")).toHaveAccessibleDescription("must not be blank");
        expect(screen.getByLabelText("URL")).toHaveAccessibleDescription("must be a lowercase DNS label");
        expect(screen.getByLabelText("Name")).toHaveFocus();
        expect(screen.getByRole("list", { name: "Other problems" })).toHaveTextContent("plan: is not available");
    });

    it("places nothing for a success", async () => {
        server.use(
            http.post(bffUrl("/org-team/orgs"), () => ok({ id: "o-1" }, "Organization created", { status: 201 })),
        );
        const { user } = renderWithProviders(<CreateOrgForm />);

        await user.click(screen.getByRole("button", { name: "Create" }));

        expect(screen.queryByRole("list", { name: "Other problems" })).not.toBeInTheDocument();
        expect(screen.getByLabelText("Name")).not.toHaveAttribute("aria-invalid");
    });

    it("maps the backend's indexed paths onto form paths", () => {
        expect(toFormPath("members[2].role")).toBe("members.2.role");
        expect(toFormPath("buildSettings.branch")).toBe("buildSettings.branch");
    });
});

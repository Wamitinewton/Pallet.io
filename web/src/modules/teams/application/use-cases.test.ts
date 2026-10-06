import { ApiError } from "@/shared/domain/errors";
import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { TeamDeletionNotConfirmedError } from "../domain/team";
import { DEFAULT_TEAM_LIST_PARAMS, teamListQuery, teamMemberListQuery } from "../domain/team-list-query";
import { aTeam, aTeamMember } from "../domain/testing/fixtures";
import { InMemoryTeamRepository } from "./testing/in-memory";
import { makeTeamUseCases } from "./use-cases";

const orgId = asOrgId("org-kilima");
const payments = aTeam();
const grace = aTeamMember();

const apiError = (status: number, code: string) => new ApiError(status, code, "message", [], {}, undefined);

function setUp(roster: readonly (typeof grace)[] = []) {
    const teams = new InMemoryTeamRepository(
        [payments],
        [grace],
        new Map([[payments.id, roster.map((person) => person.userId)]]),
    );
    return { teams, useCases: makeTeamUseCases({ teams }) };
}

describe("team use cases", () => {
    it("lists one page of teams for the query it is given", async () => {
        const { useCases } = setUp();

        const page = await useCases.listTeams(orgId, teamListQuery(DEFAULT_TEAM_LIST_PARAMS));

        expect(page.items).toEqual([payments]);
    });

    it("creates a team from the trimmed form, leaving an empty slug to the backend", async () => {
        const { teams, useCases } = setUp();

        await useCases.createTeam(orgId, { name: "  Data ", slug: "" });

        expect(teams.created).toEqual([{ orgId, team: { name: "Data", slug: undefined } }]);
    });

    it("refuses an invalid form before asking the backend", async () => {
        const { teams, useCases } = setUp();

        await expect(useCases.createTeam(orgId, { name: "Data", slug: "Not A Slug" })).rejects.toThrow();
        expect(teams.created).toEqual([]);
    });

    it("renames a team", async () => {
        const { teams, useCases } = setUp();

        const renamed = await useCases.renameTeam({ orgId, team: payments, rename: { name: " Billing " } });

        expect(renamed.name).toBe("Billing");
        expect(teams.renames).toEqual([{ orgId, teamId: payments.id, rename: { name: "Billing" } }]);
    });

    it("doesn't send a rename to the name the team already has", async () => {
        const { teams, useCases } = setUp();

        expect(await useCases.renameTeam({ orgId, team: payments, rename: { name: "Payments " } })).toBe(payments);
        expect(teams.renames).toEqual([]);
    });

    it("deletes a team once its name is typed exactly", async () => {
        const { teams, useCases } = setUp();

        await useCases.deleteTeam({ orgId, team: payments, confirmation: "Payments" });

        expect(teams.deletions).toEqual([{ orgId, teamId: payments.id }]);
    });

    it("never deletes on a confirmation that doesn't match", async () => {
        const { teams, useCases } = setUp();

        await expect(useCases.deleteTeam({ orgId, team: payments, confirmation: "payments" })).rejects.toBeInstanceOf(
            TeamDeletionNotConfirmedError,
        );
        expect(teams.deletions).toEqual([]);
    });

    it("lists a team's people", async () => {
        const { useCases } = setUp([grace]);

        const page = await useCases.listTeamMembers(orgId, payments.id, teamMemberListQuery(1));

        expect(page.items).toEqual([grace]);
    });

    it("adds someone and answers with them", async () => {
        const { teams, useCases } = setUp();

        expect(await useCases.addTeamMember(orgId, payments.id, grace.userId)).toEqual({
            status: "added",
            member: grace,
        });
        expect(teams.additions).toEqual([{ teamId: payments.id, userId: grace.userId }]);
    });

    it("reads ALREADY_IN_TEAM as someone already where the caller wanted them", async () => {
        const { teams, useCases } = setUp();
        teams.failNext = apiError(409, "ALREADY_IN_TEAM");

        expect(await useCases.addTeamMember(orgId, payments.id, grace.userId)).toEqual({ status: "alreadyInTeam" });
    });

    it("lets MEMBER_NOT_FOUND on an add through: the person left the organization", async () => {
        const { teams, useCases } = setUp();
        teams.failNext = apiError(404, "MEMBER_NOT_FOUND");

        await expect(useCases.addTeamMember(orgId, payments.id, grace.userId)).rejects.toMatchObject({
            code: "MEMBER_NOT_FOUND",
        });
    });

    it("removes someone from the team", async () => {
        const { teams, useCases } = setUp([grace]);

        expect(await useCases.removeTeamMember(orgId, payments.id, grace.userId)).toBe("removed");
        expect(teams.removals).toEqual([{ teamId: payments.id, userId: grace.userId }]);
    });

    it("reads MEMBER_NOT_FOUND on a removal as someone already off the team", async () => {
        const { teams, useCases } = setUp();
        teams.failNext = apiError(404, "MEMBER_NOT_FOUND");

        expect(await useCases.removeTeamMember(orgId, payments.id, grace.userId)).toBe("alreadyRemoved");
    });

    it("lets any other refusal of a removal through", async () => {
        const { teams, useCases } = setUp([grace]);
        teams.failNext = apiError(403, "INSUFFICIENT_ROLE");

        await expect(useCases.removeTeamMember(orgId, payments.id, grace.userId)).rejects.toMatchObject({
            code: "INSUFFICIENT_ROLE",
        });
    });
});

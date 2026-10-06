import { asOrgId, type OrgId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { NewOrganization, Organization, OrganizationRename, OrgSummary } from "../../domain/organization";
import { anOrganization } from "../../domain/testing/fixtures";
import type { OrganizationRepository } from "../ports";

export class InMemoryOrganizationRepository implements OrganizationRepository {
    readonly listRequests: PageRequest[] = [];
    readonly created: NewOrganization[] = [];
    readonly renames: { readonly orgId: OrgId; readonly rename: OrganizationRename }[] = [];
    readonly deletions: { readonly orgId: OrgId; readonly confirmSlug: string }[] = [];

    constructor(
        private readonly summaries: readonly OrgSummary[] = [],
        private readonly organizations: readonly Organization[] = [],
    ) {}

    listMine(request: PageRequest): Promise<Page<OrgSummary>> {
        this.listRequests.push(request);
        const items = this.summaries.slice(request.page * request.size, (request.page + 1) * request.size);
        const totalPages = Math.ceil(this.summaries.length / request.size);
        return Promise.resolve({
            items,
            page: request.page,
            size: request.size,
            totalItems: this.summaries.length,
            totalPages,
            isFirst: request.page === 0,
            isLast: request.page >= totalPages - 1,
        });
    }

    get(orgId: OrgId): Promise<Organization> {
        const organization = this.organizations.find((org) => org.orgId === orgId);
        return organization === undefined
            ? Promise.reject(new Error(`No organization ${orgId}`))
            : Promise.resolve(organization);
    }

    create(organization: NewOrganization): Promise<Organization> {
        this.created.push(organization);
        return Promise.resolve(
            anOrganization({
                orgId: asOrgId(`org-${String(this.created.length)}`),
                name: organization.name,
                slug: organization.slug ?? "derived-slug",
                counts: { members: 1, teams: 0, apps: 0 },
            }),
        );
    }

    async rename(orgId: OrgId, rename: OrganizationRename): Promise<Organization> {
        this.renames.push({ orgId, rename });
        return { ...(await this.get(orgId)), name: rename.name };
    }

    delete(orgId: OrgId, confirmSlug: string): Promise<void> {
        this.deletions.push({ orgId, confirmSlug });
        return Promise.resolve();
    }
}

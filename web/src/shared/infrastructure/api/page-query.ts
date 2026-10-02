import type { PageRequest } from "@/shared/domain/page";

export const MAX_PAGE_SIZE = 100;

export interface PageQueryParams {
    page: number;
    size: number;
    sort?: string;
}

export function toPageQuery({ page, size, sort = [] }: PageRequest): PageQueryParams {
    const params: PageQueryParams = {
        page: Math.max(0, Math.trunc(page)),
        size: Math.min(MAX_PAGE_SIZE, Math.max(1, Math.trunc(size))),
    };
    if (sort.length > 0) {
        params.sort = sort.map(({ field, direction }) => `${field},${direction}`).join(";");
    }
    return params;
}

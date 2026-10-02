export type SortDirection = "asc" | "desc";

export interface SortOrder {
    readonly field: string;
    readonly direction: SortDirection;
}

export interface PageRequest {
    readonly page: number;
    readonly size: number;
    readonly sort?: readonly SortOrder[];
}

export interface Page<T> {
    readonly items: readonly T[];
    readonly page: number;
    readonly size: number;
    readonly totalItems: number;
    readonly totalPages: number;
    readonly isFirst: boolean;
    readonly isLast: boolean;
}

"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { OrganizationUseCases } from "../application/use-cases";

export const [OrganizationUseCasesProvider, useOrganizationUseCases] =
    createUseCasesContext<OrganizationUseCases>("OrganizationUseCases");

"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { IdentityUseCases } from "../application/use-cases";

export const [IdentityUseCasesProvider, useIdentityUseCases] =
    createUseCasesContext<IdentityUseCases>("IdentityUseCases");

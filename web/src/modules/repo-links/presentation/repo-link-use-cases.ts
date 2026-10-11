"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { RepoLinkUseCases } from "../application/use-cases";

export const [RepoLinkUseCasesProvider, useRepoLinkUseCases] =
    createUseCasesContext<RepoLinkUseCases>("RepoLinkUseCases");

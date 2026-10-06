"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { GitHubUseCases } from "../application/use-cases";

export const [GitHubUseCasesProvider, useGitHubUseCases] = createUseCasesContext<GitHubUseCases>("GitHubUseCases");

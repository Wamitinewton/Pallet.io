"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { TeamUseCases } from "../application/use-cases";

export const [TeamUseCasesProvider, useTeamUseCases] = createUseCasesContext<TeamUseCases>("TeamUseCases");

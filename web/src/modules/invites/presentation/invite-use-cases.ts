"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { InviteUseCases } from "../application/use-cases";

export const [InviteUseCasesProvider, useInviteUseCases] = createUseCasesContext<InviteUseCases>("InviteUseCases");

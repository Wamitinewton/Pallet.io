"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { MemberUseCases } from "../application/use-cases";

export const [MemberUseCasesProvider, useMemberUseCases] = createUseCasesContext<MemberUseCases>("MemberUseCases");

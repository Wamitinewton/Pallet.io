"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { AppUseCases } from "../application/use-cases";

export const [AppUseCasesProvider, useAppUseCases] = createUseCasesContext<AppUseCases>("AppUseCases");

"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { NotificationUseCases } from "../application/use-cases";

export const [NotificationUseCasesProvider, useNotificationUseCases] =
    createUseCasesContext<NotificationUseCases>("NotificationUseCases");

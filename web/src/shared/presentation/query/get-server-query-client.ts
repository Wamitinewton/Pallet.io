import "server-only";

import { cache } from "react";
import { createQueryClient } from "./query-client";

export const getServerQueryClient = cache(() => createQueryClient());

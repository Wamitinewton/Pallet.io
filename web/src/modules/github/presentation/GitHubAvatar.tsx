"use client";

import { Icon } from "@/shared/presentation/ui";
import Image from "next/image";
import { useState } from "react";
import { avatarUrl } from "../domain/installation";
import styles from "./GitHub.module.css";

export interface GitHubAvatarProps {
    readonly login: string;
    readonly size?: number;
}

/** Decorative: the login is always written beside it. Falls back to GitHub's mark when the image won't load. */
export function GitHubAvatar({ login, size = 32 }: GitHubAvatarProps) {
    const [failed, setFailed] = useState(false);

    if (failed) {
        return (
            <span className={styles.mark} style={{ width: size, height: size }} aria-hidden="true">
                <Icon name="github" />
            </span>
        );
    }
    return (
        <Image
            src={avatarUrl(login)}
            alt=""
            width={size}
            height={size}
            className={styles.avatar}
            onError={() => {
                setFailed(true);
            }}
        />
    );
}

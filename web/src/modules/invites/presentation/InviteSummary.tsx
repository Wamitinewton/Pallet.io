import { instantToDate } from "@/shared/domain/instant";
import { RoleTag } from "@/shared/presentation/roles";
import { Eyebrow, OrgAvatar, Row, Text } from "@/shared/presentation/ui";
import { INVITED_ROLE_DESCRIPTION, type InvitePreview } from "../domain/invite-preview";
import { formatInviteExpiry, INVITED_EYEBROW_COPY, joinTitle } from "./acceptance-copy";
import styles from "./InviteAccept.module.css";

export interface InviteSummaryProps {
    readonly preview: InvitePreview;
    readonly headingId: string;
}

/** Who invited which address to what, with which role: what the person is agreeing to. */
export function InviteSummary({ preview, headingId }: InviteSummaryProps) {
    const expiresAt = instantToDate(preview.expiresAt);

    return (
        <>
            <Row className={styles.heading}>
                <OrgAvatar name={preview.orgName} size="lg" />
                <div>
                    <Eyebrow>{INVITED_EYEBROW_COPY}</Eyebrow>
                    <h1 id={headingId} className={styles.title}>
                        {joinTitle(preview.orgName)}
                    </h1>
                </div>
            </Row>
            <p>
                <strong className={styles.inviter}>{preview.inviterName}</strong> invited{" "}
                <Text mono className={styles.email}>
                    {preview.maskedEmail}
                </Text>{" "}
                to join {preview.orgName} on Pallet as a <RoleTag role={preview.role} />.
            </p>
            <Text asChild size="sm" tone="muted">
                <p>
                    {INVITED_ROLE_DESCRIPTION[preview.role]} This invite expires on{" "}
                    <time dateTime={expiresAt.toISOString()} suppressHydrationWarning>
                        {formatInviteExpiry(expiresAt)}
                    </time>
                    .
                </p>
            </Text>
        </>
    );
}

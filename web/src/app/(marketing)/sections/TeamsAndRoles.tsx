import { ROLE_DESCRIPTION, RoleTag } from "@/shared/presentation/roles";
import { Icon, type IconName, Panel, Table, TableWrap, Td, Th, VisuallyHidden } from "@/shared/presentation/ui";
import { Band, BandHead, Reveal } from "../_components/Band";
import { SECTION_ID } from "../_components/landing-links";
import styles from "./TeamsAndRoles.module.css";

const ROLES_BY_REACH = ["VIEWER", "DEVELOPER", "ADMIN", "OWNER"] as const;

const HIGHLIGHTS: readonly { icon: IconName; label: string }[] = [
    { icon: "mail", label: "Invite people by email" },
    { icon: "teams", label: "Teams own the apps they run" },
    { icon: "building", label: "Several organizations, one account" },
];

export function TeamsAndRoles() {
    return (
        <Band id={SECTION_ID.teams} layout="split">
            <BandHead
                id={SECTION_ID.teams}
                eyebrow="Teams and roles"
                title="Your whole team, with the right amount of access"
            >
                <p>
                    Give each person a role. Group them into teams, like Payments or Web, and assign apps to the team
                    that owns them. You can belong to several organizations and switch between them from the sidebar.
                </p>
                <ul className={styles.highlights}>
                    {HIGHLIGHTS.map(({ icon, label }) => (
                        <li key={label}>
                            <span className={styles.highlightIcon}>
                                <Icon name={icon} size={16} />
                            </span>
                            {label}
                        </li>
                    ))}
                </ul>
            </BandHead>
            <Reveal>
                <Panel className={styles.panel}>
                    <TableWrap>
                        <Table className={styles.table}>
                            <caption>
                                <VisuallyHidden>What each role can do</VisuallyHidden>
                            </caption>
                            <thead>
                                <tr>
                                    <Th>Role</Th>
                                    <Th>What they can do</Th>
                                </tr>
                            </thead>
                            <tbody>
                                {ROLES_BY_REACH.map((role) => (
                                    <tr key={role}>
                                        <Td className={styles.roleCell}>
                                            <RoleTag role={role} />
                                        </Td>
                                        <Td className={styles.can}>{ROLE_DESCRIPTION[role]}</Td>
                                    </tr>
                                ))}
                            </tbody>
                        </Table>
                    </TableWrap>
                </Panel>
            </Reveal>
        </Band>
    );
}

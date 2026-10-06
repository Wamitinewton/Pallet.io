import { AuthSoloScreen } from "@/shared/presentation/shell";
import { WHAT_IS_PALLET_COPY } from "./acceptance-copy";
import { InviteAcceptView, type InviteAcceptViewProps } from "./InviteAcceptView";

export function InviteAcceptScreen(props: InviteAcceptViewProps) {
    return (
        <AuthSoloScreen footLink={WHAT_IS_PALLET_COPY}>
            <InviteAcceptView {...props} />
        </AuthSoloScreen>
    );
}

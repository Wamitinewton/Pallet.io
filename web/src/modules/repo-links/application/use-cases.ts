import { makeDisconnectRepoLink, type DisconnectRepoLink } from "./disconnect-repo-link";
import { makeGetRepoLink, type GetRepoLink } from "./get-repo-link";
import { makeLinkRepository, type LinkRepository } from "./link-repository";
import { makeListLinkableRepositories, type ListLinkableRepositories } from "./list-linkable-repositories";
import type { RepoLinkRepository, RepositoryPickerRepository } from "./ports";
import { makeRequestManualBuild, type RequestManualBuild } from "./request-manual-build";
import { makeTakeOverVerification, type TakeOverVerification } from "./take-over-verification";
import { makeTuneRepoLink, type TuneRepoLink } from "./tune-repo-link";

export interface RepoLinkUseCases {
    readonly listLinkableRepositories: ListLinkableRepositories;
    readonly linkRepository: LinkRepository;
    readonly getRepoLink: GetRepoLink;
    readonly tuneRepoLink: TuneRepoLink;
    readonly takeOverVerification: TakeOverVerification;
    readonly disconnectRepoLink: DisconnectRepoLink;
    readonly requestManualBuild: RequestManualBuild;
}

export interface RepoLinkDependencies {
    readonly links: RepoLinkRepository;
    readonly repositories: RepositoryPickerRepository;
}

export function makeRepoLinkUseCases({ links, repositories }: RepoLinkDependencies): RepoLinkUseCases {
    return {
        listLinkableRepositories: makeListLinkableRepositories(repositories),
        linkRepository: makeLinkRepository(links),
        getRepoLink: makeGetRepoLink(links),
        tuneRepoLink: makeTuneRepoLink(links),
        takeOverVerification: makeTakeOverVerification(links),
        disconnectRepoLink: makeDisconnectRepoLink(links),
        requestManualBuild: makeRequestManualBuild(links),
    };
}

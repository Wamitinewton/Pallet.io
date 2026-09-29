-- Each retention sweep walks its own index oldest first, so a run never rescans rows an earlier run already handled.
CREATE INDEX ix_webhook_deliveries_payload_held ON git_integration.webhook_deliveries (received_at)
    WHERE payload IS NOT NULL AND status <> 'RECEIVED';
CREATE INDEX ix_installation_links_unlinked_at ON git_integration.installation_links (unlinked_at)
    WHERE status = 'UNLINKED';
CREATE INDEX ix_installations_deleted_at ON git_integration.installations (deleted_at)
    WHERE status = 'DELETED';
CREATE INDEX ix_apps_deleted ON git_integration.apps (updated_at)
    WHERE status = 'DELETED';
CREATE INDEX ix_manual_build_requests_created_at ON git_integration.manual_build_requests (created_at);
CREATE INDEX ix_check_runs_settled ON git_integration.check_runs (updated_at)
    WHERE desired_state = 'completed' AND reported_revision = desired_revision;
CREATE INDEX ix_deleted_orgs_deleted_at ON git_integration.deleted_orgs (deleted_at);

-- The installation-link sweep's guard looks for a repo link in any status.
CREATE INDEX ix_repo_links_installation_link ON git_integration.repo_links (installation_id, org_id);

-- The head commit's message (first line) and author, for display only; untrusted text. Null on a head stored before
-- this migration until the branch next moves, and on one whose source carried neither.
ALTER TABLE git_integration.branch_heads ADD COLUMN head_message VARCHAR(256);
ALTER TABLE git_integration.branch_heads ADD COLUMN head_author VARCHAR(256);

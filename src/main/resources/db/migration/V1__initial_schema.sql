CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_color VARCHAR(7) NOT NULL DEFAULT '#0C2439',
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE verification_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(255) NOT NULL,
    type VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_verification_codes_user_type ON verification_codes (user_id, type);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE boards (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT NULL,
    color VARCHAR(7) NOT NULL DEFAULT '#0C2439',
    icon VARCHAR(40) NOT NULL DEFAULT 'kanban',
    default_view VARCHAR(16) NOT NULL DEFAULT 'LIST',
    owner_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE board_members (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    is_favorite BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_board_members_board_user UNIQUE (board_id, user_id)
);

CREATE TABLE board_invites (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    invited_by_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(16) NOT NULL,
    email VARCHAR(255) NULL,
    role VARCHAR(16) NOT NULL,
    token VARCHAR(128) NOT NULL UNIQUE,
    max_uses INT NULL,
    uses_count INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NULL,
    declined_at TIMESTAMP WITH TIME ZONE NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_board_invites_board_id ON board_invites (board_id);
CREATE INDEX idx_board_invites_email ON board_invites (email);

CREATE TABLE board_columns (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    position DOUBLE PRECISION NOT NULL,
    is_done_column BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_board_columns_board_pos ON board_columns (board_id, position);

CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    column_id UUID NOT NULL REFERENCES board_columns(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    due_date DATE NULL,
    assignee_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE NULL,
    position DOUBLE PRECISION NOT NULL,
    created_by_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tasks_column_pos ON tasks (column_id, position);
CREATE INDEX idx_tasks_board_id ON tasks (board_id);
CREATE INDEX idx_tasks_assignee_completed_due ON tasks (assignee_id, completed, due_date);

CREATE TABLE task_subtasks (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    position DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_task_subtasks_task_pos ON task_subtasks (task_id, position);

CREATE TABLE board_tags (
    id UUID PRIMARY KEY,
    board_id UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    color VARCHAR(7) NOT NULL,
    CONSTRAINT uq_board_tags_board_name UNIQUE (board_id, name)
);

CREATE TABLE task_tags (
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES board_tags(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, tag_id)
);

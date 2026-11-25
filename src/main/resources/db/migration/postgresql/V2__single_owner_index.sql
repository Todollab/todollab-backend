CREATE UNIQUE INDEX uq_board_members_single_owner ON board_members (board_id) WHERE role = 'OWNER';

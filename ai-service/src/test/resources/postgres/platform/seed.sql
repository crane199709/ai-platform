-- 密码仅供本次临时容器：Demo-password-123!。
SET search_path = iam, extensions;
INSERT INTO tenant (id, name) VALUES
('019a2525-0000-7000-8000-000000000001', 'AI 验收租户 A'),
('019a2525-0000-7000-8000-000000000002', 'AI 验收租户 B'),
('019a2525-0000-7000-8000-000000000003', 'AI 无授权租户');
INSERT INTO identity (id, account, email, display_name, password_hash) VALUES
('019a2525-0000-7000-8000-000000000011', 'ai-member', 'ai-member@example.test', 'AI 验收成员',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a2525-0000-7000-8000-000000000031', '019a2525-0000-7000-8000-000000000011', '019a2525-0000-7000-8000-000000000001'),
('019a2525-0000-7000-8000-000000000032', '019a2525-0000-7000-8000-000000000011', '019a2525-0000-7000-8000-000000000002'),
('019a2525-0000-7000-8000-000000000033', '019a2525-0000-7000-8000-000000000011', '019a2525-0000-7000-8000-000000000003');
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a2525-0000-7000-8000-000000000041', '019a2525-0000-7000-8000-000000000001', 'ai-verification', 'AI 验收角色', false),
('019a2525-0000-7000-8000-000000000042', '019a2525-0000-7000-8000-000000000002', 'ai-verification', 'AI 验收角色', false);
INSERT INTO membership_role (membership_id, role_id) VALUES
('019a2525-0000-7000-8000-000000000031', '019a2525-0000-7000-8000-000000000041'),
('019a2525-0000-7000-8000-000000000032', '019a2525-0000-7000-8000-000000000042');
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ai-verification' AND p.code IN ('ai:conversation:use', 'ai:configuration:manage', 'iam:audit:read', 'ai:prompt:read', 'ai:prompt:manage', 'ai:prompt:publish', 'ai:prompt:rollback') AND NOT p.deleted;
INSERT INTO operator (id, account, display_name, password_hash)
VALUES ('019a2525-0000-7000-8000-000000000071', 'ai-operator', 'AI 接入验收',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO platform_role (id, code, name) VALUES ('019a2525-0000-7000-8000-000000000072', 'ai-verification', 'AI 接入验收');
INSERT INTO operator_role (operator_id, role_id) VALUES ('019a2525-0000-7000-8000-000000000071', '019a2525-0000-7000-8000-000000000072');
INSERT INTO platform_role_permission (role_id, permission_id)
SELECT '019a2525-0000-7000-8000-000000000072', id FROM permission
WHERE code IN ('platform:sub-app:manage', 'platform:service-credential:manage', 'platform:ops:manage', 'platform:audit:read') AND realm = 'platform' AND NOT deleted;

-- 本次隔离库的只读成员可用 AI，但无配置管理权限。
INSERT INTO identity (id, account, email, display_name, password_hash) VALUES
('019a2525-0000-7000-8000-000000000012', 'ai-reader', 'ai-reader@example.test', 'AI 只读成员',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a2525-0000-7000-8000-000000000034', '019a2525-0000-7000-8000-000000000012', '019a2525-0000-7000-8000-000000000001');
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a2525-0000-7000-8000-000000000044', '019a2525-0000-7000-8000-000000000001', 'ai-readonly', 'AI 只读验收', false),
('019a2525-0000-7000-8000-000000000043', '019a2525-0000-7000-8000-000000000003', 'ai-verification', 'AI 套餐裁剪验收', false);
INSERT INTO membership_role (membership_id, role_id) VALUES
('019a2525-0000-7000-8000-000000000034', '019a2525-0000-7000-8000-000000000044'),
('019a2525-0000-7000-8000-000000000033', '019a2525-0000-7000-8000-000000000043');
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ai-readonly' AND p.code = 'ai:conversation:use' AND NOT p.deleted;
INSERT INTO role_permission (role_id, permission_id)
SELECT '019a2525-0000-7000-8000-000000000043', p.id FROM permission p
WHERE p.code IN ('ai:conversation:use', 'ai:configuration:manage') AND NOT p.deleted;
-- C 有角色授权，套餐无 AI Feature Pack，真实 IAM 必须裁掉 AI 权限。
INSERT INTO tenant_plan (tenant_id, plan_id, plan_version)
VALUES ('019a2525-0000-7000-8000-000000000003', '019a2525-0000-7000-8000-000000000093', 1);
INSERT INTO operator (id, account, display_name, password_hash)
VALUES ('019a2525-0000-7000-8000-000000000073', 'ai-observer', '无平台权限验收',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');

-- Prompt 草稿管理员具有读取、编辑与使用权限，没有发布或回滚权限。
INSERT INTO identity (id, account, email, display_name, password_hash) VALUES
('019a2525-0700-7000-8000-000000000011', 'ai-prompt-editor', 'ai-prompt-editor@example.test', 'Prompt 编辑者',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO membership (id, identity_id, tenant_id) VALUES
('019a2525-0700-7000-8000-000000000031', '019a2525-0700-7000-8000-000000000011', '019a2525-0000-7000-8000-000000000001');
INSERT INTO role (id, tenant_id, code, name, builtin) VALUES
('019a2525-0700-7000-8000-000000000041', '019a2525-0000-7000-8000-000000000001', 'ai-prompt-editor', 'Prompt 草稿管理员', false);
INSERT INTO membership_role (membership_id, role_id) VALUES
('019a2525-0700-7000-8000-000000000031', '019a2525-0700-7000-8000-000000000041');
INSERT INTO role_permission (role_id, permission_id)
SELECT '019a2525-0700-7000-8000-000000000041', id FROM permission
WHERE code IN ('ai:conversation:use', 'ai:prompt:read', 'ai:prompt:manage') AND NOT deleted;

-- MCP 管理与只读使用权限由真实 IAM 发布，未授权成员保持拒绝。
INSERT INTO role_permission (role_id,permission_id)
SELECT r.id,p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ai-verification' AND p.code IN ('ai:mcp:read','ai:mcp:update','ai:mcp:publish','ai:mcp:rollback') AND NOT p.deleted;
INSERT INTO role_permission (role_id,permission_id)
SELECT r.id,p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ai-readonly' AND p.code = 'ai:mcp:read' AND NOT p.deleted;

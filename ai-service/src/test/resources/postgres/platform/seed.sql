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
WHERE r.code = 'ai-verification' AND p.code IN ('ai:conversation:use', 'iam:audit:read') AND NOT p.deleted;
INSERT INTO operator (id, account, display_name, password_hash)
VALUES ('019a2525-0000-7000-8000-000000000071', 'ai-operator', 'AI 接入验收',
'$2a$10$IbZhtjdYzxJ0Z19dRQf3je8E29VAX2jP2KlXz1r1Um1uaF042/jJ.');
INSERT INTO platform_role (id, code, name) VALUES ('019a2525-0000-7000-8000-000000000072', 'ai-verification', 'AI 接入验收');
INSERT INTO operator_role (operator_id, role_id) VALUES ('019a2525-0000-7000-8000-000000000071', '019a2525-0000-7000-8000-000000000072');
INSERT INTO platform_role_permission (role_id, permission_id)
SELECT '019a2525-0000-7000-8000-000000000072', id FROM permission
WHERE code IN ('platform:sub-app:manage', 'platform:service-credential:manage') AND realm = 'platform' AND NOT deleted;

-- 本次隔离库的真实Platform套餐绑定fixture；实际部署使用Platform API。
SET search_path = platform, extensions;
INSERT INTO tenant(id,name,status,contact_email,region) VALUES
('019a2525-0000-7000-8000-000000000001','AI 验收租户 A','active','a@example.test','east'),
('019a2525-0000-7000-8000-000000000002','AI 验收租户 B','active','b@example.test','east');

-- 仅在 SharedContainers 新建的验收库执行，不连接已有开发或共享库。
\set service iam
\set migrator_password iam_migrator
\set app_password iam_app
\ir /docker-entrypoint-initdb.d/lib/provision-service.sql
\set service platform
\set migrator_password platform_migrator
\set app_password platform_app
\ir /docker-entrypoint-initdb.d/lib/provision-service.sql

package io.saasforge.ai.access.service;

import io.saasforge.sdk.security.MembershipGrantStore;
import io.saasforge.sdk.security.OperatorContext;
import org.springframework.stereotype.Service;

/** SDK 的 RequiresPermission 使用服务级 Realm；平台路径显式读取 Operator 的真实授权。 */
@Service
public class PlatformAccessService {
    private final MembershipGrantStore grants;

    public PlatformAccessService(MembershipGrantStore grants) {
        this.grants = grants;
    }

    public boolean hasPermission(String code) {
        return grants.grants(OperatorContext.current()).permissions().contains(code);
    }
}

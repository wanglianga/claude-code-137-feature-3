package com.community.medrecycle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 文档元信息（文档 /v3/api-docs，Swagger UI /swagger-ui.html） */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI medRecycleOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("社区药箱过期药回收称重兑换与环保交接服务 API")
                .version("1.0.0")
                .description("覆盖预约、登记称重、人工复核、积分发放与失败补发、拒收冻结/追缴、"
                        + "暂存、环保交接、异常与追溯。所有业务接口需请求头 X-Auth-Token（/api/auth/login 获取）。")
                .license(new License().name("Internal").url("https://localhost")));
    }
}

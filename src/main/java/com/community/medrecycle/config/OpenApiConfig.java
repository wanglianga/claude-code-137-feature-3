package com.community.medrecycle.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 文档与 Swagger UI：访问 /swagger-ui.html，鉴权头 X-Auth-Token */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI medRecycleOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("社区过期药回收称重兑换与环保交接服务 API")
                        .version("1.1.0")
                        .description("""
                                覆盖 预约->登记->称重封袋->复核->积分发放/失败补发->暂存->环保交接(拒收冻结/追缴)->追溯 全链路。
                                1) 先 POST /api/auth/login 获取 token；
                                2) 点击右上角 Authorize，粘贴 token（发送为请求头 X-Auth-Token）。
                                """))
                .components(new Components().addSecuritySchemes("X-Auth-Token",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Auth-Token")));
    }
}

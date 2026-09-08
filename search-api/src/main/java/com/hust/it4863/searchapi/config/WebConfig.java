package com.hust.it4863.searchapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cho phep goi /api/** tu bat ky origin nao - can thiet vi ui/index.html thuong duoc mo
 * truc tiep bang file:// (origin "null") thay vi qua mot static server cung host:port.
 * Chi dung cho demo/local, khong bat allowCredentials.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}

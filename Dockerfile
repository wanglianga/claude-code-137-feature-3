# 多阶段构建：Maven 编译打包 -> JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 预热依赖（允许该缓存层偶发不完整，真正解析以下方 package 为准）
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/med-recycle-1.0.0.jar app.jar
EXPOSE 8080
# 默认连接 compose 中的 db 服务，可用环境变量覆盖
ENV DB_URL=jdbc:postgresql://db:5432/medrecycle \
    DB_USER=medrecycle \
    DB_PASSWORD=medrecycle
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

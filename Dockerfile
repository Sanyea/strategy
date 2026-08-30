# ========== 构建阶段：Maven 出可执行 jar ==========
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 阿里云 mirror（国内拉依赖加速）
COPY maven-settings.xml /usr/share/maven/conf/settings.xml

# 先拷 pom，独立层缓存依赖；go-offline 失败不阻断（真实构建仍全量解析）
COPY pom.xml .
RUN mvn -B dependency:go-offline || true

COPY src ./src
# 与 CLAUDE.md 构建命令一致；跳过测试
RUN mvn -B clean package -DskipTests

# ========== 运行阶段：JRE + jar，非 root ==========
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行
# UID 用 1006：eclipse-temurin:21-jre 内置用户已占 UID 1000，useradd 固定 1000 会报 UID not unique 构建失败
RUN useradd -r -m -u 1006 spring
USER spring

# repackage 后仅一个可执行 jar（原始 jar 改名 .jar.original，不匹配 *.jar）
COPY --from=builder /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# 生产默认 profile；dev/test 运行时覆盖
ENV SPRING_PROFILES_ACTIVE=dev

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

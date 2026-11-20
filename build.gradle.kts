plugins {
    java
}

group = "com.ghosthunt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // 單元測試：只測不碰伺服器的純邏輯（例如 AI 鬼的大腦）
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// 用本機已安裝的 JDK（26）編譯，輸出 Java 25 位元組碼，符合 Paper 26.3 的最低需求
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// gradlew deploy：打包後把 jar 複製到本機測試伺服器的 plugins 資料夾
tasks.register<Copy>("deploy") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(layout.projectDirectory.dir("test-server/plugins"))
}

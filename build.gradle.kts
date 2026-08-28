// WorldTaste 独立 Slimefun4.1 附属插件构建脚本
// 仅依赖本地 REF/Slimefun4.1 构建产物 + Paper API，不联网下载 Slimefun。
import java.util.Properties

plugins {
    java
}

group = "com.haiman233"
version = "1.8.15-standalone"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 1.21.11 API（非 Slimefun，可从 papermc 仓库获取）
        compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 本地修改版 Slimefun4.1（只读，禁止修改），与 RSC 同一编译路径；
    // REF 缺失时回退到 libs/ 下的服务器同款 jar（两者包结构一致，新旧包名均含）
    val refSlimefun = rootProject.projectDir.resolve("../REF/RykenSlimeCustomizer-1.21.11/REF/Slimefun4.1/target/SlimeFun4.1-4.9.5.jar")
    compileOnly(files(if (refSlimefun.exists()) refSlimefun else file("libs/Slimefun-2025.11-release.jar")))
    // JEG（JustEnoughGuide）软依赖：大配方菜单点击拦截（反射注册，JEG 未装时零影响）
    compileOnly(files("libs/JustEnoughGuide.jar"))
}

// 把 plugin/content/ 下的 WorldTaste 内容 YAML 一并打入 jar（插件运行期从自身资源读取）
val contentYaml = listOf(
    "groups.yml", "recipe_types.yml", "items.yml", "foods.yml", "machines.yml",
    "recipe_machines.yml", "mb_machines.yml", "linked_recipe_machines.yml",
    "template_machines.yml", "workbenches.yml", "mob_drops.yml", "geo_resources.yml", "menus.yml"
)

tasks.processResources {
    filteringCharset = "UTF-8"
    from(rootProject.projectDir.resolve("content")) {
        include(contentYaml)
        into("") // 置于 jar 根目录
    }
}

tasks.jar {
    archiveBaseName.set("WorldTaste")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.jar)
}

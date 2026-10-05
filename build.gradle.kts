// WorldTaste 独立 Slimefun 附属插件构建脚本
// 仅依赖 Paper API 与本地 Slimefun jar，不联网下载 Slimefun。
import java.util.Properties
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "com.haiman233"
version = "1.9.5-standalone"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
    // Java 17 字节码：1.20–1.20.4 服务端普遍运行 Java 17（Slimefun 2025.11 依赖 jar 本身也是 Java 17 字节码），
    // 编译基线仍为 Paper 1.21.11 API（已实测 --release 17 可正常编译），运行期覆盖 1.20 ~ 1.21.4
    options.release.set(17)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 1.21.11 API（非 Slimefun，可从 papermc 仓库获取）
        compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 本地 Slimefun 依赖（compileOnly，运行期由服务器提供）：
    // 服务端为修改版 Slimefun fork，libs/ 下的 2025.11 release jar 与其包结构一致，仅作编译参考
    compileOnly(files("libs/Slimefun-2025.11-release.jar"))
    // JEG（JustEnoughGuide）软依赖：大配方菜单点击拦截（反射注册，JEG 未装时零影响）
    compileOnly(files("libs/JustEnoughGuide.jar"))
}

// paper-api 1.21.x 的模块元数据只发布 JVM 21 variant；本插件字节码目标为 Java 17（运行期覆盖 1.20~1.21.4），
// 显式指定依赖解析的 JVM 版本为 21 以正常选中依赖，编译产物（--release 17）不受影响
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

// 把 plugin/content/ 下的 WorldTaste 内容 YAML 一并打入 jar（插件运行期从自身资源读取）
val contentYaml = listOf(
    "groups.yml", "recipe_types.yml", "items.yml", "foods.yml", "machines.yml",
    "recipe_machines.yml", "mb_machines.yml", "linked_recipe_machines.yml",
    "template_machines.yml", "workbenches.yml", "mob_drops.yml", "geo_resources.yml", "menus.yml",
    "juicer.yml", "sugar.yml", "cellar.yml", "config.yml"
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

// WorldTaste 独立 Slimefun 附属插件构建脚本
// 仅依赖 Paper API 与本地 Slimefun jar，不联网下载 Slimefun。
import java.util.Properties
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "com.haiman233"
version = "1.10.1-standalone"

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

// ---- 版本兼容层三源集 ----
// 依赖分层（无环）：compatApi（接口 + noop）← legacy / modern（分版本 paper 直调实现）
//                 ← main（Compat 路由器引用全部实现类）。
// modern 源集与 main 同基线（1.21.11）：DataComponent API（consumable 组件，1.21.2+）、
// InventoryView 接口形态、Sound 接口形态（Registry 查找）。三源集产物一并打入 jar。
val compatApi by sourceSets.creating {
    java.srcDirs("src/compatApi/java")
}
val legacy by sourceSets.creating {
    java.srcDirs("src/legacy/java")
    // 只依赖 compatApi 的接口（不依赖 main，避免循环——main 反过来要编译 legacy 的产物）
    compileClasspath += compatApi.output
}
val modern by sourceSets.creating {
    java.srcDirs("src/modern/java")
    compileClasspath += compatApi.output
}
// main 的 Compat 路由器引用三个源集的实现类/接口 → main 编译依赖它们的产物
sourceSets.main.get().compileClasspath += compatApi.output + legacy.output + modern.output

dependencies {
    // Paper 1.21.11 API（非 Slimefun，可从 papermc 仓库获取）
        compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // 本地 Slimefun 依赖（compileOnly，运行期由服务器提供）：
    // 服务端为修改版 Slimefun fork，libs/ 下的 2025.11 release jar 与其包结构一致，仅作编译参考
    compileOnly(files("libs/Slimefun-2025.11-release.jar"))
    // JEG（JustEnoughGuide）软依赖：大配方菜单点击拦截（反射注册，JEG 未装时零影响）
    compileOnly(files("libs/JustEnoughGuide.jar"))

    // compatApi 源集：接口签名全部为 Bukkit/原版类型（不含 Slimefun 类型），无需挂 Slimefun jar
    "compatApiCompileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // legacy 源集：Paper 1.20.6 API——与 main(1.21.11) 同接口名但运行形态不同的 API 在此
    // 按 1.20.x 运行时形态编译直调（全程无反射）：InventoryView 抽象类（1.21 起改接口）、
    // Bukkit FoodComponent（1.20.5+，含 setEatSeconds）、Sound 枚举 valueOf（1.21.3 起改接口）、
    // PotionMeta.getBasePotionType（1.20.2+）。运行期由 Compat 按 Bukkit.getMinecraftVersion()
    // 路由：低版本运行时不会加载 modern 类，高版本不会加载 legacy 类（互不 ICCE/NCDFE）。
    "legacyCompileOnly"("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    "modernCompileOnly"("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

// paper-api 1.21.x 的模块元数据只发布 JVM 21 variant；本插件字节码目标为 Java 17（运行期覆盖 1.20~1.21.4），
// 显式指定依赖解析的 JVM 版本为 21 以正常选中依赖，编译产物（--release 17）不受影响
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
configurations.matching {
    it.name == "legacyCompileClasspath" || it.name == "modernCompileClasspath"
            || it.name == "compatApiCompileClasspath"
}.all {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

tasks.named<org.gradle.api.tasks.compile.JavaCompile>("compileCompatApiJava") {
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.named<org.gradle.api.tasks.compile.JavaCompile>("compileLegacyJava") {
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.named<org.gradle.api.tasks.compile.JavaCompile>("compileModernJava") {
    options.encoding = "UTF-8"
    options.release.set(17)
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
    // 语言文件：content/lang/<语言码>.yml → jar 内 lang/<语言码>.yml
    // （运行期由 Lang 从 plugins/WorldTaste/lang/ 读取，缺失时按此路径从 jar 释放）
    from(rootProject.projectDir.resolve("content/lang")) {
        include("*.yml")
        into("lang")
    }
}

tasks.jar {
    archiveBaseName.set("WorldTaste")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    // legacy / modern / compatApi 源集的 class 产物一并打入（版本兼容层）
    from(sourceSets["compatApi"].output)
    from(sourceSets["legacy"].output)
    from(sourceSets["modern"].output)
}

tasks.build {
    dependsOn(tasks.jar)
}

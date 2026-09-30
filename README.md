# 尘世百味 WorldTaste

<img width="220" height="220" alt="worldtaste" src="https://github.com/user-attachments/assets/89593566-830a-466a-b8f2-6cd2b2459d0b" />

尘世百味为 Slimefun（粘液科技）添加来自世界各地的美食、作物、钓鱼与屠宰等内容。

## 尘百jar插件版
- 原作者为[海曼](https://github.com/haiman233)（初代rsc插件），后由[hershate](https://github.com/hershate)改为jar版本
- 由养坤场管理员提修复了大量bug以及一些优化
- jar版本相较rsc的脚本驱动拥有更好的性能！
- 以上操作均为ai操作，本人几乎没有编程基础，不喜勿喷。
- 但是可以保证的是，已经在本地测试服经过一段时间的测试，目前没有遇到其他bug
- 如果遇到问题，欢迎加我的qq`1424136122`或者提issue反馈，我会尽力解决

jar版本依然保留了原版rsc的yml配置文件，可以将jar后缀改为zip后打开即可看到。继承了rsc的易编辑性！

> [!WARNING]
> 物品、机器等定义文件均被打包入jar
> 如果你有自行更改配置文件，那么请在安装新版本时留意发布介绍
> 并自行做出调整！

## 下载

[![构建状态](https://builds.guizhanss.com/api/badge/ykcgly/WorldTaste-Plugin/master/latest)](https://builds.guizhanss.com/ykcgly/WorldTaste-Plugin/master)

## 前置依赖

| 类型 | 插件 |
|---|---|
| 必须 | [Slimefun](https://builds.guizhanss.com/SlimefunGuguProject/Slimefun4/master)(粘液科技本体) |
| 必须 | [Gastronomicon](https://builds.guizhanss.com/SlimefunGuguProject/Gastronomicon/master)（美食家）、[ExoticGarden](https://builds.guizhanss.com/balugaq/ExoticGardenComplex/master)（异域花园·复合花园 fork） |
| 可选 | [Cultivation](https://builds.guizhanss.com/SlimefunGuguProject/Cultivation/main)（农耕工艺）、[InfinityExpansion](https://builds.guizhanss.com/SlimefunGuguProject/InfinityExpansion/master)（无尽贪婪）、[LogiTech](https://builds.guizhanss.com/Ruchikanmani/LogiTech/master) |

> 提示：若 Gastronomicon 的捕鱼网拉低 TPS，可在其配置中禁用捕鱼网（粘液 ID `GN_FISHING_NET_I/II/III`），或改用本附属性能更优的捕鱼器。

### 启动依赖检查

- **硬依赖缺失**（Slimefun / Gastronomicon / ExoticGarden 任一未安装）：控制台输出
  `缺少 xxx，WorldTaste已自动卸载！`，插件自动卸载、不参与加载。
- **软依赖缺失**（Cultivation / InfinityExpansion / LogiTech）：控制台输出
  `缺少 xxx，部分玩法可能无法加载`，插件继续加载。
  JEG（JustEnoughGuide）缺失已有完整降级，不做提示。
- 启动时的「物品未找到」警告已折叠：普通引用缺失（如未装附属导致的 `GN_*`、`YEAST`）
  汇总为一行（含数量与示例）；仅**特殊物品**（榨汁盆、酒窖管理器、温度控制器、果渣、
  甜度试纸、果酒、酒曲等核心物品）缺失时单独告警。

## 构建与安装

```bash
./gradlew build
# 产物：build/libs/WorldTaste-1.9.4-standalone.jar
```

1. 将构建出的 jar放入服务器的 `plugins/` 目录。
2. 装齐上表中的前置插件。
3. 重启服务器（不建议热重载）。

## 功能概览

- **食物**：烘焙、肉食、中餐、汤与炖菜、饮品（酿酒/果汁）、甜品、零食、发酵食品、功能丸子等十余个分类。
- **作物**：多种作物及其变种，带生长与收获机制。
- **钓鱼**：百味钓竿搭配 5 种鱼饵，按权重掉落各类鱼产。
- **屠宰**：为各类生物添加对应的肉与食材掉落。
- **其他**：厨房装饰，以及愚人节 / 无尽贪婪等主题餐饮。
- **酒精度联动**：与异域花园（ExoticGarden·复合花园）联动，全部酒类饮品标注酒精度，饮用后累积到异域花园的酒精系统（50 半醉提示、100 醉酒胡言乱语，随时间缓慢醒酒）。未安装异域花园时仅展示数值，不影响游戏。
- **酿造工艺**：新增主题餐饮--酿造工艺：配方在 juicer.yml 中定义。**默认开启**，如需关闭可在 config.yml 中将 brewing.enabled 设为 false。

## 全局配置（config.yml）

插件首次启动会在 `plugins/WorldTaste/` 下释放 `config.yml`，修改后重启服务器生效。

```yaml
brewing:
  enabled: true                # 酿造工艺总开关：默认 true（默认开启）
  disabled-suffix: "&r&c&l已禁用" # 关闭时追加在分类名后的字样
```

> 已跑过旧版本的服主注意：插件不会覆盖已存在的配置文件，需手动把
> `plugins/WorldTaste/config.yml` 里的 `enabled` 改成期望值，或删掉让它重新释放。

酿造工艺**默认为开启状态**。设为 `false` 后：榨汁盆、酒窖管理器、温度控制器、果渣、甜度试纸、果酒等酿造内容不再注册（无法合成与使用），榨汁/酒窖配方与糖分表也不再加载；但指南中的「酿造工艺」分类按钮仍然保留，并且提示「已禁用」字样。

## 致谢

感谢 [balugaq](https://github.com/balugaq) 编写的 [rsc-editor](https://github.com/balugaq/RSCEditor)，以及 balugaq、Eventually、南柯梦在脚本编写上给予的帮助。



# 尘世百味 WorldTaste

<img width="220" height="220" alt="worldtaste" src="https://github.com/user-attachments/assets/89593566-830a-466a-b8f2-6cd2b2459d0b" />

尘世百味为 Slimefun（粘液科技）添加来自世界各地的美食、作物、钓鱼与屠宰等内容。

## 尘百jar插件版
- 原作者为海曼（初代rsc插件），后由hershate改为jar版本
- 由养坤场管理员提修复了大量bug以及一些优化
- jar版本相较rsc的脚本驱动拥有更好的性能！
- 以上操作均为ai操作，本人几乎没有编程基础，不喜勿喷。
- 但是可以保证的是，已经在本地测试服经过一段时间的测试，目前没有遇到其他bug
- 如果遇到问题，欢迎加我的qq`1424136122`或者提issue反馈，我会尽力解决

## 下载

[![构建状态](https://builds.guizhanss.com/api/badge/ykcgly/WorldTaste-Plugin/master/latest)](https://builds.guizhanss.com/ykcgly/WorldTaste-Plugin/master)

## 前置依赖

| 类型 | 插件 |
|---|---|
| 必须 | [Slimefun](https://builds.guizhanss.com/SlimefunGuguProject/Slimefun4/master)(粘液科技本体) |
| 必须 | [Gastronomicon](https://builds.guizhanss.com/SlimefunGuguProject/Gastronomicon/master)（美食家）、[ExoticGarden](https://builds.guizhanss.com/balugaq/ExoticGardenComplex/master)（异域花园·复合花园 fork） |
| 可选 | [Cultivation](https://builds.guizhanss.com/SlimefunGuguProject/Cultivation/main)（农耕工艺）、[InfinityExpansion](https://builds.guizhanss.com/SlimefunGuguProject/InfinityExpansion/master)（无尽贪婪）、[LogiTech](https://builds.guizhanss.com/Ruchikanmani/LogiTech/master) |

> 提示：若 Gastronomicon 的捕鱼网拉低 TPS，可在其配置中禁用捕鱼网（粘液 ID `GN_FISHING_NET_I/II/III`），或改用本附属性能更优的捕鱼器。

## 构建与安装

```bash
./gradlew build
# 产物：build/libs/WorldTaste-1.8.17-standalone.jar
```

1. 将构建出的 jar放入服务器的 `plugins/` 目录。
2. 装齐上表中的前置插件。
3. 重启服务器（不建议热重载）。

## 功能概览

- **食物**：烘焙、肉食、中餐、汤与炖菜、饮品（酿酒/果汁）、甜品、零食、发酵食品、功能丸子等十余个分类。
- **作物**：多种作物及其变种，带生长与收获机制。
- **钓鱼**：百味钓竿搭配 5 种鱼饵，按权重掉落各类鱼产。
- **屠宰**：为各类生物添加对应的肉与食材掉落。
- **其他**：厨房装饰，以及愚人节 / 无尽贪婪主题餐饮。
- **酒精度联动**：与异域花园（ExoticGarden·复合花园）联动，全部酒类饮品标注酒精度，饮用后累积到异域花园的酒精系统（50 半醉提示、100 醉酒胡言乱语，随时间缓慢醒酒）。未安装异域花园时仅展示数值，不影响游戏。

## 致谢

感谢 [balugaq](https://github.com/balugaq) 编写的 [rsc-editor](https://github.com/balugaq/RSCEditor)，以及 balugaq、Eventually、南柯梦在脚本编写上给予的帮助。



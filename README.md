# AI Job Copilot

面向多平台求职场景的本机工作台：岗位采集、匹配判断、材料准备、投递执行、结果回写，都在同一个界面里完成，投过什么事后查得到。不连线上服务，数据库、登录态、附件都留在本机。

**这是 AI 产品经理方向的作品集项目。** 产品定义、用户与需求分析、业务规则、AI 功能边界、评估体系与原型在 [product/](product/README.md)；一页总览见 [00-作品集总览](product/00-作品集总览.md)。

## 为什么做

我做这个的起因很实际：校招季同一个岗要在几个平台重复搜、重复看 JD、重复写开场白，投完还记不清哪家投过、发的是哪一版简历。手工做几百次，翻车的方式就那几种——投了不对口的岗、附件版本发错、同一个 HR 被打扰两次、平台开始风控了还在空转。下面的东西就是按这几个坑改的，没重写架构。

代码底座来自开源项目 [get_jobs](https://github.com/loks666/get_jobs)，我在它上面做了投递链路和前端的二次开发。产品定义、用户分层、需求优先级、业务规则、AI 功能边界与评估体系是我重新梳理的。两边边界写在 [UPSTREAM.md](UPSTREAM.md) 和 [00-作品集总览](product/00-作品集总览.md)，不含糊。

## 产品速览

| | |
| --- | --- |
| 面向谁 | 四类用户：高频批量型、多版本材料型、质量优先型、复盘驱动型。方向未定或投递量很低的人群不在服务范围内，见 [01](product/01-定位与用户.md) |
| 解决什么 | 投放质量、可控性、可追溯。价值主张是「把有限的额度放到值得的岗位上」，不是投得更多 |
| 关键决策 | 先解决投得准再解决投得多；AI 只放两个决策点且有确定性回退；本机优先；所有跳过都留痕。理由见 [07](product/07-需求分析与取舍.md) |
| 怎么验证 | 17 条可验业务规则、9 个指标口径、16 条测试用例，全部用离线样本和试跑，不放真实投递统计 |

## 文档导航

| 文档 | 看什么 |
| --- | --- |
| [00 作品集总览](product/00-作品集总览.md) | 一页概览、关键决策、贡献边界、阅读路径 |
| [01 定位与用户](product/01-定位与用户.md) | 定位、用户分层、场景矩阵、用户旅程、价值主张 |
| [02 业务流程](product/02-业务流程.md) | 业务流程图、用户操作流程图、节点与异常分支 |
| [03 功能结构](product/03-功能结构.md) | 功能清单（逐行带代码路径）、结构图、平台覆盖 |
| [04 页面原型](product/04-页面原型.md) | 六页低保真线框说明；[prototype.html](product/prototype.html) 可在浏览器直接打开 |
| [05 简版 PRD](product/05-简版PRD.md) | 业务规则、异常降级、验收口径、开放项 |
| [06 AI 设计与评估](product/06-AI设计与评估.md) | AI 产品决策、流程图、指标口径、测试用例 |
| [07 需求分析与取舍](product/07-需求分析与取舍.md) | 需求分层、优先级、替代方案对比、指标树、MVP 取舍 |
| [08 路线图与风险合规](product/08-路线图与风险合规.md) | 阶段归纳、路线图、风险合规、贡献边界 |

## 代码里有什么

| 能力 | 在哪 |
| --- | --- |
| 多平台采集与投递 | `src/main/java/com/getjobs/worker/` 下 Boss、智联、猎聘、51job、实习僧各一条链路，Playwright 驱动 |
| AI 判断与招呼语 | `application/service/AiService.java`、`defaults/page-config.json` 的 prompt、`worker/boss/Boss.java` 的默认模板 |
| 附件简历与人工门 | `BossResumeImageService.java`、`ZhilianController.java`、`front/app/resume-center/` |
| 投递记录 | `front/app/records/`、`front/app/boss/records/`、`front/app/shixiseng/` |
| 实时进度 | 后端 SSE + `front/lib/sse.ts` |
| 配置与登录态 | `ConfigService` / `CookieService`，SQLite 存本机 |

Java 21 + Spring Boot 3.5（Gradle），前端 Next.js 16 / React 19，浏览器用 Playwright，数据存 SQLite。后端 `8888`，前端 `6680`。

## 跑起来

前置：JDK 21、Node.js + pnpm、能访问对应招聘平台。

1. 先准备 `db/getjobs.db`。**仓库不带数据库**，表结构看 `application/entity/` 和 mapper，默认配置看 `src/main/resources/defaults/page-config.json`。
2. 后端 `./gradlew bootRun`。
3. 前端 `cd front && pnpm install && pnpm dev`。
4. 打开 `http://localhost:6680`，填搜索条件、传附件简历。
5. AI 是可选的。要开就在「AI 配置」页填 Base URL / API Key / 模型，或放本机 `.env`；不配也能跑不依赖模型的流程。

`.env` 只放本机：

```
BASE_URL=https://api.openai.com
API_KEY=sk-...
MODEL=...
```

默认配置里的自我介绍、招呼语和搜索关键词都是占位。不改的话 AI 只能吐套话，也搜不到岗。

## 数据安全

- 仓库不带数据库、Cookie、浏览器用户数据、简历图片、运行日志，`.gitignore` 也拦着。
- API Key 和 Webhook 只从本机 `.env` 或配置页读，不提交、不截图外发。
- 只在本机跑，登录态存在本地浏览器数据目录。
- 遵守各平台协议，投递频率由使用者控制；账号受限风险由使用者承担。完整风险清单见 [08](product/08-路线图与风险合规.md)。

## 协议与来源

仅供学习研究和个人求职辅助使用，非商业。协议见 [LICENSE](LICENSE)。

代码底座是开源项目 [get_jobs](https://github.com/loks666/get_jobs)，改动范围和协议说明在 [UPSTREAM.md](UPSTREAM.md)。

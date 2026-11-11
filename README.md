# AI Job Copilot

跑在本机的一个投递工作台。搜岗、看 JD、配简历和招呼语、投出去、记下来，都在同一个界面里。
不连线上服务，数据库、登录态、简历文件都在自己机器上。

做它的原因不复杂：校招季同一个岗要在几个平台重复搜、重复看 JD、重复写开场白，投完还记不清
哪家投过、发的是哪一版简历。手工做几百次，翻车的方式就那几种——投了不对口的岗、附件版本发错、
同一个 HR 被打扰两次、平台开始风控了还在空转。下面的代码就是按这几个坑改的，没重写架构。

## 给谁用

一个人同时盯几个招聘平台、每天有一定投递量的时候才用得上。方向没定清楚的话，先把方向定下来，
比开这个工具管用。

| 坑 | 怎么处理的 |
| --- | --- |
| 重复劳动 | 搜索条件、打招呼、附件上传交给程序，要人判断的地方才停 |
| 投得不准 | JD 先过方向和经验判断，不匹配就不投，不为凑数放宽条件 |
| 简历错配 | 按方向存几份附件简历，按关键词选版本；没对应附件就跳过 |
| 重复与风控 | 平台内去重、企业黑名单、登录态失效和风控页降级；白发一条也算事故 |
| 没反馈 | 投递记录落库、进度实时回显，查得到投了什么、发的哪份、结果如何 |

## 现在有什么

| 能力 | 在哪 |
| --- | --- |
| 多平台采集与投递 | `src/main/java/com/getjobs/worker/` 下 Boss、智联、猎聘、51job 各一条链路，Playwright 驱动 |
| AI 判断与招呼语 | `application/service/AiService.java`、`defaults/page-config.json` 的 prompt，还有 `worker/boss/Boss.java` 里的默认 prompt。先判要不要投，再写一段能直接复制发送的文本；字数、格式、身份口径都写在规则里 |
| 附件简历与人工门 | `BossResumeImageService.java`、`ZhilianController.java`、`front/app/resume-center/` |
| 投递记录 | `front/app/records/`、`front/app/boss/records/`、`front/app/shixiseng/` |
| 实时进度 | 后端 SSE + `front/lib/sse.ts` |
| 配置与登录态 | `ConfigService` / `CookieService`，SQLite 存本机 |

Java 21 + Spring Boot 3.5（Gradle），前端 Next.js 16 / React 19，浏览器用 Playwright，数据存 SQLite。
后端 `8888`，前端 `6680`。

## 目录

```
ai-job-copilot/
├── src/main/java/com/getjobs/    后端接口、worker 投递链路、实体与 mapper
├── src/main/resources/defaults/ 首次启动用的示例配置（含 AI prompt 模板）
├── src/test/                    少量针对规则与 DOM 的单元测试
├── front/app/                   平台配置、岗位分析、简历中心、投递记录
├── front/lib/                   API 与 SSE 封装
└── build.gradle.kts
```

## 跑起来

前置：JDK 21、Node.js + pnpm、能访问对应招聘平台。

1. 先准备 `db/getjobs.db`。**仓库不带数据库**，表结构看 `application/entity/` 和 mapper，默认配置看 `src/main/resources/defaults/page-config.json`。
2. 后端 `./gradlew bootRun`。
3. 前端 `cd front && pnpm install && pnpm dev`。
4. 打开 `http://localhost:6680`，填搜索条件、传自己的附件简历。
5. AI 是可选的。要开就在「AI 配置」页填 Base URL / API Key / 模型，或者放本机 `.env`；不配也能跑不依赖模型的流程。

`.env` 只放本机：

```
BASE_URL=https://api.openai.com
API_KEY=sk-...
MODEL=...
```

默认配置里的自我介绍、招呼语和搜索关键词都是占位。不改的话 AI 只能吐套话，也搜不到岗。

## 产品文档

这一版只把代码和骨架立起来。下面几份会放进 `product/`，分批补，不一次堆完：

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| 产品定位说明 | 定位、目标用户、核心痛点 | 规划中 |
| 业务流程梳理 | 业务流程图、用户操作流程图 | 规划中 |
| 功能拆解与结构 | 功能清单、产品功能结构图 | 规划中 |
| 页面原型 | 核心页面低保真原型 | 规划中 |
| 简版 PRD | 业务规则、异常处理 | 规划中 |
| AI 功能设计与评估 | AI 流程图、评估指标口径、测试用例 | 规划中 |

评估那部分只写指标口径和用例，不放真实投递数字。

## 数据安全

- 仓库不带数据库、Cookie、浏览器用户数据、简历图片、运行日志，`.gitignore` 也拦着。
- API Key 和 Webhook 只从本机 `.env` 或配置页读，别提交、别截图外发。
- 只在本机跑，登录态存在本地浏览器数据目录。
- 遵守各平台协议，投递频率自己控着点；账号受限自己承担。

## 协议与来源

仅供学习研究和本人求职辅助，非商业。协议见 [LICENSE](LICENSE)。

代码底座是开源项目 [get_jobs](https://github.com/loks666/get_jobs)，改动范围和协议说明在 [UPSTREAM.md](UPSTREAM.md)。

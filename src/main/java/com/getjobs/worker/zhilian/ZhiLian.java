package com.getjobs.worker.zhilian;

import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 * 智联招聘自动投递 - Playwright版本
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class ZhiLian {

    @Setter
    private Page page;

    @Setter
    private ZhilianConfig config;

    @Setter
    private ProgressCallback progressCallback;

    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private boolean isLimit = false;
    private int maxPage = 500;

    /** 本次任务累计成功投递数（跨关键词、跨翻页累计） */
    private int deliveredTotal = 0;
    /** 已达到配置的投递上限 */
    private volatile boolean limitReached = false;

    /**
     * 是否连带投递投递弹窗里的「相似职位」。
     * <p>
     * 默认关闭。该功能会勾选「全选」后一次性把相似职位全部投掉：
     * 数量完全不可控（一次可能十几家），而且这批岗位不做任何经验/学历过滤，
     * 对只投应届岗位的场景是纯负担。需要时改回 true 即可。
     */
    private static final boolean DELIVER_SIMILAR_JOBS = false;

    private static final String HOME_URL = "https://www.zhaopin.com/sou/";

    private final ZhilianService zhilianService;

    private static class PageJob {
        int index;
        String jobId;
        String jobTitle;
        String companyName;
        String experience;

        PageJob(int index, String jobId, String jobTitle, String companyName, String experience) {
            this.index = index;
            this.jobId = jobId;
            this.jobTitle = jobTitle;
            this.companyName = companyName;
            this.experience = experience;
        }
    }

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /**
     * 准备工作：加载配置、初始化数据
     */
    public void prepare() {
        log.info("智联招聘准备工作开始...");
        resultList.clear();
        isLimit = false;
        deliveredTotal = 0;
        limitReached = false;
        log.info("智联招聘准备工作完成");
    }

    /**
     * 是否已达到本次任务的投递上限（maxDeliverCount 为空或 <=0 表示不限）。
     */
    private boolean deliverLimitReached() {
        if (limitReached) return true;
        Integer max = config != null ? config.getMaxDeliverCount() : null;
        if (max == null || max <= 0) return false;
        if (deliveredTotal >= max) {
            limitReached = true;
            log.info("已达到本次智联投递上限 {} 家，停止继续投递（累计 {} 家）", max, deliveredTotal);
            sendProgress("已投满 " + max + " 家，任务自动结束", deliveredTotal, max);
            return true;
        }
        return false;
    }

    /**
     * 执行投递任务
     * @return 投递数量
     */
    public int execute() {
        log.info("智联招聘投递任务开始...");
        long startTime = System.currentTimeMillis();

        try {
            // 遍历所有关键词进行投递
            for (String keyword : config.getKeywords()) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    break;
                }
                // 投满上限就不必再换关键词继续搜了
                if (deliverLimitReached()) {
                    break;
                }

                String baseUrl = buildBaseUrl(1);
                deliverByKeyword(keyword, baseUrl);
            }

            long duration = System.currentTimeMillis() - startTime;
            // 用 deliveredTotal 而不是 resultList.size()：新版分栏流程不再往 resultList 里塞数据，
            // 用列表长度统计会永远显示 0，明明投成功了却报“共投递0个岗位”
            String message = String.format("智联招聘投递完成，共投递%d个岗位，用时%s",
                deliveredTotal, formatDuration(duration));
            log.info(message);
            sendProgress(message, null, null);

            if (!resultList.isEmpty()) {
                log.info("新投递公司如下:");
                resultList.forEach(job -> log.info(job.toString()));
            } else {
                log.info("未投递新的岗位...");
            }

        } catch (Exception e) {
            log.error("智联招聘投递过程出现异常", e);
            sendProgress("投递出现异常: " + e.getMessage(), null, null);
        }

        return resultList.size();
    }

    /**
     * 按关键词投递
     */
    /**
     * 把指定附件设为「默认投递」。
     * <p>
     * 实测智联点「立即投递」后没有「在线简历 / 附件简历」二选一，直接发送
     * 「默认投递」标记的那份附件 + 一段固定招呼语。所以想投哪份简历，
     * 只能提前把默认附件切过去——这也是绕开站内旧在线简历的唯一入口。
     *
     * @return true  = 目标附件现在就是默认投递（本来就是，或刚切过去）
     *         false = 附件列表里没有这个文件名，或切换过程出错
     */
    private boolean switchDefaultAttachment(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        try {
            page.navigate("https://i.zhaopin.com/resume");
            PlaywrightUtil.sleep(4);
            // 记住当前默认是哪一份，切失败时好把原因说清楚
            String currentDefault = "";
            Locator items = page.locator(".parse-item");
            int n = items.count();
            for (int i = 0; i < n; i++) {
                String name;
                try {
                    name = items.nth(i).locator(".parse-item__left-title-text").innerText().trim();
                } catch (Exception ignore) {
                    continue;
                }
                String text;
                try {
                    text = items.nth(i).locator(".parse-item__right-text").innerText().trim();
                } catch (Exception ignore) {
                    text = "";
                }
                if (text.contains("默认投递")) {
                    currentDefault = name;
                }
                if (!name.equals(fileName.trim())) {
                    continue;
                }
                if (text.contains("默认投递")) {
                    log.info("默认附件已经是「{}」，无需切换", fileName);
                    return true;
                }
                items.nth(i).locator(".parse-item__right-text").click();
                PlaywrightUtil.sleep(1);
                log.info("已把默认附件切换为「{}」", fileName);
                return true;
            }
            log.error("附件列表里没有「{}」，当前默认是「{}」（列表共 {} 份）",
                    fileName, currentDefault, n);
            return false;
        } catch (Exception e) {
            log.error("切换默认附件失败：{}", e.getMessage());
            return false;
        }
    }

    private void deliverByKeyword(String keyword, String baseUrl) {
        if (isLimit) {
            return;
        }

        try {
            log.info("开始投递关键词: {}", keyword);

            // 每个关键词批次开始前，先把该方向对应的附件设为默认。
            // 切不过去就**中止这批**，绝不沿用上一份（很可能是站内旧简历）——
            // 这和实习僧那条同理：发错简历等于白投，而且投出去追不回来。
            if (config != null && config.getResumeMap() != null) {
                String resumeFile = config.getResumeMap().get(keyword == null ? "" : keyword.trim());
                if (resumeFile != null && !resumeFile.isBlank()) {
                    if (!switchDefaultAttachment(resumeFile)) {
                        String why = String.format(
                                "关键词「%s」的附件「%s」不在智联附件列表里，已跳过该关键词（不投旧简历）。"
                                        + "先跑 python zl_sync.py 补齐附件，再 python resume_rules.py --zl 核对",
                                keyword, resumeFile);
                        log.error(why);
                        sendProgress(why, null, null);
                        return;
                    }
                } else {
                    // 没配映射 = 不知道该发哪个方向，发下去就是撞运气（通常是旧简历）
                    String why = String.format(
                            "关键词「%s」没有配 resume_map，无法确定发哪份附件，已跳过（不投旧简历）",
                            keyword);
                    log.error(why);
                    sendProgress(why, null, null);
                    return;
                }
            }

            sendProgress("正在搜索关键词: " + keyword, null, null);

            // 直接拼搜索结果 URL，不再走「打开首页 → 搜索框输入 → Enter」。
            // 智联改版后，Enter 会跳到 /jobs/?jl=538&pageMode=search&kw=Python 这个旧版聚合页
            // （页面标题是“热门职位招聘”），里面根本没有 .joblist-box__item，
            // 于是每个关键词都会报“等待岗位列表超时”，整轮 0 投递。
            // 新版结果页是 /sou/?jl=<城市码>&kw=<关键词>，直接导航稳定得多。
            String searchUrl = resolveSearchUrl(keyword);
            log.info("搜索URL: {}", searchUrl);
            page.navigate(searchUrl);
            PlaywrightUtil.sleep(2);

            // 等待岗位列表加载。
            // 2026-11 改版后卡片类名从 joblist-box__item 换成了 job-card（左列表 + 右详情分栏），
            // 旧选择器在页面上一个都匹配不到，会一路超时到 0 投递。
            try {
                page.waitForSelector(".job-list-panel .job-card",
                    new Page.WaitForSelectorOptions().setTimeout(20_000));
            } catch (Exception e) {
                // 超时时把页面现场打出来：智联前端会自己把 /sou/ 重定向到 /jobs/，
                // 那个页面是纯客户端渲染，列表靠 XHR 异步拉。光看 html 的 head 判断不了
                // 到底是“没数据”“要登录”还是“被风控”，所以这里直接取可见文本和类名分布。
                String curUrl = "";
                String curTitle = "";
                String bodyText = "";
                String classSample = "";
                try { curUrl = page.url(); } catch (Exception ignore) {}
                try { curTitle = page.title(); } catch (Exception ignore) {}
                try {
                    bodyText = String.valueOf(page.evaluate(
                            "document.body ? document.body.innerText.replace(/\\s+/g,' ').slice(0,500) : ''"));
                } catch (Exception ignore) {}
                try {
                    classSample = String.valueOf(page.evaluate(
                            "Array.from(new Set(Array.from(document.querySelectorAll('[class]'))" +
                            ".map(e => typeof e.className === 'string' ? e.className : '')" +
                            ".filter(c => c.indexOf('job') >= 0 || c.indexOf('list') >= 0)))" +
                            ".slice(0,12).join(' | ')"));
                } catch (Exception ignore) {}
                log.warn("等待岗位列表超时，跳过当前关键词 | url={} | title={} | 可见文本={} | 相关类名={}",
                        curUrl, curTitle, bodyText, classSample);
                // 新版智联是 job-split-layout 分栏结构，把左侧列表第一张卡片的结构打出来，
                // 方便照着改选择器，不用反复猜。
                String cardHtml = "";
                try {
                    cardHtml = String.valueOf(page.evaluate(
                            "(function(){var el=document.querySelector('.job-list-panel');" +
                            "return el ? el.outerHTML.replace(/\\s+/g,' ').slice(0,1200) : 'NO_PANEL';})()"));
                } catch (Exception ignore) {}
                log.warn("列表面板结构: {}", cardHtml);

                // 新版是「左列表 + 右详情」，点第一张卡片看右侧结构，才知道投递按钮长什么样
                try {
                    page.evaluate("(function(){var c=document.querySelector('.job-list-panel .job-card'); if(c) c.click();})()");
                    PlaywrightUtil.sleep(3);
                    String rightHtml = String.valueOf(page.evaluate(
                            "(function(){var el=document.querySelector('.job-split-layout__right');" +
                            "return el ? el.outerHTML.replace(/\\s+/g,' ').slice(0,1500) : 'NO_RIGHT';})()"));
                    log.warn("右侧详情结构: {}", rightHtml);
                    String btnTexts = String.valueOf(page.evaluate(
                            "Array.from(document.querySelectorAll('button,a,div,span'))" +
                            ".filter(function(e){var t=(e.innerText||'').trim();" +
                            "return t.indexOf('投递')>=0 && t.length<=12 && e.children.length<=2;})" +
                            ".slice(0,8).map(function(e){return e.tagName+'.'" +
                            "+(typeof e.className==='string'?e.className:'')+'['+e.innerText.trim()+']';})" +
                            ".join(' || ')"));
                    log.warn("投递相关元素: {}", btnTexts);
                    String toolbar = String.valueOf(page.evaluate(
                            "(function(){var el=document.querySelector('.job-detail-summary__toolbar');" +
                            "return el ? el.outerHTML.replace(/data:image[^\"']+/g,'IMG').replace(/\\s+/g,' ').slice(0,1200) : 'NO_TOOLBAR';})()"));
                    log.warn("详情工具栏: {}", toolbar);
                } catch (Exception ex) {
                    log.warn("点击卡片取详情失败: {}", ex.getMessage());
                }
                return;
            }

            // 遍历所有页面：仅以“下一页”按钮禁用状态为主，最多50页
            int pageNum = 1;
            while (pageNum <= 50) {
                if (shouldStop() || isLimit) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return;
                }

                log.info("开始投递【{}】关键词，第【{}】页...", keyword, pageNum);
                sendProgress(String.format("正在投递第%d页", pageNum), pageNum, 50);

                // 等待岗位列表出现（新版类名 job-list-panel）
                try {
                    page.waitForSelector(".job-list-panel",
                        new Page.WaitForSelectorOptions().setTimeout(10_000));
                } catch (Exception e) {
                    log.warn("等待岗位列表失败，刷新页面重试");
                    page.reload();
                    PlaywrightUtil.sleep(1);
                }

                // 投递当前页面
                if (!deliverCurrentPage(keyword)) {
                    break;
                }

                PlaywrightUtil.sleep(2);

                // 判断是否还有下一页
                if (isNextDisabled()) {
                    log.info("下一页按钮不可点击，结束翻页");
                    break;
                }

                // 点击下一页
                Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
                if (nextBtn.count() > 0) {
                    try { nextBtn.first().scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    nextBtn.first().click();
                    PlaywrightUtil.sleep(2);
                    pageNum++;
                } else {
                    log.info("未找到下一页按钮，结束翻页");
                    break;
                }
            }

            log.info("关键词【{}】投递完成", keyword);
        } catch (Exception e) {
            log.error("投递关键词【{}】时出现异常", keyword, e);
        }
    }

    /**
     * 投递当前页面的所有职位
     * @return 是否继续投递下一页
     */
    private boolean deliverCurrentPage(String keyword) {
        try {
            page.waitForSelector(".job-list-panel .job-card",
                    new Page.WaitForSelectorOptions().setTimeout(15000));

            if (checkIsLimit()) {
                sendProgress("用户取消投递或已达上限", null, null);
                return false;
            }

            Locator cards = page.locator(".job-list-panel .job-card");
            int count = cards.count();
            log.info("检测到当前页岗位数量: {}", count);

            List<PageJob> jobs = new ArrayList<>();
            // 统一采集当前页岗位后再保存（不在循环中逐个入库）
            List<ZhilianJobDataEntity> toInsert = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }

                Locator card = cards.nth(i);
                // 新版卡片结构：岗位名在 .job-card__title-main 下的 .vue-clamp__text，
                // 薪资单独一个 span，学历/经验混在 .job-card__skill-tag 标签串里（位置不固定）。
                String jobTitle = normalizeText(safeGetText(card, ".job-card__title-main .vue-clamp__text"));
                if (jobTitle.isEmpty()) {
                    jobTitle = normalizeText(safeGetText(card, ".job-card__title-main"));
                }
                String salary = normalizeText(safeGetText(card, ".job-card__salary"));
                // 卡片里的公司名是带缩进换行的富文本，直接入库会得到「\n        某某公司\n      」
                String companyName = normalizeText(safeGetText(card, ".job-card__company a"));
                if (companyName.isEmpty()) {
                    companyName = normalizeText(safeGetText(card, ".job-card__company"));
                }
                String[] degreeAndExp = parseSkillTags(card);
                String degree = degreeAndExp[0];
                String experience = degreeAndExp[1];
                // 新版列表卡片不再直接给岗位详情链接，城市也不在卡片上
                String location = "";
                String jobLink = null;

                // 列表卡片没有可用的岗位 ID（只有公司页链接），用「岗位名+公司+薪资」生成稳定标识，
                // 足以支撑去重和投递状态回写
                String jobId = buildFallbackJobId(jobTitle, companyName, salary);

                try {
                    String jid = jobId == null ? "" : jobId.trim();
                    String jtitle = jobTitle == null ? "" : jobTitle.trim();
                    if (jid.isEmpty() || jtitle.isEmpty()) {
                        log.info("岗位缺少jobId或jobTitle，跳过采集：title={}，company={}", jtitle, companyName);
                    } else {
                        boolean exists = false;
                        try { exists = zhilianService.existsByJobId(jid); } catch (Exception checkEx) {
                            log.warn("查询jobId是否已存在失败: {}", checkEx.getMessage());
                        }
                        if (exists) {
                            log.info("jobId已存在，跳过采集：jobId={}，title={}", jid, jtitle);
                        } else {
                            ZhilianJobDataEntity entity = new ZhilianJobDataEntity();
                            entity.setJobId(jid);
                            entity.setJobTitle(jtitle);
                            entity.setJobLink(jobLink);
                            entity.setSalary(salary);
                            entity.setLocation(location);
                            entity.setExperience(experience);
                            entity.setDegree(degree);
                            entity.setCompanyName(companyName);
                            // 大厂岗位留给人工定制简历，不自动投
                            entity.setDeliveryStatus(
                                    com.getjobs.worker.utils.JobRelevanceFilter.isBigCompany(companyName)
                                            ? "待人工定制" : "未投递");
                            toInsert.add(entity);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("采集岗位数据失败: {}", ex.getMessage());
                }

                jobs.add(new PageJob(i, jobId, jobTitle, companyName, experience));
            }

            // 统一保存采集到的一整页岗位
            if (!toInsert.isEmpty()) {
                for (ZhilianJobDataEntity entity : toInsert) {
                    try {
                        zhilianService.insertJob(entity);
                        log.info("已保存岗位数据：jobId={}，title={}，company={}", entity.getJobId(), entity.getJobTitle(), entity.getCompanyName());
                    } catch (Exception ex) {
                        log.warn("保存岗位数据失败: {}", ex.getMessage());
                    }
                }
            }

            for (PageJob pj : jobs) {
                if (shouldStop()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }
                // 投满上限：不再点下一个「立即投递」
                if (deliverLimitReached()) {
                    return false;
                }
                // 大厂护栏：大厂岗位一律不自动打招呼，留给人工按 JD 定制简历后再投
                if (com.getjobs.worker.utils.JobRelevanceFilter.isBigCompany(pj.companyName)) {
                    log.info("跳过大厂岗位（留人工定制）：{}（{}）", pj.jobTitle, pj.companyName);
                    continue;
                }
                // 岗位方向不相关的直接跳过。11-10 起智联走专用规则：
                // 站内三份是方向级通用稿（后端/AI/测试），isRelevant 的裸词白名单
                // 会把算法研究、功能测试、产品策划等杂岗放进来；Boss / 51job 仍用旧规则。
                if (!com.getjobs.worker.utils.JobRelevanceFilter.isRelevantForZhilian(pj.jobTitle)) {
                    log.info("跳过无关岗位：{}（{}）", pj.jobTitle, pj.companyName);
                    continue;
                }
                // 经验要求超出应届范围的不投
                if (com.getjobs.worker.utils.JobRelevanceFilter.isExperienceTooHigh(pj.experience)) {
                    log.info("跳过经验不符岗位：{}（{}）要求 {}", pj.jobTitle, pj.companyName, pj.experience);
                    continue;
                }

                // 新版是「左列表 + 右详情」分栏：卡片上没有投递按钮，
                // 得先点左侧卡片把详情加载到右边，再点右侧的「立即投递」
                Locator card = page.locator(".job-list-panel .job-card").nth(pj.index);
                // 默认 30s 超时 × 卡片数 = 一轮白等十几分钟（实测投 3 个后后续卡片全点不动）。
                // 滚动改用 JS（不占 Playwright 超时），点击统一压到 5s，失败再退化到 JS click。
                try {
                    page.evaluate("(function(i){var c=document.querySelectorAll('.job-list-panel .job-card')[i];"
                            + "if(c){c.scrollIntoView({block:'center'});}})(" + pj.index + ")");
                    card.click(new Locator.ClickOptions().setTimeout(5_000));
                } catch (Exception clickCardEx) {
                    // 多半是上一轮投递弹窗的遮罩还盖着：先清遮罩再重试一次，别白白跳过岗位
                    boolean retried = false;
                    try {
                        clearDeliveryOverlay();
                        card.click(new Locator.ClickOptions().setTimeout(5_000));
                        retried = true;
                    } catch (Exception ignore) {
                    }
                    if (!retried) {
                        // 遮罩/虚拟滚动导致 locator 不可见时，直接对 DOM 派发 click
                        try {
                            Object hit = page.evaluate("(function(i){var c=document.querySelectorAll("
                                    + "'.job-list-panel .job-card')[i]; if(c){c.click(); return true;} return false;})("
                                    + pj.index + ")");
                            retried = Boolean.TRUE.equals(hit);
                        } catch (Exception ignored) {
                        }
                    }
                    if (!retried) {
                        log.info("岗位【{}】点击卡片失败，跳过: {}", pj.jobTitle, clickCardEx.getMessage());
                        continue;
                    }
                }
                PlaywrightUtil.sleep(2);

                Locator applyBtn = page.locator("button.job-detail-summary__apply");
                if (applyBtn.count() == 0) {
                    log.info("岗位【{}】未找到立即投递按钮，跳过", pj.jobTitle);
                    continue;
                }
                try {
                    // 点击前：注册监听器，统一关闭由当前页面打开的新窗口（弹出页）
                    java.util.function.Consumer<Page> closer = (Page newPage) -> {
                        try {
                            // 只关闭由当前 page 打开的子窗口，避免误伤
                            if (newPage.opener() == page) {
                                try { newPage.waitForLoadState(); } catch (Exception ignored) {}
                                try { PlaywrightUtil.sleep(200); } catch (Exception ignored) {}
                                try { newPage.close(); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    };
                    page.context().onPage(closer);

                    // 仅通过监听器捕捉并关闭由当前页打开的新窗口，避免与 waitForPopup 产生竞态
                    try {
                        applyBtn.click(); /* 点击投递按钮（关键定位注释：delivery-click-line）*/
                    } finally {
                        // 取消监听，避免影响后续流程
                        try { page.context().offPage(closer); } catch (Exception ignored) {}
                    }

                    // 智联点「立即投递」后必弹 deliver-greeting-modal：
                    //   成功：「已向对方发送简历和打招呼语」+ 按钮「留在此页 / 继续沟通」
                    //   注意「继续沟通」会跳去 IM 把投递循环打断，所以成功时只能点「留在此页」。
                    //   之前这里只认「确认投递/确定/继续投递」，一个都匹配不上 → 弹窗不关、
                    //   遮罩挡住下一张卡片 → 后续岗位全部点不动，还被记成「已投递」假成功。
                    // 用带超时的 waitForSelector 等弹窗：isVisible()/innerText() 这类调用没有超时，
                    // 页面一旦假死就会永久挂住整个 playwright-thread（连 /stop 都读不到 shouldStop）。
                    String modalTitle = "";
                    try {
                        page.waitForSelector(".deliver-greeting-modal__title",
                                new Page.WaitForSelectorOptions()
                                        .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                                        .setTimeout(10_000));
                        modalTitle = page.locator(".deliver-greeting-modal__title").first()
                                .innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(3_000))
                                .trim();
                    } catch (Exception e) {
                        log.info("未检测到投递结果弹窗（可能未投出去）: {}", e.getMessage());
                    }

                    boolean applied = modalTitle.contains("已向对方发送简历");
                    if (applied) {
                        // 留在此页：继续投下一家（「继续沟通」会跳去 IM，绝对不点）
                        try {
                            page.locator(".deliver-greeting-modal__btn--secondary").first()
                                    .click(new Locator.ClickOptions().setTimeout(5_000));
                            PlaywrightUtil.sleep(1);
                        } catch (Exception ignore) {
                        }
                        try {
                            if (pj.jobId != null && !pj.jobId.isEmpty()) {
                                zhilianService.markDeliveredByJobId(pj.jobId);
                            } else if (pj.jobTitle != null && pj.companyName != null) {
                                zhilianService.markDeliveredByTitleAndCompany(pj.jobTitle, pj.companyName);
                            }
                            log.info("已标记投递：{}（{}）", pj.jobTitle, pj.companyName);
                        } catch (Exception ex) {
                            log.warn("更新投递状态失败: {}", ex.getMessage());
                        }
                        deliveredTotal++;
                        Integer maxLimit = config != null ? config.getMaxDeliverCount() : null;
                        if (maxLimit != null && maxLimit > 0) {
                            sendProgress("已投递 " + deliveredTotal + " / " + maxLimit + " 家：" + pj.jobTitle,
                                    deliveredTotal, maxLimit);
                        }
                        // 实测原本约 16 秒一个岗位，和触发 Boss 风控的节奏同级；
                        // 加 20–40 秒随机间隔，投满上限就不必再等
                        if (maxLimit == null || maxLimit <= 0 || deliveredTotal < maxLimit) {
                            int wait = 20 + java.util.concurrent.ThreadLocalRandom.current().nextInt(21);
                            log.info("等待 {} 秒后继续下一个岗位", wait);
                            PlaywrightUtil.sleep(wait);
                        }
                    } else {
                        // 弹窗文案不是「已发送」= 没投出去（需选简历/已投过/条件不符）。
                        // 关掉弹窗继续下一个，千万不要标记成功，否则会凭假数据判断进度。
                        // 只点 close 不够：实测「无弹窗」时遮罩仍在，后面每张卡片都会点不动，
                        // 所以统一走 clearDeliveryOverlay 把遮罩清干净。
                        log.warn("投递未成功【{}】，弹窗文案：{}", pj.jobTitle,
                                modalTitle.isEmpty() ? "(无弹窗)" : modalTitle);
                        clearDeliveryOverlay();
                    }
                } catch (Exception clickEx) {
                    log.warn("投递失败，继续下一个岗位: {}", clickEx.getMessage());
                }

                if (checkIsLimit()) {
                    sendProgress("用户取消投递或已达上限", null, null);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("投递当前页面失败", e);
            try {
                saveCurrentPageHtml();
                log.info("已保存当前页面到 src/main/java/com/getjobs/worker/zhilian/page.html 以便排查");
            } catch (Exception saveEx) {
                log.warn("保存当前页面HTML失败: {}", saveEx.getMessage());
            }
            return false;
        }
    }

    /**
     * 清掉投递弹窗与遮罩。
     * 「立即投递」没弹出预期对话框时（需选简历/已投过/条件不符），页面上常留一层遮罩；
     * 只点 close 按钮清不掉它，后续所有 .job-card 都会因为被遮挡而点击超时。
     */
    private void clearDeliveryOverlay() {
        try {
            page.locator(".deliver-greeting-modal__close").first()
                    .click(new Locator.ClickOptions().setTimeout(2_000));
        } catch (Exception ignored) {
        }
        try {
            page.keyboard().press("Escape");
        } catch (Exception ignored) {
        }
        try {
            page.evaluate("(function(){document.querySelectorAll("
                    + "'.deliver-greeting-modal,[class*=modal__mask],[class*=el-dialog__wrapper],"
                    + "[class*=overlay],[class*=mask]').forEach(function(e){e.remove();});"
                    + "document.body.style.overflow='';})()");
        } catch (Exception ignored) {
        }
        PlaywrightUtil.sleep(1);
    }

    /**
     * 处理投递弹窗
     */
    private void handleDeliveryDialog() {
        try {
            // 获取所有页面
            List<Page> pages = page.context().pages();
            if (pages.size() < 2) {
                log.warn("未检测到投递弹窗页面");
                return;
            }

            // 切换到最新打开的页面（投递弹窗）
            Page dialogPage = pages.get(pages.size() - 1);

            try {
                // 检查投递结果（CSS选择器）
                Locator deliverResult = dialogPage.locator("div.deliver-dialog");
                if (deliverResult.count() > 0) {
                    String text = deliverResult.textContent();
                    if (text != null && text.contains("申请成功")) {
                        log.info("岗位申请成功！");
                    }
                }
            } catch (Exception e) {
                log.debug("读取投递结果失败: {}", e.getMessage());
            }

            // 关闭弹窗
            try {
                Locator closeButton = dialogPage.locator("img[title='close-icon']");
                if (closeButton.count() > 0) {
                    closeButton.click();
                    PlaywrightUtil.sleep(1);
                }
            } catch (Exception e) {
                log.debug("关闭投递弹窗失败: {}", e.getMessage());
                if (checkIsLimit()) {
                    return;
                }
            }

            // 投递相似职位：默认关闭。开了会「全选」一次性批量投掉弹窗里所有相似岗位，
            // 数量不可控且不做经验/学历过滤，与「精确投 N 家」和「只投应届岗」都冲突。
            if (DELIVER_SIMILAR_JOBS) {
                deliverSimilarJobs(dialogPage);
            } else {
                log.debug("已跳过相似职位批量投递（DELIVER_SIMILAR_JOBS=false）");
            }

            // 关闭弹窗页面
            try {
                dialogPage.close();
            } catch (Exception e) {
                log.debug("关闭弹窗页面失败: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("处理投递弹窗失败", e);
        }
    }

    /**
     * 投递相似职位
     */
    private void deliverSimilarJobs(Page dialogPage) {
        try {
            // 全选相似职位
            Locator selectAllCheckbox = dialogPage.locator("div.applied-select-all input");
            if (selectAllCheckbox.count() > 0 && !selectAllCheckbox.isChecked()) {
                selectAllCheckbox.click();
                PlaywrightUtil.sleep(1);
            }

            // 获取相似职位列表
            Locator jobs = dialogPage.locator("div.recommend-job");
            int jobCount = jobs.count();

            if (jobCount == 0) {
                log.info("没有匹配到相似职位");
                return;
            }

            // 记录相似职位信息
            for (int i = 0; i < jobCount; i++) {
                try {
                    Locator jobElement = jobs.nth(i);
                    String jobName = safeGetText(jobElement, ".recommend-job__position");
                    String salary = safeGetText(jobElement, "span.recommend-job__demand__salary");
                    String years = safeGetText(jobElement, "span.recommend-job__demand__experience").replace("\n", " ");
                    String education = safeGetText(jobElement, "span.recommend-job__demand__educational").replace("\n", " ");
                    String companyName = safeGetText(jobElement, ".recommend-job__cname");
                    String companyTag = safeGetText(jobElement, ".recommend-job__demand__cinfo").replace("\n", " ");

                    Job job = new Job();
                    job.setJobName(jobName);
                    job.setSalary(salary);
                    job.setCompanyTag(companyTag);
                    job.setCompanyName(companyName);
                    job.setJobInfo(years + "·" + education);

                    log.info("投递【{}】公司【{}】岗位，薪资【{}】，要求【{}·{}】，规模【{}】",
                        companyName, jobName, salary, years, education, companyTag);
                    resultList.add(job);
                } catch (Exception e) {
                    log.debug("记录相似职位信息失败: {}", e.getMessage());
                }
            }

            // 点击投递按钮
            Locator postButton = dialogPage.locator("div.applied-select-all button");
            if (postButton.count() > 0) {
                postButton.click();
                PlaywrightUtil.sleep(2);
                log.info("相似职位投递成功！");
            }

        } catch (Exception e) {
            log.error("投递相似职位异常: {}", e.getMessage());
        }
    }

    /**
     * 检查是否达到投递上限
     */
    private boolean checkIsLimit() {
        try {
            PlaywrightUtil.sleep(1);
            Locator result = page.locator("//div[@class='a-job-apply-workflow']");
            if (result.count() > 0) {
                String text = result.textContent();
                if (text != null && text.contains("达到上限")) {
                    log.info("今日投递已达上限！");
                    isLimit = true;
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置最大页数（已废弃：使用“下一页不可点击”+最多50页）
     */
    private void setMaxPages() {
        // 保留方法避免旧调用报错，但不再依赖输入框改页码
        maxPage = 50;
    }

    /**
     * 构建基础搜索URL（不含关键词，由搜索框触发）
     */
    private String buildBaseUrl(int pageNum) {
        StringBuilder url = new StringBuilder(HOME_URL);
        url.append("jl").append(config.getCityCode()).append("/");
        url.append("p").append(pageNum).append("?");
        url.append(JobUtils.appendParam("sl", config.getSalary()));
        return url.toString();
    }

    /**
     * 构造新版搜索结果页 URL：{@code https://www.zhaopin.com/sou/?jl=<城市码>&kw=<关键词>&sl=<薪资>}
     * <p>
     * 老代码用的是 {@code /sou/jl538/p1?sl=...} 这种路径式地址，新版智联已经不再识别，
     * 必须走查询参数形式才能直接落到结果列表。
     *
     * @param keyword 搜索关键词
     */
    private String buildSearchUrl(String keyword) {
        StringBuilder url = new StringBuilder("https://www.zhaopin.com/sou/?");
        url.append("jl=").append(config.getCityCode());
        url.append("&kw=").append(urlEncode(keyword));
        String salary = config.getSalary();
        // 0 表示不限，附加到 URL 上没意义
        if (salary != null && !salary.trim().isEmpty() && !"0".equals(salary.trim())) {
            url.append("&sl=").append(salary.trim());
        }
        return url.toString();
    }

    /**
     * 解析出真正可用的搜索结果页 URL。
     * <p>
     * 踩坑记录：新版权智联有三套地址，只有一套能用。
     * <ul>
     *   <li>{@code /jobs/?jl=&kw=}：旧版聚合页，标题是“热门职位招聘”，<b>一条岗位卡片都没有</b></li>
     *   <li>{@code /sou/?jl=&kw=}：服务端能直出岗位列表，但浏览器里 JS 挂载后会被重定向到上面那个 /jobs/</li>
     *   <li>{@code /sou/jl538/kw01800U80EG06G03F01N0}：前端认可的规范路由，正常渲染</li>
     * </ul>
     * 规范地址里的 {@code kw...} 是智联自己编的关键词哈希，只能靠 {@code sou.zhaopin.com}
     * 的 301 换出来，没法本地拼。所以先用一次 HTTP 探测拿 Location，再交给 Playwright 导航。
     *
     * @param keyword 搜索关键词
     * @return 规范搜索 URL；探测失败时回退到查询参数形式
     */
    private String resolveSearchUrl(String keyword) {
        String probe = "https://sou.zhaopin.com/?jl=" + config.getCityCode() + "&kw=" + urlEncode(keyword);
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(probe))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String loc = resp.headers().firstValue("location").orElse(null);
            if (loc != null && loc.contains("/sou/")) {
                String salary = config.getSalary();
                if (salary != null && !salary.trim().isEmpty() && !"0".equals(salary.trim()) && !loc.contains("sl=")) {
                    loc = loc + (loc.contains("?") ? "&" : "?") + "sl=" + salary.trim();
                }
                log.info("关键词【{}】规范搜索URL: {}", keyword, loc);
                return loc;
            }
            log.warn("探测未拿到规范URL（HTTP {}），回退查询参数形式", resp.statusCode());
        } catch (Exception e) {
            log.warn("探测规范搜索URL失败，回退查询参数形式: {}", e.getMessage());
        }
        return buildSearchUrl(keyword);
    }

    private String urlEncode(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 查找搜索关键词输入框（多候选选择器，提高鲁棒性）
     */
    private Locator findKeywordInput() {
        String[] candidates = new String[] {
            "input[placeholder*='职位']",
            "input[placeholder*='公司']",
            "input[name='kw']",
            "input[type='text']",
            "input[class*='search'], input[class*='sou'], input[class*='input']"
        };
        for (String sel : candidates) {
            try {
                Locator lc = page.locator(sel);
                if (lc != null && lc.count() > 0) {
                    return lc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 判断“下一页”是否不可点击
     */
    private boolean isNextDisabled() {
        try {
            Locator nextBtn = page.locator("a.soupager__btn:has-text(\"下一页\")");
            if (nextBtn.count() == 0) return true;
            String cls = null;
            try { cls = nextBtn.first().getAttribute("class"); } catch (Exception ignored) {}
            String disabledAttr = null;
            try { disabledAttr = nextBtn.first().getAttribute("disabled"); } catch (Exception ignored) {}
            if (cls != null && cls.contains("soupager__btn--disable")) return true;
            return disabledAttr != null && ("disabled".equalsIgnoreCase(disabledAttr) || "true".equalsIgnoreCase(disabledAttr));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 富文本节点取出的文本常带缩进和换行（尤其是公司名），统一压成单行。
     */
    private String normalizeText(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" {2,}", " ").trim();
    }

    /**
     * 从新版卡片的技能标签串里识别学历和经验。
     * <p>
     * 标签顺序不固定（本科 / 1-3年 / Python / Transformer / 人工智能 …），
     * 所以按内容判断，不能按 nth-child 位置取。
     *
     * @return 长度为 2 的数组：[学历, 经验]，识别不到时对应位置为空串
     */
    private String[] parseSkillTags(Locator card) {
        String degree = "";
        String experience = "";
        try {
            Locator tags = card.locator(".job-card__skill-tag");
            int n = tags.count();
            for (int i = 0; i < n; i++) {
                String t = null;
                try { t = tags.nth(i).textContent(); } catch (Exception ignore) {}
                if (t == null) continue;
                t = t.trim();
                if (t.isEmpty()) continue;
                if (degree.isEmpty() && (t.contains("本科") || t.contains("大专") || t.contains("硕士")
                        || t.contains("博士") || t.contains("中专") || "不限".equals(t))) {
                    degree = t;
                } else if (experience.isEmpty() && t.contains("年")) {
                    experience = t;
                }
            }
        } catch (Exception ignore) {}
        return new String[]{degree, experience};
    }

    /**
     * 新版列表卡片不再暴露岗位 ID（只有公司页链接），用岗位名+公司+薪资生成稳定的去重标识，
     * 足以支撑 existsByJobId 去重和投递状态回写。
     */
    private String buildFallbackJobId(String title, String company, String salary) {
        String raw = (title == null ? "" : title.trim()) + "|"
                + (company == null ? "" : company.trim()) + "|"
                + (salary == null ? "" : salary.trim());
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("zl_");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "zl_" + Integer.toHexString(raw.hashCode());
        }
    }

    private String extractJobIdFromLink(String link) {
        if (link == null) return null;
        try {
            int i = link.indexOf("jobdetail/");
            int j = link.lastIndexOf(".htm");
            if (i >= 0 && j > i) {
                return link.substring(i + "jobdetail/".length(), j);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 安全获取文本内容
     */
    private String safeGetText(Locator parent, String selector) {
        try {
            Locator element = parent.locator(selector);
            if (element.count() > 0) {
                return element.textContent();
            }
        } catch (Exception e) {
            log.debug("获取文本失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 格式化时长
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }

    /**
     * 将当前页面内容保存到项目内 page.html，覆盖原文件
     */
    private void saveCurrentPageHtml() {
        try {
            String html = page.content();
            java.nio.file.Path path = java.nio.file.Paths.get("src/main/java/com/getjobs/worker/zhilian/page.html");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("写入 page.html 失败", e);
        }
    }

    /**
     * 发送进度消息
     */
    private void sendProgress(String message, Integer current, Integer total) {
        if (progressCallback != null) {
            progressCallback.accept(message, current, total);
        }
    }

    /**
     * 检查是否应该停止
     */
    private boolean shouldStop() {
        return shouldStopCallback != null && shouldStopCallback.get();
    }
}

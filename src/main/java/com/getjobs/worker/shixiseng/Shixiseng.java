package com.getjobs.worker.shixiseng;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.entity.ShixisengJobDataEntity;
import com.getjobs.application.service.ShixisengFontMap;
import com.getjobs.application.service.ShixisengService;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 实习僧岗位采集器。
 * <p>
 * 关键点：实习僧的列表数据不像 Boss 那样需要点卡片解析 DOM，而是整块塞在页面的
 * {@code window.__NUXT__} 里（Nuxt SSR 状态树）。浏览器执行完脚本后这个对象已经求值，
 * 直接 {@code JSON.stringify} 出来就是结构化数据——不用登录、不用解析 HTML、不用逐条点击。
 * <p>
 * 两个坑：
 * <ol>
 *   <li>岗位名里的常用字被替换成私有区字符（字体加密），列表页拿到的是乱码。
 *       详情页是明文且与列表名等长，所以用详情页反推映射后交给
 *       {@link ShixisengFontMap} 缓存，后续不再重复抓详情。</li>
 *   <li>公司规模、每周天数、实习月数同样是加密字段，且详情页也可能加密，
 *       这几项一律不采集，不做猜测。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Shixiseng {

    private static final String SEARCH_URL =
            "https://www.shixiseng.com/interns?keyword=%s&city=%s&page=%d&type=intern";
    private static final String DETAIL_URL = "https://www.shixiseng.com/intern/%s";

    /** 单次采集最多为多少条岗位补抓详情页（每条约 1-2 秒，避免采集过慢） */
    private static final int DETAIL_BUDGET = 40;

    private final ShixisengService shixisengService;
    private final ShixisengFontMap fontMap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 进度回调：文案, 当前, 总数 */
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    /** 本次采集结果 */
    public static class CollectResult {
        public int scanned;   // 扫到的岗位数（含重复）
        public int inserted;  // 新入库数
        public int pages;     // 实际抓取页数
        public int detailFetched; // 为还原标题抓了多少详情页
    }

    /**
     * 按关键词逐页采集。
     *
     * @param keywords 关键词列表，空则用默认值
     * @param city     城市中文名，空则全国
     * @param maxPages 每个关键词最多抓几页（1 页约 15 条）
     */
    public CollectResult collect(Page page, List<String> keywords, String city, int maxPages,
                                 ProgressCallback callback) {
        CollectResult result = new CollectResult();
        List<String> kws = (keywords == null || keywords.isEmpty()) ? List.of("Python") : keywords;
        String cityParam = (city == null || city.trim().isEmpty()) ? "全国" : city.trim();
        int pages = Math.max(1, Math.min(maxPages, 10));
        int detailBudget = DETAIL_BUDGET;

        Page detailPage = null;
        try {
            detailPage = page.context().newPage();
            int keywordIndex = 0;
            for (String kw : kws) {
                keywordIndex++;
                for (int p = 1; p <= pages; p++) {
                    if (callback != null) {
                        callback.accept(String.format("采集「%s」第 %d 页", kw, p), keywordIndex, kws.size());
                    }
                    try {
                        String url = String.format(SEARCH_URL, urlEncode(kw), urlEncode(cityParam), p);
                        log.info("实习僧采集：{}", url);
                        page.navigate(url, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(30000));
                        try {
                            page.waitForFunction("() => window.__NUXT__ !== undefined",
                                    null, new Page.WaitForFunctionOptions().setTimeout(8000));
                        } catch (Exception ignore) {
                            log.warn("等待 __NUXT__ 超时，尝试直接读取");
                        }
                        PlaywrightUtil.sleep(1);

                        JsonNode jobs = readJobs(page);
                        result.pages++;
                        if (jobs == null || jobs.size() == 0) {
                            log.info("「{}」第 {} 页无岗位，换下一个关键词", kw, p);
                            break;
                        }
                        result.scanned += jobs.size();
                        for (JsonNode job : jobs) {
                            try {
                                String uuid = text(job, "uuid");
                                if (uuid == null || uuid.isEmpty()) continue;
                                String rawTitle = trimOrNull(text(job, "name"));
                                String title = fontMap.apply(rawTitle);

                                // 还有没见过的加密字符时，抓一次详情页拿明文，顺带学习映射
                                if (fontMap.hasUnknown(title) && detailBudget > 0) {
                                    detailBudget--;
                                    result.detailFetched++;
                                    String plain = fetchDetailTitle(detailPage, uuid);
                                    if (plain != null && !plain.isEmpty()) {
                                        int learned = fontMap.learn(rawTitle, plain);
                                        if (learned > 0) fontMap.save();
                                        title = plain;
                                    }
                                }

                                ShixisengJobDataEntity entity = toEntity(job, uuid, title);
                                if (entity != null && shixisengService.insertJob(entity)) {
                                    result.inserted++;
                                }
                            } catch (Exception e) {
                                log.debug("单条岗位解析失败：{}", e.getMessage());
                            }
                        }
                        log.info("「{}」第 {} 页：{} 条，累计新增 {} 条", kw, p, jobs.size(), result.inserted);
                    } catch (Exception e) {
                        log.warn("采集「{}」第 {} 页失败：{}", kw, p, e.getMessage());
                        break;
                    }
                    PlaywrightUtil.sleep(2);
                }
            }
        } finally {
            if (detailPage != null) {
                try {
                    detailPage.close();
                } catch (Exception ignore) {
                }
            }
        }
        return result;
    }

    /** 从页面取出 interns.data 数组 */
    private JsonNode readJobs(Page page) {
        Object raw = page.evaluate("() => { try { return JSON.stringify(window.__NUXT__ || null); } catch (e) { return null; } }");
        if (raw == null) return null;
        try {
            JsonNode nuxt = objectMapper.readTree(raw.toString());
            if (nuxt == null || nuxt.isNull()) return null;
            JsonNode data = nuxt.path("data");
            for (JsonNode block : data) {
                JsonNode interns = block.path("interns");
                if (interns.isMissingNode()) continue;
                JsonNode list = interns.path("data");
                if (list.isArray()) return list;
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 __NUXT__ 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 抓详情页的明标题。详情页没有字体加密，{@code state.internInfo.iname} 即完整岗位名。
     *
     * @return 明文标题；失败返回 null
     */
    private String fetchDetailTitle(Page detailPage, String uuid) {
        try {
            detailPage.navigate(String.format(DETAIL_URL, uuid), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(25000));
            PlaywrightUtil.sleep(1);
            Object raw = detailPage.evaluate(
                    "() => { try { return JSON.stringify(window.__NUXT__ || null); } catch (e) { return null; } }");
            if (raw == null) return null;
            JsonNode nuxt = objectMapper.readTree(raw.toString());
            String iname = nuxt.path("state").path("internInfo").path("iname").asText("");
            if (iname.isEmpty()) {
                iname = nuxt.path("data").path(0).path("msg").path("iname").asText("");
            }
            return iname.isEmpty() ? null : fontMap.stripUnknown(trimOrNull(iname));
        } catch (Exception e) {
            log.debug("抓详情页标题失败（{}）：{}", uuid, e.getMessage());
            return null;
        }
    }

    private ShixisengJobDataEntity toEntity(JsonNode job, String uuid, String title) {
        ShixisengJobDataEntity e = new ShixisengJobDataEntity();
        e.setJobId(uuid);
        e.setJobTitle(fontMap.stripUnknown(title));
        e.setCompanyName(fontMap.stripUnknown(trimOrNull(text(job, "cname"))));
        e.setCity(fontMap.stripUnknown(trimOrNull(text(job, "city"))));
        e.setDegree(fontMap.stripUnknown(trimOrNull(text(job, "degree"))));
        e.setIndustry(fontMap.stripUnknown(trimOrNull(text(job, "industry"))));
        e.setRefreshTime(fontMap.stripUnknown(trimOrNull(text(job, "refresh"))));
        e.setSalary(buildSalary(job));
        e.setTags(buildTags(job));
        e.setJobLink("https://www.shixiseng.com/intern/" + uuid);
        e.setDeliveryStatus("未投递");
        return e;
    }

    /**
     * 薪资：用 minsalary/maxsalary 明文数值（元/天）。
     * 字体加密的 minsal/maxsal 是展示文本，解出来是乱码，不用。
     */
    private String buildSalary(JsonNode job) {
        int min = intValue(job, "minsalary");
        int max = intValue(job, "maxsalary");
        if (min <= 0 && max <= 0) return "面议";
        if (min > 0 && max > 0) {
            return min == max ? min + "元/天" : min + "-" + max + "元/天";
        }
        return (min > 0 ? min : max) + "元/天";
    }

    private String buildTags(JsonNode job) {
        List<String> tags = new ArrayList<>();
        for (String field : new String[]{"i_tags", "c_tags"}) {
            JsonNode arr = job.path(field);
            if (arr.isArray()) {
                for (JsonNode t : arr) {
                    String v = fontMap.stripUnknown(trimOrNull(t.asText("")));
                    if (v == null || v.isEmpty()) continue;
                    if (!tags.contains(v)) tags.add(v);
                }
            }
        }
        return String.join("、", tags);
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private int intValue(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return 0;
        if (v.isNumber()) return v.asInt();
        try {
            return Integer.parseInt(v.asText().trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 只做 trim。
     * 注意：字体加密 token（&#xf069 这种）必须原样保留，否则就没法再对它做映射还原了。
     */
    private String trimOrNull(String s) {
        if (s == null) return null;
        String out = s.trim();
        return out.isEmpty() ? null : out;
    }

    private String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 实习僧投递结果。
     * <p>stage 取值：clicked / dialog / selected / confirmed / verified /
     * already（已投过）/ need-login（未登录）/ no-apply-btn（没找到投个简历）/
     * no-dialog（点了没弹窗）/ no-confirm（弹窗里没确认按钮）/ error。</p>
     */
    public static class ApplyResult {
        public boolean success;
        public String stage;
        public String message;
        public String jobId;
        public String dialogHtml;   // 弹窗/页面快照，便于现场排查
        public boolean hasMessageField; // 弹窗里是否出现留言/备注输入框（用于招呼语优化判断）
    }

    /**
     * 投递一个实习僧岗位（需要先登录）。
     * <p>
     * 流程依据开源实现（xiaweiyi713/zhipin-auto-apply 的 shixiseng_auto_apply.py）确认：
     * 点「投个简历」(.resume_apply) → 等 .deliver-dialog-box → 选 .user-resume（含附件简历字样）
     * → 点「确认投递」(.common-deliver__footer .btn 或文本恰为「确认投递」的 div.btn)。
     * 这里用 page.evaluate 跑选择器逻辑，避免 Playwright 严格动作在隐藏/动画元素上超时。
     *
     * @param page          实习僧标签页（复用共享 BrowserContext）
     * @param jobId         岗位 uuid，如 inn_xxxx
     * @param resumeKeyword 在弹窗简历列表里要选中的关键字（一般为附件简历的命名片段），空则选「附件简历」
     * @param message       投递弹窗里的留言/备注（实习僧多数没有，有则填入）；可空
     * @param dryRun        true 时走到「定位到确认按钮」就停，不真正点击，用于上线前验证弹窗结构
     * @return 投递结果（含快照）
     */
    public ApplyResult apply(Page page, String jobId, String resumeKeyword, String message, boolean dryRun) {
        ApplyResult r = new ApplyResult();
        r.jobId = jobId;
        try {
            String url = String.format(DETAIL_URL, jobId);
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
            // 等详情渲染（Nuxt SSR 很快，但投个简历按钮依赖客户端 hydration）
            try {
                page.waitForSelector(".resume_apply, .job-box, body",
                        new Page.WaitForSelectorOptions().setTimeout(8_000));
            } catch (Exception ignore) {
            }
            PlaywrightUtil.sleep(2);

            // 1) 点「投个简历」，同时判断是否已经投过 / 未登录
            String clickRes = page.evaluate("(() => {" +
                    " const el = document.querySelector('.btn-box.resume_apply') || document.querySelector('.resume_apply');" +
                    " if (!el) return 'no-btn';" +
                    " const t = (el.innerText||'').trim();" +
                    " if (t.includes('已投递') || t.includes('已申请')) return 'already';" +
                    " el.click(); return 'clicked';" +
                    "})()").toString();
            if ("no-btn".equals(clickRes)) {
                String body = page.evaluate(
                        "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,600):''").toString();
                if (body.contains("登录") && (body.contains("注册") || body.contains("扫码"))) {
                    r.stage = "need-login";
                    r.message = "实习僧未登录，请先在浏览器登录后再投递";
                    return r;
                }
                r.stage = "no-apply-btn";
                r.message = "未找到「投个简历」按钮，页面结构可能已变化";
                r.dialogHtml = body;
                return r;
            }
            if ("already".equals(clickRes)) {
                r.stage = "already";
                r.success = true;
                r.message = "该岗位此前已投递";
                return r;
            }
            r.stage = "clicked";

            // 2) 等投递弹窗
            PlaywrightUtil.sleep(2);
            String dialogState = page.evaluate("(() => {" +
                    " const box = document.querySelector('.deliver-dialog-box');" +
                    " if (!box) return 'no-dialog';" +
                    // 注意：.deliver-dialog-box 是 position:fixed 弹层，fixed 元素的 offsetParent 恒为 null，
                    // 之前用 offsetParent 判断可见性会永远误判成 hidden。改用 display/visibility + 包围盒尺寸。
                    " const cs = getComputedStyle(box);" +
                    " if (cs.display==='none' || cs.visibility==='hidden') return 'hidden';" +
                    " const rc = box.getBoundingClientRect();" +
                    " return (rc.width>0 && rc.height>0) ? 'shown' : 'hidden';" +
                    "})()").toString();
            if (!"shown".equals(dialogState)) {
                r.stage = "no-dialog";
                r.dialogHtml = page.evaluate(
                        "document.querySelector('.deliver-dialog-box') ? document.querySelector('.deliver-dialog-box').outerHTML.slice(0,2000) : document.body.innerText.replace(/\\s+/g,' ').slice(0,800)")
                        .toString();
                r.message = "点击投个简历后未出现投递弹窗（" + dialogState + "）";
                return r;
            }
            r.stage = "dialog";

            // 3) 留言框：只把 <textarea> 当留言框。
            //    注意 el-select 的 input（「到岗时间」下拉）不是留言框，之前误当留言框会写坏到岗时间。
            r.hasMessageField = Boolean.parseBoolean(page.evaluate(
                    "!!document.querySelector('.deliver-dialog-box textarea')").toString());
            if (r.hasMessageField && message != null && !message.isBlank()) {
                page.evaluate("((msg) => {" +
                        " const f = document.querySelector('.deliver-dialog-box textarea');" +
                        " if (!f) return;" +
                        " const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;" +
                        " setter.call(f, msg);" +
                        " f.dispatchEvent(new Event('input', {bubbles:true}));" +
                        "})(" + jsString(message) + ")");
            }

            // 3.5) 到岗时间（Element UI el-select，通常必填）：为空则用 DOM click 直接点选一项。
            //      下拉是 display:none 的预渲染列表，普通 Playwright click 会被 readonly 输入层拦截，改用 DOM click（Vue 事件照常触发）。
            String startDate = page.evaluate("(() => {" +
                    " const box = document.querySelector('.deliver-dialog-box');" +
                    " if (!box) return 'no-box';" +
                    " const sel = box.querySelector('.el-select');" +
                    " if (!sel) return 'no-select';" +
                    " const inp = sel.querySelector('input');" +
                    " const cur = inp ? (inp.value||'') : '';" +
                    " if (cur && !cur.includes('请选择')) return 'has:'+cur;" +
                    " const items = Array.from(sel.querySelectorAll('.el-select-dropdown__item'));" +
                    " if (!items.length) return 'no-opt';" +
                    " const t = items.find(e => /随时|1周|一周|立即/.test(e.innerText||'')) || items[0];" +
                    " t.click();" +
                    " return 'picked:'+(t.innerText||'').trim();" +
                    "})()").toString();

            // 4) 选「附件简历」→ 等重渲染 → 校验确实选中 → 点确认投递（dryRun 只看不点）
            //    坑1：实习僧的单选只监听 mousedown/mouseup，纯 el.click() 不会切换，
            //         之前 attach.click() 一直是无效操作，实际投出去的是默认勾选的「在线简历」。
            //    坑2：点完图标要等 Vue 重渲染，同一帧读选中态会读到旧值，所以拆成「点 → 等 → 校验」。
            String pickRes = page.evaluate("(() => {" +
                    " const box = document.querySelector('.deliver-dialog-box');" +
                    " if (!box) return 'no-box';" +
                    " const label = (e) => ((e.querySelector('.user-resume__left')||{}).innerText||'');" +
                    " const attach = Array.from(box.querySelectorAll('.user-resume'))" +
                    "   .find(e => label(e).includes('附件简历'));" +
                    " if (!attach) return 'no-attach';" +
                    " const radio = attach.querySelector('.right-item__img img')" +
                    "   || attach.querySelector('.right-item__img') || attach;" +
                    " ['mousedown','mouseup','click'].forEach(function(t){" +
                    "   radio.dispatchEvent(new MouseEvent(t, {bubbles:true, cancelable:true, view:window}));" +
                    " });" +
                    " return 'picked';" +
                    "})()").toString();
            if ("no-attach".equals(pickRes) || "no-box".equals(pickRes)) {
                r.stage = "no-attach";
                r.message = "投递弹窗中没有「附件简历」选项（" + pickRes + "），已中止";
                return r;
            }
            PlaywrightUtil.sleep(1);

            String confirmRes = page.evaluate("((dry) => {" +
                    " const box = document.querySelector('.deliver-dialog-box');" +
                    " if (!box) return 'no-box';" +
                    " const label = (e) => ((e.querySelector('.user-resume__left')||{}).innerText||'');" +
                    // 选中态看图标：radio-choosed.png 才是已选
                    " const chosen = Array.from(box.querySelectorAll('.user-resume')).filter(function(e){" +
                    "   const img = e.querySelector('.right-item__img img');" +
                    "   return img && (img.getAttribute('src')||'').indexOf('radio-choosed') >= 0;" +
                    " }).map(label).join('|');" +
                    " if (chosen.indexOf('附件') < 0) return 'attach-not-selected:' + (chosen || 'none');" +
                    " const ok = box.querySelector('.common-deliver__footer .btn')" +
                    "   || Array.from(box.querySelectorAll('div.btn')).find(e => (e.innerText||'').trim()==='确认投递');" +
                    " if (!ok) return 'no-confirm';" +
                    " if (dry) return 'dryrun-attach';" +
                    " ok.click(); return 'confirmed';" +
                    "})(" + (dryRun ? "true" : "false") + ")").toString();
            r.dialogHtml = page.evaluate(
                    "document.querySelector('.deliver-dialog-box') ? document.querySelector('.deliver-dialog-box').outerHTML.replace(/\\s+/g,' ').slice(0,6000) : ''")
                    .toString();
            if ("no-confirm".equals(confirmRes)) {
                r.stage = "no-confirm";
                r.message = "弹窗中未找到「确认投递」按钮";
                return r;
            }
            // 附件没选中（或弹窗里没有附件项）：一律不投，避免把在线简历发出去
            if (confirmRes.startsWith("attach-not-selected")) {
                r.stage = "attach-not-selected";
                r.message = "附件简历未被选中（当前选中：" + confirmRes + "），已中止，未投递";
                return r;
            }
            if ("no-attach".equals(confirmRes) || "no-box".equals(confirmRes)) {
                r.stage = "no-attach";
                r.message = "投递弹窗中没有「附件简历」选项（" + confirmRes + "），已中止";
                return r;
            }
            if ("dryrun-attach".equals(confirmRes)) {
                r.stage = "dryrun";
                r.message = "(dry-run) 到岗=" + startDate + "；已选中附件简历，未点击确认";
                return r;
            }
            r.stage = "confirmed";

            // 5) 校验结果
            PlaywrightUtil.sleep(2);
            String verify = page.evaluate("(() => {" +
                    " const t = (document.querySelector('.resume_apply')||{}).innerText || '';" +
                    " if (t.includes('已投递')||t.includes('已申请')) return 'sent';" +
                    " const body = document.body?document.body.innerText:'';" +
                    " if (body.includes('投递成功')) return 'sent';" +
                    " if (body.includes('简历不完整')||body.includes('完善简历')) return 'resume-incomplete';" +
                    " if (body.includes('请先登录')) return 'need-login';" +
                    " return 'unknown:' + t.slice(0,30);" +
                    "})()").toString();
            if (verify.startsWith("sent")) {
                r.success = true;
                r.stage = "verified";
                r.message = "投递成功";
            } else {
                r.stage = verify;
                r.message = "点击确认后未能确认成功：" + verify;
            }
            return r;
        } catch (Exception e) {
            r.stage = "error";
            r.message = "投递异常: " + e.getMessage();
            return r;
        }
    }

    /** 把 Java 字符串包成 JS 单引号字符串字面量（转义 ' 和 \\） */
    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}

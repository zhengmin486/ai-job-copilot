package com.getjobs.application.controller;

import com.getjobs.application.entity.ShixisengJobDataEntity;
import com.getjobs.application.service.ShixisengService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.ShixisengJobService;
import com.getjobs.worker.shixiseng.Shixiseng;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实习僧接口：采集 + 查询。
 * 目前只做采集与人工筛选，不提供自动投递。
 */
@Slf4j
@RestController
@RequestMapping("/api/shixiseng")
@RequiredArgsConstructor
public class ShixisengController {

    private final ShixisengService shixisengService;
    private final ShixisengJobService shixisengJobService;
    private final PlaywrightManager playwrightManager;
    @Autowired
    private ObjectProvider<Shixiseng> shixisengProvider;

    /** 触发采集 */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collect(@RequestBody(required = false) Map<String, Object> body) {
        List<String> keywords = null;
        String city = null;
        int pages = 2;
        if (body != null) {
            Object kw = body.get("keywords");
            if (kw instanceof List<?> list) {
                keywords = list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
            } else if (kw instanceof String s && !s.isBlank()) {
                keywords = List.of(s.split("[,，\\s]+"));
            }
            Object c = body.get("city");
            if (c != null) city = String.valueOf(c);
            Object p = body.get("pages");
            if (p instanceof Number n) pages = n.intValue();
            else if (p != null) {
                try {
                    pages = Integer.parseInt(String.valueOf(p));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        Map<String, Object> result = shixisengJobService.collect(keywords, city, pages);
        return ResponseEntity.ok(result);
    }

    /** 运行状态 */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("platform", "shixiseng");
        m.put("isRunning", shixisengJobService.isRunning());
        m.put("success", true);
        return ResponseEntity.ok(m);
    }

    /** 岗位列表 */
    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> jobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ShixisengJobDataEntity> rows = shixisengService.list(keyword, status, page, size);
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        m.put("data", rows);
        m.put("stats", shixisengService.stats());
        return ResponseEntity.ok(m);
    }

    /** 改状态（人工标记已投/忽略） */
    @PostMapping("/jobs/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        Object s = body.get("status");
        if (s == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "缺少 status"));
        }
        shixisengService.markStatus(id, String.valueOf(s));
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 诊断用：借真实浏览器打开实习僧任意地址，把页面现场（登录态、申请/投递按钮、简历选择）返回。
     * 实习僧投递需要先搞清楚申请按钮长什么样、点开后要不要选简历附件、是否必须登录，
     * 这些只能借真实浏览器看，不能靠裸 HTTP（会被风控拦）。
     */
    @GetMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestParam String url) {
        Map<String, Object> out = new HashMap<>();
        try {
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            p.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            out.put("url", p.url());
            out.put("title", p.title());
            out.put("text", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,1600):''"));
            out.put("applyBtns", p.evaluate(
                    "Array.from(document.querySelectorAll('button,a')).map(function(e){return (e.innerText||'').trim();})" +
                    ".filter(function(t){return /申请|投递|立即|报名|简历/.test(t);}).slice(0,20)"));
            out.put("links", p.evaluate(
                    "Array.from(document.querySelectorAll('a')).map(function(e){return ((e.innerText||'').trim().slice(0,20))+' => '+(e.getAttribute('href')||'');})" +
                    ".filter(function(s){return /简历|个人|中心|设置|resume|user|mine|profile|消息|沟通|聊天|投递|deliver|chat|im|inbox|notice/.test(s);}).slice(0,40)"));
            // 诊断用：返回整页 DOM 结构（投递记录/消息列表的 class 命名未知，先看清再写解析）
            out.put("html", p.evaluate(
                    "document.body?document.body.innerHTML.replace(/\\s+/g,' ').slice(0,45000):''"));
            // 投递/沟通相关按钮的完整 HTML：用于确认弹窗到底是「确认投递」还是「打开聊天」
            out.put("btnHtml", p.evaluate(
                    "(function(){" +
                    " var out=[];" +
                    " document.querySelectorAll('button,[class*=deliver],[class*=greeting],[class*=modal]').forEach(function(e){" +
                    "   var t=(e.innerText||'').replace(/\\s+/g,' ').trim();" +
                    "   if(t && t.length<=40 && /投递|沟通|简历|确认|确定|取消/.test(t))" +
                    "     out.push((e.outerHTML||'').replace(/\\s+/g,' ').slice(0,700));" +
                    " });" +
                    " return out.slice(0,20);" +
                    "})()"));
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "探测失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 调试接口统一取页：body 里 platform 缺省为 shixiseng，其余按平台名取已初始化的页面 */
    private Page pageOf(Map<String, Object> body) {
        Object v = body.get("platform");
        String platform = (v == null || String.valueOf(v).isBlank()) ? "shixiseng" : String.valueOf(v).trim();
        return playwrightManager.getPage(platform);
    }

    /**
     * 调试用：在当前标签页（或先导航到 url）执行一段 JS，返回执行结果。
     * 用于简历编辑这类「页内交互」的探查与操作，仅本地自用。
     */
    @PostMapping("/exec")
    public ResponseEntity<Map<String, Object>> exec(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String js = body.get("js");
        String url = body.get("url");
        if (js == null || js.isBlank()) {
            out.put("success", false);
            out.put("message", "缺少 js");
            return ResponseEntity.badRequest().body(out);
        }
        try {
            String platform = (body.get("platform") == null || body.get("platform").isBlank())
                    ? "shixiseng" : body.get("platform").trim();
            Page p = playwrightManager.getPage(platform);
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪或平台不存在: " + platform);
                return ResponseEntity.ok(out);
            }
            String finalUrl = url;
            // 页面只能在 playwright 专用线程上操作，否则跨线程访问会报错
            String finalJs = js;
            Object r = playwrightManager.callOnPlaywright(() -> {
                if (finalUrl != null && !finalUrl.isBlank()) {
                    p.navigate(finalUrl, new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(45_000));
                    Thread.sleep(3_500);
                }
                return p.evaluate(finalJs);
            });
            out.put("success", true);
            out.put("result", r == null ? null : String.valueOf(r));
            out.put("url", p.url());
            out.put("title", p.title());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "执行失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 调试用：真实鼠标点击（先 hover 让隐藏按钮显形，再点）。
     * 简历页的「编辑」按钮默认 display:none，只有 hover 到标题行才显示，JS 的 click() 点不到。
     */
    @PostMapping("/click")
    public ResponseEntity<Map<String, Object>> click(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        String selector = str(body, "selector");
        String hover = str(body, "hover");
        String text = str(body, "text");
        boolean force = Boolean.TRUE.equals(body.get("force"));
        int index = body.get("index") == null ? 0 : ((Number) body.get("index")).intValue();
        if (selector == null && text == null) {
            out.put("success", false);
            out.put("message", "缺少 selector 或 text");
            return ResponseEntity.badRequest().body(out);
        }
        try {
            Page p = pageOf(body);
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            int before = p.context().pages().size();
            if (hover != null && !hover.isBlank()) {
                p.locator(hover).nth(0).hover(new com.microsoft.playwright.Locator.HoverOptions().setTimeout(6_000));
                Thread.sleep(400);
            }
            com.microsoft.playwright.Locator loc = null;
            if (selector != null) {
                // 同名字段常有多个隐藏副本，index 按「可见元素」计数
                int seen = 0;
                for (com.microsoft.playwright.Locator l : p.locator(selector).all()) {
                    if (!l.isVisible()) continue;
                    if (seen == index) {
                        loc = l;
                        break;
                    }
                    seen++;
                }
                if (loc == null) loc = p.locator(selector).nth(index);
            } else {
                loc = p.getByText(text, new Page.GetByTextOptions().setExact(true)).nth(index);
            }
            loc.click(new com.microsoft.playwright.Locator.ClickOptions()
                    .setForce(force).setTimeout(10_000));
            Thread.sleep(1_200);
            List<Page> pages = p.context().pages();
            out.put("success", true);
            out.put("url", p.url());
            out.put("pageOpened", pages.size() - before);
            if (pages.size() > before) {
                Page np = pages.get(pages.size() - 1);
                out.put("newPageUrl", np.url());
            }
            out.put("bodyText", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,600):''"));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "点击失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 调试用：真实键盘输入。
     * antd 这类组件只认原生键盘事件（keydown/keypress/input 序列），
     * 用 JS 改 value 再 dispatch input 骗不过它们的远程搜索。
     */
    @PostMapping("/type")
    public ResponseEntity<Map<String, Object>> type(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        String selector = str(body, "selector");
        String text = str(body, "text");
        int delay = body.get("delay") == null ? 80 : ((Number) body.get("delay")).intValue();
        try {
            Page p = pageOf(body);
            if (p == null || text == null) {
                out.put("success", false);
                out.put("message", "参数或浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            if (selector != null && !selector.isBlank()) {
                com.microsoft.playwright.Locator loc = null;
                for (com.microsoft.playwright.Locator l : p.locator(selector).all()) {
                    if (l.isVisible()) {
                        loc = l;
                        break;
                    }
                }
                if (loc == null) {
                    out.put("success", false);
                    out.put("message", "没有可见的目标元素: " + selector);
                    return ResponseEntity.ok(out);
                }
                loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(8_000));
            }
            p.keyboard().type(text,
                    new com.microsoft.playwright.Keyboard.TypeOptions().setDelay(delay));
            Thread.sleep(1_500);
            out.put("success", true);
            out.put("url", p.url());
            out.put("bodyText", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-500):''"));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "输入失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 调试用：按一个键（Enter / Escape / ArrowDown 等）。 */
    @PostMapping("/key")
    public ResponseEntity<Map<String, Object>> key(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        String key = str(body, "key");
        try {
            Page p = pageOf(body);
            if (p == null || key == null) {
                out.put("success", false);
                out.put("message", "参数或浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            p.keyboard().press(key);
            Thread.sleep(1_000);
            out.put("success", true);
            out.put("url", p.url());
            out.put("bodyText", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-500):''"));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "按键失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 调试用：给 input[type=file] 塞文件。
     * 猎聘聊天窗口的图片上传控件就是这个隐藏 input（accept=jpg,jpeg,png,bmp），
     * 平时不可见，但 setInputFiles 可以直接塞。
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        String selector = str(body, "selector");
        String path = str(body, "path");
        try {
            Page p = pageOf(body);
            if (p == null || selector == null || path == null) {
                out.put("success", false);
                out.put("message", "参数或浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            java.nio.file.Path file = java.nio.file.Paths.get(path);
            if (!java.nio.file.Files.exists(file)) {
                out.put("success", false);
                out.put("message", "文件不存在: " + path);
                return ResponseEntity.ok(out);
            }
            p.locator(selector).first().setInputFiles(file);
            Thread.sleep(2_500);
            out.put("success", true);
            out.put("url", p.url());
            out.put("bodyText", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-400):''"));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "上传失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 调试用：向输入框填值（先清空再输入，触发 Vue 的 input 事件） */
    @PostMapping("/fill")
    public ResponseEntity<Map<String, Object>> fill(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        String selector = str(body, "selector");
        String value = str(body, "value");
        try {
            Page p = pageOf(body);
            if (p == null || selector == null || value == null) {
                out.put("success", false);
                out.put("message", "参数或浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            // 页面上同名字段的隐藏副本很多（每个弹窗各有一份），取第一个真正可见的
            com.microsoft.playwright.Locator loc = null;
            for (com.microsoft.playwright.Locator l : p.locator(selector).all()) {
                if (l.isVisible()) {
                    loc = l;
                    break;
                }
            }
            if (loc == null) {
                out.put("success", false);
                out.put("message", "没有可见的输入框: " + selector);
                return ResponseEntity.ok(out);
            }
            loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(8_000));
            loc.fill(value, new com.microsoft.playwright.Locator.FillOptions().setTimeout(8_000));
            Thread.sleep(300);
            out.put("success", true);
            out.put("value", loc.inputValue(new com.microsoft.playwright.Locator.InputValueOptions().setTimeout(5_000)));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "填写失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 检查实习僧登录态：打开列表页，看是否出现登录墙。
     * 投递前先调这个，未登录就别浪费投递配额。
     */
    @GetMapping("/login-check")
    public ResponseEntity<Map<String, Object>> loginCheck() {
        Map<String, Object> out = new HashMap<>();
        try {
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            playwrightManager.runOnPlaywright(() -> {
                p.navigate("https://www.shixiseng.com/interns", new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                }
            });
            String body = p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,400):''").toString();
            boolean loggedIn = !(body.contains("登录/注册") || body.contains("请先登录") || body.contains("短信登录"));
            out.put("success", true);
            out.put("loggedIn", loggedIn);
            out.put("snippet", body);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "检查失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 查回复：把实习僧「我的投递」的 7 个状态分页逐个点开，收集每个状态下的岗位记录。
     * 判据不靠猜——站点自己把投递划分为 已投递/被查看/初筛/笔试/面试/不合适/录用，
     * 只要「已投递」以外的分页里有记录，就说明 HR 已经有动作（看过简历 / 约面 / 拒绝）。
     */
    @GetMapping("/replies")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> replies() {
        Map<String, Object> out = new HashMap<>();
        try {
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            Map<String, Object> data = playwrightManager.callOnPlaywright(() -> {
                p.navigate("https://resume.shixiseng.com/my/delivered", new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                Thread.sleep(5_000);

                String[] ids = {"tab-delivered", "tab-checked", "tab-undeter", "tab-written",
                        "tab-notify", "tab-reject", "tab-employed"};
                String[] names = {"已投递", "被查看", "初筛", "笔试", "面试", "不合适", "录用"};
                List<Map<String, Object>> tabs = new ArrayList<>();
                List<Map<String, Object>> all = new ArrayList<>();
                for (int i = 0; i < ids.length; i++) {
                    p.evaluate("(function(id){var t=document.getElementById(id); if(t) t.click();})('" + ids[i] + "')");
                    Thread.sleep(1_800);
                    Object list = p.evaluate(
                            "(function(){" +
                            " var items=Array.from(document.querySelectorAll('.delivered-intern-item'))" +
                            "   .filter(function(e){var b=e.getBoundingClientRect(); return b.height>0 && b.width>0;});" +
                            " return items.map(function(it){" +
                            "   var a=it.querySelector('a.intern');" +
                            "   var c=it.querySelector('.com-name');" +
                            "   var t=it.querySelector('.time');" +
                            "   var s=it.querySelector('.del-btn span');" +
                            "   return {job:a?a.innerText.trim():'', jobUrl:a?a.href:''," +
                            "     company:c?c.innerText.replace(/\\s+/g,' ').trim():''," +
                            "     time:t?t.innerText.trim():'', status:s?s.innerText.trim():''};});" +
                            "})()");
                    List<Map<String, Object>> items = new ArrayList<>();
                    if (list instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> m) {
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                                row.put("tab", names[i]);
                                items.add(row);
                                all.add(row);
                            }
                        }
                    }
                    Map<String, Object> t = new HashMap<>();
                    t.put("name", names[i]);
                    t.put("count", items.size());
                    t.put("items", items);
                    tabs.add(t);
                }
                Map<String, Object> r = new HashMap<>();
                r.put("tabs", tabs);
                r.put("all", all);
                r.put("hasMessageBtn", p.evaluate("!!document.querySelector('.message-btn')"));
                return r;
            });
            out.put("success", true);
            out.putAll(data);

            List<Map<String, Object>> all = (List<Map<String, Object>>) out.get("all");
            int replied = 0;
            if (all != null) {
                for (Map<String, Object> row : all) {
                    if (!"已投递".equals(row.get("tab"))) replied++;
                }
            }
            out.put("deliveredCount", all == null ? 0 : all.size());
            out.put("repliedCount", replied);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "查询失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 查 HR 私信：在「我的投递」页点「查看HR消息」，把打开的新页面/弹层内容抓回来。
     * 实习僧的 HR 约面一般走站内私信，所以这个比投递状态更接近「真的有人回你」。
     */
    @GetMapping("/messages")
    public ResponseEntity<Map<String, Object>> messages() {
        Map<String, Object> out = new HashMap<>();
        try {
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            Map<String, Object> data = playwrightManager.callOnPlaywright(() -> {
                com.microsoft.playwright.BrowserContext ctx = playwrightManager.getContext();
                p.navigate("https://resume.shixiseng.com/my/delivered", new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                Thread.sleep(4_000);
                int before = ctx.pages().size();
                Object clicked = p.evaluate(
                        "(function(){var b=document.querySelector('.message-btn'); if(!b) return 'no-btn'; b.click(); return 'clicked';})()");
                Thread.sleep(5_000);

                Map<String, Object> r = new HashMap<>();
                r.put("clicked", String.valueOf(clicked));
                r.put("urlAfter", p.url());
                r.put("beforeCount", before);
                List<Map<String, Object>> pages = new ArrayList<>();
                for (Page q : ctx.pages()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("url", q.url());
                    try {
                        m.put("title", q.title());
                    } catch (Exception ignore) {
                    }
                    try {
                        m.put("text", q.evaluate(
                                "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,1500):''"));
                    } catch (Exception ignore) {
                    }
                    pages.add(m);
                }
                r.put("pages", pages);
                try {
                    r.put("overlayText", p.evaluate(
                            "(function(){var e=document.querySelector('.el-dialog__wrapper:not([style*=\"none\"]),.message-dialog,.el-drawer');" +
                            "return e?e.innerText.replace(/\\s+/g,' ').slice(0,1200):'';})()"));
                } catch (Exception ignore) {
                }
                return r;
            });
            out.put("success", true);
            out.putAll(data);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "查询失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 单岗位投递（或 dryRun 仅验证弹窗）。
     * body: {jobId?|dbId?, resumeKeyword?, message?, dryRun?}
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        try {
            String jobId = str(body, "jobId");
            Long dbId = num(body, "dbId");
            if ((jobId == null || jobId.isBlank()) && dbId != null) {
                ShixisengJobDataEntity e = shixisengService.getById(dbId);
                if (e != null) jobId = e.getJobId();
            }
            if (jobId == null || jobId.isBlank()) {
                out.put("success", false);
                out.put("message", "缺少 jobId 或 dbId");
                return ResponseEntity.badRequest().body(out);
            }
            out.putAll(runApply(jobId, dbId, str(body, "resumeKeyword"),
                    str(body, "message"), "true".equalsIgnoreCase(str(body, "dryRun"))));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "投递失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 批量投递（或 dryRun 仅验证）。
     * body: {jobs:[{jobId?|dbId?, resumeKeyword?, message?}], dryRun?}
     */
    @PostMapping("/apply-batch")
    public ResponseEntity<Map<String, Object>> applyBatch(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>();
        try {
            boolean dryRun = "true".equalsIgnoreCase(str(body, "dryRun"));
            List<Map<String, Object>> jobs = body.get("jobs") instanceof List
                    ? (List<Map<String, Object>>) body.get("jobs") : new ArrayList<>();
            List<Map<String, Object>> results = new ArrayList<>();
            int ok = 0, fail = 0;
            for (Map<String, Object> j : jobs) {
                String jobId = str(j, "jobId");
                Long dbId = num(j, "dbId");
                if ((jobId == null || jobId.isBlank()) && dbId != null) {
                    ShixisengJobDataEntity e = shixisengService.getById(dbId);
                    if (e != null) jobId = e.getJobId();
                }
                if (jobId == null || jobId.isBlank()) {
                    results.add(Map.of("success", false, "message", "缺少 jobId/dbId"));
                    fail++;
                    continue;
                }
                Map<String, Object> r = runApply(jobId, dbId, str(j, "resumeKeyword"),
                        str(j, "message"), dryRun);
                results.add(r);
                if (Boolean.TRUE.equals(r.get("success"))) ok++;
                else fail++;
                // 间隔，避免风控
                try {
                    Thread.sleep(dryRun ? 500 : 8_000);
                } catch (InterruptedException ignored) {
                }
            }
            out.put("success", true);
            out.put("total", jobs.size());
            out.put("ok", ok);
            out.put("fail", fail);
            out.put("dryRun", dryRun);
            out.put("results", results);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "批量投递失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 仅验证弹窗结构（dryRun 单岗位），返回弹窗快照与是否含留言框。
     * 上线前先跑这个，确认选择器没变、简历能选中，再正式投递。
     */
    @GetMapping("/inspect-apply")
    public ResponseEntity<Map<String, Object>> inspectApply(@RequestParam String jobId,
                                                           @RequestParam(required = false) String resumeKeyword) {
        Map<String, Object> out = new HashMap<>();
        try {
            out.putAll(runApply(jobId, null, resumeKeyword, null, true));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "验证失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 上传附件简历到实习僧账号（best-effort，需登录后人工确认是否生效）。
     * body: {path: PDF绝对路径, resumeUrl?: 简历中心地址, dryRun?}
     * 实习僧简历中心地址可能随版本变化，resumeUrl 可覆盖；找不到输入框时会返回页面结构供诊断。
     */
    @PostMapping("/upload-resume")
    public ResponseEntity<Map<String, Object>> uploadResume(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String path = body.get("path");
        String resumeUrl = body.getOrDefault("resumeUrl", "https://www.shixiseng.com/resume");
        boolean dryRun = "true".equalsIgnoreCase(body.getOrDefault("dryRun", "false"));
        if (path == null || path.isBlank()) {
            out.put("success", false);
            out.put("message", "缺少 path");
            return ResponseEntity.badRequest().body(out);
        }
        java.nio.file.Path file = java.nio.file.Paths.get(path);
        if (!java.nio.file.Files.exists(file)) {
            out.put("success", false);
            out.put("message", "文件不存在: " + path);
            return ResponseEntity.badRequest().body(out);
        }
        try {
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return ResponseEntity.ok(out);
            }
            playwrightManager.runOnPlaywright(() -> {
                p.navigate(resumeUrl, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                try {
                    Thread.sleep(4_000);
                } catch (InterruptedException ignored) {
                }
            });
            out.put("fileSizeKb", java.nio.file.Files.size(file) / 1024);
            out.put("fileInputs", p.evaluate(
                    "Array.from(document.querySelectorAll('input[type=file]')).map(function(e){" +
                            "return 'accept='+(e.getAttribute('accept')||'-')+' | class='+(e.className||'-');}).join(' ;; ')"));
            out.put("uploadTexts", p.evaluate(
                    "Array.from(document.querySelectorAll('button,a,div')).map(function(e){return (e.innerText||'').trim();})" +
                            ".filter(function(t){return /上传|附件|简历/.test(t);}).slice(0,15)"));
            if (dryRun) {
                out.put("success", true);
                out.put("message", "dryRun：仅诊断，未上传");
                return ResponseEntity.ok(out);
            }
            int inputs = Integer.parseInt(String.valueOf(
                    p.evaluate("document.querySelectorAll('input[type=file]').length")));
            if (inputs == 0) {
                out.put("success", false);
                out.put("message", "未找到文件输入框，需人工确认简历中心地址与上传入口");
                return ResponseEntity.ok(out);
            }
            p.locator("input[type=file]").first().setInputFiles(file);
            Thread.sleep(3_000);
            out.put("afterUploadBody", p.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-300):''"));
            out.put("success", true);
            out.put("message", "已尝试 setInputFiles，请人工确认附件简历是否出现在列表（实习僧可能有二次确认/解析）");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "上传失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 把本地 HTML 渲染成 PDF（借项目自带 Playwright 无头渲染，用于生成附件简历） */
    @PostMapping("/render-pdf")
    public ResponseEntity<Map<String, Object>> renderPdf(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String html = body.get("html");
        String pdf = body.get("pdf");
        if (html == null || pdf == null) {
            out.put("success", false);
            out.put("message", "缺少 html/pdf");
            return ResponseEntity.badRequest().body(out);
        }
        try {
            boolean ok = playwrightManager.renderPdf(html, pdf);
            out.put("success", ok);
            out.put("message", ok ? "已生成 PDF: " + pdf : "渲染失败，见服务日志");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "渲染异常: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 在 Playwright 线程上执行单个岗位投递，并回写 DB 状态 */
    private Map<String, Object> runApply(String jobId, Long dbId, String resumeKeyword,
                                        String message, boolean dryRun) {
        Map<String, Object> out = new HashMap<>();
        out.put("jobId", jobId);
        try {
            playwrightManager.ensureReady();
            Page p = playwrightManager.getShixisengPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器未就绪");
                return out;
            }
            Shixiseng shixiseng = shixisengProvider.getObject();
            Shixiseng.ApplyResult r = playwrightManager.callOnPlaywright(() ->
                    shixiseng.apply(p, jobId, resumeKeyword, message, dryRun));
            out.put("stage", r.stage);
            out.put("message", r.message);
            out.put("success", r.success);
            out.put("hasMessageField", r.hasMessageField);
            if (r.dialogHtml != null) out.put("dialogHtml", r.dialogHtml);
            if (dbId != null) {
                if (r.success) shixisengService.markStatus(dbId, "已投递");
                else if (!dryRun && !"already".equals(r.stage) && !"need-login".equals(r.stage))
                    shixisengService.markStatus(dbId, "投递待复核");
            }
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "投递异常: " + e.getMessage());
        }
        return out;
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private Long num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}

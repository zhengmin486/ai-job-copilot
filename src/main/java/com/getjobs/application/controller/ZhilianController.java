package com.getjobs.application.controller;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.entity.ZhilianConfigEntity;
import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.ZhilianService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.ZhilianJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 智联招聘控制器（整合版）
 * 提供智联招聘平台配置管理、Cookie管理和登录状态控制的REST API接口
 */
@Slf4j
@RestController
@RequestMapping("/api/zhilian")
@CrossOrigin(origins = "*")
public class ZhilianController {

    @Autowired
    private ZhilianService zhilianService;

    @Autowired
    private PlaywrightManager playwrightManager;

    @Autowired
    private CookieService cookieService;

    @Autowired
    private ZhilianJobService zhilianJobService;

    /**
     * 诊断用：模拟一次投递，把弹窗内容打出来，但**不点最终确认**。
     * <p>
     * 目的只有一个：确认「立即投递」之后有没有「在线简历 / 附件简历」的选项。
     * 有的话就能让脚本每次投递都选附件，彻底绕开站内那份旧在线简历。
     */
    @PostMapping("/probe-delivery")
    public ResponseEntity<Map<String, Object>> probeDelivery(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String kw = body.getOrDefault("keyword", "Python");
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            String url = "https://www.zhaopin.com/sou/?jl=538&kw="
                    + java.net.URLEncoder.encode(kw, java.nio.charset.StandardCharsets.UTF_8);
            p.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            // 前端会自己跳到规范地址，等列表出来
            try {
                p.waitForSelector(".job-list-panel .job-card",
                        new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(15_000));
            } catch (Exception ignore) {}

            p.locator(".job-list-panel .job-card").first().click();
            Thread.sleep(3_000);
            out.put("detailTitle", p.evaluate(
                    "(function(){var e=document.querySelector('.job-detail-summary__title-text');" +
                    "return e?e.innerText.trim():'';})()"));

            p.locator("button.job-detail-summary__apply").first().click();
            Thread.sleep(3_500);

            // 收集所有弹层、单选项、按钮文案 —— 不点任何确认
            out.put("overlays", p.evaluate(
                    "Array.from(document.querySelectorAll('.dialog,[class*=modal],[class*=dialog],[class*=popup],[class*=drawer]'))" +
                    ".filter(function(e){var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden'&&e.offsetHeight>0;})" +
                    ".slice(0,4).map(function(e){return (e.innerText||'').replace(/\\s+/g,' ').slice(0,500);})" +
                    ".join('  ||  ')"));
            out.put("radios", p.evaluate(
                    "Array.from(document.querySelectorAll('input[type=radio],input[type=checkbox]'))" +
                    ".map(function(e){return e.type+'|'+(e.checked?'checked':'')+'|'+" +
                    "(e.closest('label')?e.closest('label').innerText.replace(/\\s+/g,' ').slice(0,40):'');})" +
                    ".join(' ;; ')"));
            out.put("buttons", p.evaluate(
                    "Array.from(document.querySelectorAll('button,.a-button,a'))" +
                    ".map(function(e){return (e.innerText||'').trim();})" +
                    ".filter(function(t){return t && t.length<=12;})" +
                    ".slice(0,40).join(' / ')"));
            out.put("bodyTail", p.evaluate(
                    "document.body ? document.body.innerText.replace(/\\s+/g,' ').slice(-600) : ''"));
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", String.valueOf(e.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 上传（或替换）智联的附件简历，并把网络请求全程记录下来。
     * <p>
     * 之前试过一次：点「上传新附件」能出现 input[type=file]，setInputFiles 也返回成功，
     * 但附件列表没变、一个请求都没发出去。这次带全量网络监听和 DOM 快照重试，
     * 目的是分清「塞文件没触发 change」还是「触发了但被接口拒了」。
     *
     * @param body {"path": "本地 PDF 绝对路径", "dryRun": "true 时只诊断不上传"}
     */
    @PostMapping("/upload-attach")
    public ResponseEntity<Map<String, Object>> uploadAttach(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String path = body.get("path");
        boolean dryRun = "true".equalsIgnoreCase(String.valueOf(body.get("dryRun")));
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
            out.put("fileSizeKb", java.nio.file.Files.size(file) / 1024);
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            // 先跳 about:blank 再回来：智联是 SPA，直接 navigate 同一个地址有时会保留
            // 上一次的弹窗状态，弹出的是拖拽组件而不是「选择文件上传」那个弹窗。
            p.navigate("about:blank");
            p.navigate("https://i.zhaopin.com/resume", new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            dismissOverlays(p);

            // 1) 先把附件区现有结构留档，改版时能看出容器换了什么类名
            out.put("attachBlock", p.evaluate(
                    "(function(){var e=document.querySelector('.parse-item');" +
                    "if(!e) return 'NO_PARSE_ITEM';" +
                    "var box=e.parentElement&&e.parentElement.parentElement?e.parentElement.parentElement:e.parentElement;" +
                    "return (box?box.outerHTML:'').replace(/\\s+/g,' ').slice(0,3000);})()"));
            out.put("attachCountBefore", p.locator(".parse-item").count());

            // 2) 不再挂页面级 onRequest/onResponse。
            //    Playwright 的 Request/Response 是托管句柄，回调里访问它们的开销不小，
            //    而且一旦有迟到的响应撞上回收，会抛出 Object doesn't exist: response@...
            //    把整个 Page 拖坏（只能重启服务恢复）。诊断阶段已经拿到需要的接口名，
            //    现在改为纯 DOM 判定，稳定性优先。
            final java.util.List<String> netLog = new java.util.concurrent.CopyOnWriteArrayList<>();
            {
            // 3) 点上传入口
            com.microsoft.playwright.Locator uploadBtn = p.getByText("上传新附件");
            if (uploadBtn.count() == 0) uploadBtn = p.getByText("添加附件");
            out.put("uploadBtnHit", uploadBtn.count());
            if (uploadBtn.count() == 0) {
                out.put("success", false);
                out.put("message", "页面上找不到上传入口");
                return ResponseEntity.ok(out);
            }
            uploadBtn.first().click();
            Thread.sleep(2_500);

            out.put("fileInputs", p.evaluate(
                    "Array.from(document.querySelectorAll('input[type=file]')).map(function(e){" +
                    "return 'accept=' + (e.getAttribute('accept')||'-') + ' | name=' + (e.getAttribute('name')||'-')" +
                    " + ' | class=' + (e.className||'-') + ' | parent=' + (e.parentElement?e.parentElement.className:'-');" +
                    "}).join(' ;; ')"));
            // 点击后可能弹出二级弹层，把它的结构也带回来
            out.put("modalAfterClick", p.evaluate(
                    "(function(){var cs=Array.from(document.querySelectorAll('[class*=modal],[class*=dialog],[class*=upload],[class*=drawer]'));" +
                    "var shown=cs.filter(function(e){var r=e.getBoundingClientRect();" +
                    "return r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden';});" +
                    "return shown.slice(0,3).map(function(e){return e.className+' >> '+(e.innerText||'').replace(/\\s+/g,' ').slice(0,200);}).join(' || ');})()"));

            if (dryRun) {
                out.put("success", true);
                out.put("message", "dryRun：只诊断，未塞文件");
                out.put("netLog", String.join(" ;; ", netLog));
                return ResponseEntity.ok(out);
            }

            int inputs = Integer.parseInt(String.valueOf(
                    p.evaluate("document.querySelectorAll('input[type=file]').length")));
            if (inputs == 0) {
                out.put("success", false);
                out.put("message", "点击后未出现文件输入框");
                out.put("netLog", String.join(" ;; ", netLog));
                return ResponseEntity.ok(out);
            }
            // 若弹窗里有「选择文件上传」按钮，先点它，走已验证成功的那条路；
            // 点完再挑 input：优先没有 name 属性的那个（它挂在 a-dialog 上，需要再点「确定上传」）
            com.microsoft.playwright.Locator pickBtn = p.getByText("选择文件上传");
            if (pickBtn.count() > 0) {
                try {
                    pickBtn.first().click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5_000));
                    Thread.sleep(1_500);
                } catch (Exception ignore) {
                }
            }
            com.microsoft.playwright.Locator fileInput = null;
            com.microsoft.playwright.Locator allInputs = p.locator("input[type=file]");
            for (int i = 0; i < allInputs.count(); i++) {
                String name = allInputs.nth(i).getAttribute("name");
                if (name == null || name.isBlank()) {
                    fileInput = allInputs.nth(i);
                    break;
                }
            }
            if (fileInput == null) fileInput = p.locator("input[type=file][name=content]");
            if (fileInput.count() == 0) fileInput = allInputs;
            out.put("usedInput", fileInput.first().evaluate("e => e.getAttribute('name') || '(no-name)'"));
            fileInput.first().setInputFiles(file);

            // 智联的上传弹窗里有两条路，命中哪条取决于先抓到哪个 input：
            //   A) 「选择文件上传」按钮（a-dialog 里的 input）→ 选中后必须再点「确定上传」
            //   B) 拖拽区（input[name=content]）→ 选完文件直接 POST attachment/upload
            // 两条都要认，否则 B 会一直报「没有出现确定上传」。
            String pickTail = "";
            boolean uploaded = false;
            boolean confirmClicked = false;
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1_000);
                com.microsoft.playwright.Locator confirm = p.getByText("确定上传");
                if (confirm.count() > 0) {
                    confirm.first().click();
                    confirmClicked = true;
                    uploaded = true;
                    break;
                }
                // B 路：拖拽区已经直传，或解析完弹出可同步提示
                pickTail = String.valueOf(p.evaluate(
                        "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-300):''"));
                if (pickTail.contains("同步至在线简历") || pickTail.contains("解析中")
                        || pickTail.contains("上传中")) {
                    uploaded = true;
                    break;
                }
            }
            out.put("confirmClicked", confirmClicked);
            out.put("uploaded", uploaded);
            out.put("pickTail", pickTail);
            if (!uploaded) {
                out.put("success", false);
                out.put("message", "上传未触发（既没有「确定上传」，也没有 attachment/upload 请求）");
                out.put("netLog", String.join(" ;; ", netLog));
                return ResponseEntity.ok(out);
            }

            // 解析 PDF 需要时间。附件数可能涨（新增），也可能不涨（同名覆盖），
            // 所以除了数数量，也认「可同步提示」和「解析失败」两种终态。
            int before = p.locator(".parse-item").count();
            String tail = "";
            boolean added = false;
            for (int i = 0; i < 30; i++) {
                Thread.sleep(2_000);
                int now = p.locator(".parse-item").count();
                tail = String.valueOf(p.evaluate(
                        "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-300):''"));
                if (now > before || tail.contains("同步至在线简历")) {
                    added = true;
                    break;
                }
                if (tail.contains("解析失败") || tail.contains("上传失败")) break;
            }
            out.put("attachCountAfter", p.locator(".parse-item").count());
            out.put("added", added);
            out.put("netLog", String.join(" ;; ", netLog));
            out.put("textTail", tail);
            out.put("attachments", p.evaluate(
                    "Array.from(document.querySelectorAll('.parse-item')).map(function(e){" +
                    "var n=e.querySelector('.parse-item__left-title-text');var c=e.querySelector('.parse-item__right-text');" +
                    "return (n?n.innerText.trim():'?')+' ['+(c?c.innerText.trim():'?')+']';}).join(' | ')"));
            out.put("success", added);
            out.put("message", added ? "附件已新增" : "附件列表没有变化，见 netLog 与 textTail");

            // 7) 可选：立刻点开「同步至在线简历」对话框。
            //    这个入口只存在于解析完成后的提示里，页面一导航就消失，
            //    所以必须在同一次会话里点，不能分两次调用。
            if (added && "true".equalsIgnoreCase(String.valueOf(body.get("syncAfter")))) {
                com.microsoft.playwright.Locator sync = p.getByText("同步至在线简历");
                out.put("syncHit", sync.count());
                if (sync.count() > 0) {
                    sync.first().evaluate("e => e.click()");
                    Thread.sleep(2_500);
                    out.put("syncDialog", p.evaluate(
                            "(function(){var e=document.querySelector('.upload-to-confirm__modal');" +
                            "return e?(e.innerText||'').replace(/\\s+/g,' ').slice(0,600):'NO_DIALOG';})()"));
                }
            }
            }
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "上传失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 诊断用：用已登录的浏览器打开指定地址，把页面现场信息返回。
     * <p>
     * 智联的简历页挡掉了所有裸 HTTP 请求（EdgeOne 人机验证），只能借真实浏览器看。
     * 用来确认「在线简历能不能自动改」「附件简历能不能自动传」这两个问题。
     */
    @GetMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(@RequestParam String url,
                                                     @RequestParam(required = false) String click) {
        Map<String, Object> out = new HashMap<>();
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            p.navigate(url, new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            // 可选：先点一下某个按钮再看，用于展开“上传附件”这类二级入口
            if (click != null && !click.isBlank()) {
                try {
                    com.microsoft.playwright.Locator el = p.locator(":has-text(\"" + click + "\")").first();
                    if (el.count() > 0) {
                        el.click();
                        out.put("clicked", click);
                        Thread.sleep(3_000);
                    } else {
                        out.put("clicked", "未找到: " + click);
                    }
                } catch (Exception ce) {
                    out.put("clicked", "点击失败: " + ce.getMessage());
                }
            }
            out.put("success", true);
            out.put("url", p.url());
            out.put("title", p.title());
            out.put("text", p.evaluate(
                    "document.body ? document.body.innerText.replace(/\\s+/g,' ').slice(0,1200) : ''"));
            out.put("fileInputs", p.evaluate("document.querySelectorAll('input[type=file]').length"));
            out.put("resumeBtns", p.evaluate(
                    "Array.from(document.querySelectorAll('button,a,div[role=button]'))" +
                    ".map(function(e){return (e.innerText||'').trim();})" +
                    ".filter(function(t){return t && t.length<=16 && /简历|上传|附件|导入|更新/.test(t);})" +
                    ".slice(0,25).join(' | ')"));
            out.put("resumeLinks", p.evaluate(
                    "Array.from(document.querySelectorAll('a'))" +
                    ".filter(function(a){return /简历|附件/.test(a.innerText||'');})" +
                    ".slice(0,12).map(function(a){return (a.innerText||'').trim()+' -> '+a.href;}).join(' | ')"));
            // 简历管理区：哪份是默认、上传入口长什么样
            out.put("resumeMgmt", p.evaluate(
                    "(function(){var all=Array.from(document.querySelectorAll('div'));" +
                    "var hit=all.filter(function(e){return (e.innerText||'').indexOf('我的在线简历')>=0;});" +
                    "if(!hit.length) return 'NO_BLOCK';" +
                    "var el=hit[hit.length-1];" +
                    "return el.outerHTML.replace(/\\s+/g,' ').slice(0,2500);})()"));
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", String.valueOf(e.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 简历管理页的投递候选清单：在线简历 + 附件简历，各自标出当前是不是默认投递。
     * <p>
     * 智联投递不弹选择框，点「立即投递」直接发出去，发的是简历管理页里被标成
     * 「默认投递」的那一份。所以要让投递用上我们维护的 PDF，唯一开关就是这里。
     * <p>
     * 选择器（.online-resume / .parse-item + __left-title-text / __right-text）
     * 取自 GitHub 上已跑通的同类实现，智联改版时这里最容易失效，故做成可观测接口。
     */
    @GetMapping("/resume-options")
    public ResponseEntity<Map<String, Object>> resumeOptions() {
        Map<String, Object> out = new HashMap<>();
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            p.navigate("https://i.zhaopin.com/resume", new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            out.put("url", p.url());
            out.put("account", p.evaluate(
                    "(function(){var e=document.querySelector('.zp-welcome__username');" +
                    "return e?e.innerText.trim():'';})()"));
            // kind/reference/control 三元组：control 为「默认投递」的就是当前生效的那份
            out.put("options", p.evaluate(
                    "(function(){" +
                    "  var t=function(e){return e?(e.innerText||'').trim():'';};" +
                    "  var online=Array.from(document.querySelectorAll('.online-resume')).map(function(e){" +
                    "    return {kind:'online',reference:t(e.querySelector('.online-resume__name'))," +
                    "      control:t(e.querySelector('.online-resume__right-text'))," +
                    "      time:t(e.querySelector('.online-resume__time'))};});" +
                    "  var atts=Array.from(document.querySelectorAll('.parse-item')).map(function(e){" +
                    "    return {kind:'attachment',reference:t(e.querySelector('.parse-item__left-title-text'))," +
                    "      control:t(e.querySelector('.parse-item__right-text'))};});" +
                    "  return JSON.stringify({online:online,attachments:atts});})()"));
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", String.valueOf(e.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 把指定的那一份简历设为「默认投递」，投递时就发它。
     *
     * @param body {"kind":"attachment|online", "reference":"文件名或在线简历名"}
     */
    @PostMapping("/resume-default")
    public ResponseEntity<Map<String, Object>> setDefaultResume(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String kind = body.getOrDefault("kind", "attachment");
        String reference = body.get("reference");
        if (reference == null || reference.isBlank()) {
            out.put("success", false);
            out.put("message", "缺少 reference");
            return ResponseEntity.badRequest().body(out);
        }
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            p.navigate("https://i.zhaopin.com/resume", new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            dismissOverlays(p);

            String itemSel = "online".equals(kind) ? ".online-resume" : ".parse-item";
            String nameSel = "online".equals(kind) ? ".online-resume__name" : ".parse-item__left-title-text";
            String ctrlSel = "online".equals(kind) ? ".online-resume__right-text" : ".parse-item__right-text";

            com.microsoft.playwright.Locator items = p.locator(itemSel);
            int n = items.count();
            out.put("total", n);
            int hit = -1;
            StringBuilder seen = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String name;
                try {
                    name = items.nth(i).locator(nameSel).innerText().trim();
                } catch (Exception ignore) {
                    continue;
                }
                if (seen.length() > 0) seen.append(" | ");
                seen.append(name);
                if (name.equals(reference.trim())) {
                    hit = i;
                    break;
                }
            }
            out.put("seen", seen.toString());
            if (hit < 0) {
                out.put("success", false);
                out.put("message", "没有找到这份简历: " + reference);
                return ResponseEntity.ok(out);
            }
            String before = items.nth(hit).locator(ctrlSel).innerText().trim();
            out.put("controlBefore", before);
            if ("默认投递".equals(before)) {
                out.put("success", true);
                out.put("message", "它已经是默认投递，无需切换");
                return ResponseEntity.ok(out);
            }
            // 记录遮挡物：上传流程的「确定上传」弹层可能会以全屏遮罩留在页面上
            out.put("overlay", p.evaluate(
                    "Array.from(document.querySelectorAll('.ivu-modal-wrap')).filter(function(e){" +
                    "var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).display!=='none';})" +
                    ".map(function(e){return (e.className||'')+' >> '+(e.innerText||'').replace(/\\s+/g,' ').slice(0,150);})" +
                    ".join(' || ')"));
            // 先用正常点击；被遮挡时退回 DOM 直发 click ——
            // 「设为默认」是挂在 <p> 上的 Vue 事件，直发同样能触发，且不受遮罩影响
            String clickMode;
            try {
                items.nth(hit).locator(ctrlSel)
                        .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(6_000));
                clickMode = "normal";
            } catch (Exception clickErr) {
                items.nth(hit).locator(ctrlSel).evaluate("e => e.click()");
                clickMode = "dom-dispatch";
            }
            out.put("clickMode", clickMode);
            // 切换是异步的，轮询等右侧文案真的变成「默认投递」再下结论
            String after = before;
            for (int i = 0; i < 12; i++) {
                Thread.sleep(1_000);
                try {
                    after = items.nth(hit).locator(ctrlSel).innerText().trim();
                } catch (Exception ignore) {
                    continue;
                }
                if ("默认投递".equals(after)) break;
            }
            out.put("controlAfter", after);
            boolean ok = "默认投递".equals(after);
            out.put("success", ok);
            out.put("message", ok ? "已把「" + reference + "」设为默认投递"
                    : "点击后文案未变化，切换可能未生效");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "切换失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // ==================== 配置管理相关接口 ====================

    /**
     * 获取 智联 配置与可选项
     */
    @GetMapping("/config")
    public Map<String, Object> getAllZhilianConfig() {
        Map<String, Object> result = new HashMap<>();

        ZhilianConfigEntity config = zhilianService.getFirstConfig();
        if (config == null) {
            config = new ZhilianConfigEntity();
        }

        Map<String, List<Map<String, String>>> options = new HashMap<>();
        options.put("city", zhilianService.getOptionsByType("city").stream().map(e -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", e.getName());
            m.put("code", e.getCode());
            return m;
        }).collect(Collectors.toList()));
        // 智联薪资目前不枚举，前端可用文本输入或简单选择"不限"

        result.put("config", config);
        result.put("options", options);
        return result;
    }

    /**
     * 更新 智联 配置（选择性更新第一条记录）
     */
    @PutMapping("/config")
    public ZhilianConfigEntity updateConfig(@RequestBody ZhilianConfigEntity config) {
        return zhilianService.updateConfig(config);
    }

    /**
     * 返回城市选项列表
     */
    @GetMapping("/config/options/city")
    public List<Map<String, String>> getCityOptions() {
        return zhilianService.getOptionsByType("city").stream().map(e -> {
            Map<String, String> m = new HashMap<>();
            m.put("name", e.getName());
            m.put("code", e.getCode());
            return m;
        }).collect(Collectors.toList());
    }

    // ==================== 登录和认证相关接口 ====================

    /**
     * 检查登录状态
     * @return 登录状态信息
     */
    @GetMapping("/login-status")
    public ResponseEntity<Map<String, Object>> checkLoginStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean isLoggedIn = playwrightManager.isLoggedIn("zhilian");
            response.put("success", true);
            response.put("isLoggedIn", isLoggedIn);
            response.put("message", isLoggedIn ? "已登录" : "未登录");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("检查登录状态失败", e);
            response.put("success", false);
            response.put("message", "检查登录状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 触发智联招聘登录：在未登录时点击二维码入口，等待扫码
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> triggerZhilianLogin() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.triggerZhilianLogin();
            response.put("success", true);
            response.put("message", "已尝试打开智联二维码登录入口，请在浏览器扫码登录");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("触发智联登录失败", e);
            response.put("success", false);
            response.put("message", "触发登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 退出登录：清空数据库Cookie并清理运行中的上下文Cookie
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logoutZhilian() {
        Map<String, Object> response = new HashMap<>();
        try {
            // 更新登录状态为未登录并触发SSE通知
            playwrightManager.setLoginStatus("zhilian", false);

            // 清空数据库中 智联招聘 平台的所有 Cookie 值
            cookieService.clearCookieByPlatform("zhilian", "manual logout");

            // 清理运行中的上下文Cookie
            try {
                playwrightManager.clearZhilianCookies();
            } catch (Exception e) {
                log.warn("清理智联招聘上下文Cookie时发生异常，但不影响退出流程: {}", e.getMessage());
            }

            response.put("success", true);
            response.put("message", "智联招聘已退出登录，数据库Cookie和上下文Cookie均已清理");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("退出登录失败", e);
            response.put("success", false);
            response.put("message", "退出登录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== Cookie管理相关接口 ====================

    /**
     * 调试接口：读取数据库中的 智联招聘 Cookie 记录
     */
    @GetMapping("/cookie")
    public ResponseEntity<Map<String, Object>> getZhilianCookieRecord() {
        Map<String, Object> response = new HashMap<>();
        try {
            CookieEntity cookie = cookieService.getCookieByPlatform("zhilian");
            Map<String, Object> data = new HashMap<>();
            if (cookie != null) {
                data.put("platform", cookie.getPlatform());
                data.put("cookie_value", cookie.getCookieValue());
                data.put("remark", cookie.getRemark());
                data.put("created_at", cookie.getCreatedAt());
                data.put("updated_at", cookie.getUpdatedAt());
            } else {
                data.put("platform", "zhilian");
                data.put("cookie_value", null);
                data.put("message", "未找到智联招聘Cookie记录");
            }
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "读取Cookie记录失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 调试接口：主动保存当前上下文中的 智联招聘 Cookie 到数据库
     */
    @PostMapping("/save-cookie")
    public ResponseEntity<Map<String, Object>> saveZhilianCookie() {
        Map<String, Object> response = new HashMap<>();
        try {
            playwrightManager.saveZhilianCookiesToDb("manual save");
            response.put("success", true);
            response.put("message", "已主动保存智联招聘Cookie到数据库");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "保存智联招聘Cookie失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 数据分析与列表 ====================

    /** 投递统计（Dashboard） */
    @GetMapping("/stats")
    public ZhilianService.StatsResponse stats(
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        java.util.List<String> statusList = null;
        if (statuses != null && !statuses.trim().isEmpty()) {
            statusList = java.util.Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }
        return zhilianService.getZhilianStats(statusList, location, experience, degree, minK, maxK, keyword);
    }

    /** 岗位列表（分页 + 筛选） */
    @GetMapping("/list")
    public ZhilianService.PagedResult list(
            @RequestParam(value = "statuses", required = false) String statuses,
            @RequestParam(value = "location", required = false) String location,
            @RequestParam(value = "experience", required = false) String experience,
            @RequestParam(value = "degree", required = false) String degree,
            @RequestParam(value = "minK", required = false) Double minK,
            @RequestParam(value = "maxK", required = false) Double maxK,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size
    ) {
        java.util.List<String> statusList = null;
        if (statuses != null && !statuses.trim().isEmpty()) {
            statusList = java.util.Arrays.stream(statuses.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
        }
        return zhilianService.listZhilianJobs(statusList, location, experience, degree, minK, maxK, keyword, page, size);
    }

    // ==================== 任务管理相关接口 ====================

    /**
     * 启动智联招聘自动投递任务
     * @return 响应结果
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startZhilianJob() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 未登录则不允许启动
            if (!playwrightManager.isLoggedIn("zhilian")) {
                response.put("success", false);
                response.put("message", "请先登录智联招聘");
                response.put("status", "not_logged_in");
                return ResponseEntity.badRequest().body(response);
            }

            // 检查是否已有任务在运行
            if (zhilianJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "智联招聘任务已在运行中，请等待当前任务完成");
                response.put("status", "running");
                return ResponseEntity.badRequest().body(response);
            }

            // 异步启动新任务
            CompletableFuture.runAsync(() -> {
                zhilianJobService.executeDelivery(progressMessage -> {
                    log.info("[{}] {}", progressMessage.getPlatform(), progressMessage.getMessage());
                });
            });

            response.put("success", true);
            response.put("message", "智联招聘任务启动成功");
            response.put("status", "started");

            log.info("通过API启动智联招聘任务成功");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("启动智联招聘任务失败", e);
            response.put("success", false);
            response.put("message", "启动智联招聘任务失败: " + e.getMessage());
            response.put("error", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 停止智联招聘任务
     * @return 响应结果
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopZhilianJob() {
        Map<String, Object> response = new HashMap<>();

        try {
            if (!zhilianJobService.isRunning()) {
                response.put("success", false);
                response.put("message", "没有正在运行的智联招聘任务");
                return ResponseEntity.badRequest().body(response);
            }

            zhilianJobService.stopDelivery();

            response.put("success", true);
            response.put("message", "智联招聘任务停止请求已发送");

            log.info("通过API停止智联招聘任务");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("停止智联招聘任务失败", e);
            response.put("success", false);
            response.put("message", "停止智联招聘任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 获取当前运行状态
     * @return 当前状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getCurrentStatus() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> status = zhilianJobService.getStatus();
            response.put("success", true);
            response.putAll(status);
            response.put("timestamp", System.currentTimeMillis());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取当前状态失败", e);
            response.put("success", false);
            response.put("message", "获取状态失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 健康检查接口 ====================

    /**
     * 健康检查接口
     * @return 服务状态
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("service", "ZhilianController");
        response.put("status", "healthy");
        response.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.ok(response);
    }

    /**
     * 把附件解析出的内容同步进站内在线简历。
     * <p>
     * 智联在上传附件后会弹「附件中有 N 项内容可同步 …… 同步至在线简历」，
     * 这是站点自带的能力，比逐字段填表可靠得多。
     * <p>
     * 分两步走，因为同步大概率有二次确认（选要同步哪些段落）：
     * stage=open 只点开看弹层内容；stage=confirm 再往下点确认。
     *
     * @param body {"stage": "open|confirm"}
     */
    @PostMapping("/sync-online")
    public ResponseEntity<Map<String, Object>> syncOnline(@RequestBody(required = false) Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String stage = body == null ? "open" : body.getOrDefault("stage", "open");
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            // 上传解析后弹层会留在页面上；它本身就是同步对话框。
            // 注意：一旦导航走，这个弹层就没了（同步入口只存在于弹层里），
            // 所以弹层在场时绝不能再点一次同步，也不能导航。
            boolean modal = Boolean.parseBoolean(String.valueOf(p.evaluate(
                    "!!Array.from(document.querySelectorAll('.upload-to-confirm__modal')).find(function(e){" +
                    "var r=e.getBoundingClientRect();return r.width>0&&r.height>0;})")));
            out.put("modalPresent", modal);
            out.put("dialogText", p.evaluate(
                    "(function(){var e=document.querySelector('.upload-to-confirm__modal');" +
                    "return e?(e.innerText||'').replace(/\\s+/g,' ').slice(0,600):'';})()"));
            if (!modal) {
                p.navigate("https://i.zhaopin.com/resume", new com.microsoft.playwright.Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(30_000));
                Thread.sleep(4_000);
                com.microsoft.playwright.Locator sync = p.getByText("同步至在线简历");
                out.put("syncHit", sync.count());
                if (sync.count() == 0) {
                    out.put("success", false);
                    out.put("message", "页面上没有同步入口：该入口只在上传解析完成后的提示弹层里出现，需先上传附件");
                    return ResponseEntity.ok(out);
                }
                sync.first().evaluate("e => e.click()");
                Thread.sleep(3_000);
                out.put("dialogText", p.evaluate(
                        "(function(){var e=document.querySelector('.upload-to-confirm__modal');" +
                        "return e?(e.innerText||'').replace(/\\s+/g,' ').slice(0,600):'';})()"));
            }

            if (!"confirm".equals(stage)) {
                out.put("success", true);
                out.put("message", "同步对话框已就绪（stage=open，未点确认）");
                return ResponseEntity.ok(out);
            }
            // 二次确认：措辞不定，按优先级找可见按钮
            String[] labels = {"确认同步", "确定同步", "确认替换", "确定", "确认", "开始同步"};
            String clickedLabel = "";
            for (String label : labels) {
                com.microsoft.playwright.Locator btn = p.getByText(label, new com.microsoft.playwright.Page.GetByTextOptions()
                        .setExact(true));
                int n = btn.count();
                for (int i = 0; i < n; i++) {
                    try {
                        if (btn.nth(i).isVisible()) {
                            btn.nth(i).evaluate("e => e.click()");
                            clickedLabel = label;
                            break;
                        }
                    } catch (Exception ignore) {
                    }
                }
                if (!clickedLabel.isEmpty()) break;
            }
            out.put("confirmedBy", clickedLabel);
            String tail = "";
            for (int i = 0; i < 15; i++) {
                Thread.sleep(2_000);
                tail = String.valueOf(p.evaluate(
                        "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(-400):''"));
                if (tail.contains("成功") || tail.contains("同步完成") || tail.contains("失败")) break;
            }
            out.put("tail", tail);
            // 同步后在线简历的更新时间应变成今天
            out.put("onlineResumeTime", p.evaluate(
                    "(function(){var e=document.querySelector('.online-resume__time');return e?e.innerText.trim():'';})()"));
            out.put("success", !clickedLabel.isEmpty());
            out.put("message", clickedLabel.isEmpty() ? "没找到确认按钮，见 afterOpenText" : "已点「" + clickedLabel + "」");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "同步失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 删除一份附件简历（按文件名精确匹配）。
     * <p>
     * 上传接口不幂等，同一个文件重传会在列表里留下重名项，需要一个清理手段。
     * 删除图标藏在每项的 hover 浮层（.parse-action-area__right__delete-icon）里，
     * DOM 里本来就有、只是 display:none，所以直发 click 即可，不必真的 hover。
     *
     * @param body {"reference": "文件名"}
     */
    @PostMapping("/resume-delete")
    public ResponseEntity<Map<String, Object>> deleteAttachment(@RequestBody Map<String, String> body) {
        Map<String, Object> out = new HashMap<>();
        String reference = body.get("reference");
        if (reference == null || reference.isBlank()) {
            out.put("success", false);
            out.put("message", "缺少 reference");
            return ResponseEntity.badRequest().body(out);
        }
        try {
            com.microsoft.playwright.Page p = playwrightManager.getZhilianPage();
            if (p == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未就绪");
                return ResponseEntity.ok(out);
            }
            p.navigate("https://i.zhaopin.com/resume", new com.microsoft.playwright.Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(4_000);
            dismissOverlays(p);

            out.put("before", p.evaluate(
                    "Array.from(document.querySelectorAll('.parse-item')).map(function(e){" +
                    "var n=e.querySelector('.parse-item__left-title-text');return n?n.innerText.trim():'?';}).join(' | ')"));
            Object clicked = p.evaluate(
                    "(function(ref){" +
                    "  var its=document.querySelectorAll('.parse-item');" +
                    "  for (var i=0;i<its.length;i++){" +
                    "    var n=its[i].querySelector('.parse-item__left-title-text');" +
                    "    if(!n||n.innerText.trim()!==ref) continue;" +
                    "    var pop=its[i].closest('.parse-item__poptip')||its[i].parentElement;" +
                    "    var del=pop?pop.querySelector('.parse-action-area__right__delete-icon'):null;" +
                    "    if(!del) return 'no_delete_icon';" +
                    "    del.click(); return 'clicked';" +
                    "  }" +
                    "  return 'notfound';" +
                    "})", reference.trim());
            out.put("clickResult", clicked);
            if (!"clicked".equals(String.valueOf(clicked))) {
                out.put("success", false);
                out.put("message", "没找到可删除的「" + reference + "」");
                return ResponseEntity.ok(out);
            }
            Thread.sleep(1_500);
            // 删除通常有二次确认
            String confirmLabel = "";
            for (String label : new String[]{"确定", "确认删除", "删除"}) {
                com.microsoft.playwright.Locator btn = p.getByText(label,
                        new com.microsoft.playwright.Page.GetByTextOptions().setExact(true));
                int n = btn.count();
                for (int i = 0; i < n; i++) {
                    try {
                        if (btn.nth(i).isVisible()) {
                            btn.nth(i).evaluate("e => e.click()");
                            confirmLabel = label;
                            break;
                        }
                    } catch (Exception ignore) {
                    }
                }
                if (!confirmLabel.isEmpty()) break;
            }
            out.put("confirmedBy", confirmLabel);
            Thread.sleep(3_000);
            out.put("after", p.evaluate(
                    "Array.from(document.querySelectorAll('.parse-item')).map(function(e){" +
                    "var n=e.querySelector('.parse-item__left-title-text');return n?n.innerText.trim():'?';}).join(' | ')"));
            boolean gone = !String.valueOf(out.get("after")).contains(reference.trim());
            out.put("success", gone);
            out.put("message", gone ? "已删除「" + reference + "」" : "删除后列表里仍在，见 before/after");
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "删除失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    // ==================== 辅助方法 ====================

    /**
     * 关掉页面上残留的弹层。
     * <p>
     * 智联的「确定上传」弹窗是 iView Modal（.ivu-modal-wrap），一旦上次操作没关，
     * 它会以 fixed 全屏遮罩盖住整个页面，之后所有点击都会报
     * 「subtree intercepts pointer events」直到超时。所以每次进简历页先清一遍。
     */
    private void dismissOverlays(com.microsoft.playwright.Page p) {
        try {
            for (int i = 0; i < 3; i++) {
                int vis = Integer.parseInt(String.valueOf(p.evaluate(
                        "Array.from(document.querySelectorAll('.ivu-modal-wrap')).filter(function(e){" +
                        "var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).display!=='none';}).length")));
                if (vis == 0) return;
                com.microsoft.playwright.Locator close = p.locator(".ivu-modal-close, .ivu-icon-ios-close");
                boolean clicked = false;
                for (int k = 0; k < close.count(); k++) {
                    if (close.nth(k).isVisible()) {
                        close.nth(k).click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(3_000));
                        clicked = true;
                        break;
                    }
                }
                if (!clicked) p.keyboard().press("Escape");
                Thread.sleep(1_000);
            }
        } catch (Exception ignore) {
            // 清不掉也不影响后续逻辑，交给调用方自己的超时处理
        }
    }


    // 旧枚举构建方法已移除，改为从数据库读取
}

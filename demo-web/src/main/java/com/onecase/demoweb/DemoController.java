package com.onecase.demoweb;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;

/**
 * Demo Web App - 给 Playwright MCP 一个真实可操作的页面
 *
 * Phase 2.5: 最小 login + dashboard
 * - 内存账号: test / test123
 * - 配置项 app.login.button-text 用于演示 button text 改名场景
 */
@Controller
@RequestMapping("/demo")
public class DemoController {

    @org.springframework.beans.factory.annotation.Value("${app.demo.username:test}")
    private String username;

    @org.springframework.beans.factory.annotation.Value("${app.demo.password:test123}")
    private String password;

    @org.springframework.beans.factory.annotation.Value("${app.login.button-text:登录}")
    private String buttonText;

    @org.springframework.beans.factory.annotation.Value("${app.login.welcome:欢迎回来,登录成功}")
    private String welcomeText;

    @GetMapping("/login")
    public String loginPage(HttpSession session) {
        if (session.getAttribute("user") != null) {
            return "redirect:/demo/dashboard";
        }
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String username,
                          @RequestParam String password,
                          HttpSession session,
                          Model model) {
        if (this.username.equals(username) && this.password.equals(password)) {
            session.setAttribute("user", username);
            return "redirect:/demo/dashboard";
        }
        model.addAttribute("error", "用户名或密码错误");
        model.addAttribute("buttonText", buttonText);
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        Object user = session.getAttribute("user");
        if (user == null) {
            return "redirect:/demo/login";
        }
        model.addAttribute("username", user);
        model.addAttribute("welcome", welcomeText);
        return "dashboard";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/demo/login";
    }
}
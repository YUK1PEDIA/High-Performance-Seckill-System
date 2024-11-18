package com.hmdp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    // 前置拦截器实现用户登录校验
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // ThreadLocal 中没有用户，拦截
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // ThreadLocal 中有用户，直接放行
        return true;
    }
}

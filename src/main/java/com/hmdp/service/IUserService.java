package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 用户登录
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 用户签到
     * @return
     */
    Result sign();

    /**
     * 记录签到天数
     * @return
     */
    Result signCount();
}

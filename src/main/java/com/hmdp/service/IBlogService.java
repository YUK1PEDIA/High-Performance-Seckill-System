package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查看 blog 内容（根据 id 查询 blog）
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 查看热门 blog
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * blog 点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);
}

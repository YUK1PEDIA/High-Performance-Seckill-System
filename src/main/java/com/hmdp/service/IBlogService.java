package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

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

    /**
     * 查看点赞排行榜
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存 blog
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 实现滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
